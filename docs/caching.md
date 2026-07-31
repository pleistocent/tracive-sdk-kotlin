# Caching

The local store of previous answers. Reachable as `client.cache`.

Three reasons it exists, in the order that matters:

1. **Money.** Every request that reaches the server is billed, **a miss included** — the server did
   the lookup either way. A cache hit costs nothing.
2. **Offline.** A hit is a real answer with no network at all.
3. **Latency.** No round trip.

---

## Wiring it up

The cache is **on by default in policy and off by default in wiring.** `CachePolicy.Default` is
enabled, but `TraciveClient.cache` defaults to `DisabledCheckCache` until you pass one, because the
persistent cache needs two things only your app can supply: a SQLite driver and a platform key
store. Nothing about correctness depends on it — a disabled cache only costs credits.

### Android

```kotlin
val driver = AndroidSqliteDriver(
  schema = SqlDelightCheckCache.Schema,
  context = context,
  name = "tracive-check-cache.db",
)

val cache = SqlDelightCheckCache(
  driver = driver,
  dataKeyStore = KeystoreCacheDataKeyStore(context),  // yours; see below
  policy = CachePolicy.Default,
  ioContext = Dispatchers.IO,
)

val client = TraciveClient(mode = /* … */, cache = cache)
```

Pass `Dispatchers.IO` on Android. The SDK's default is `Dispatchers.Default`, not because that is
better but because common Kotlin cannot name `Dispatchers.IO` — it is declared per platform. Default
is a CPU-sized pool, and blocking one of its threads on a disk read is avoidable. What the default
does guarantee is that the cache never touches the main thread, so it cannot cause an ANR by
omission.

### iOS

```kotlin
val driver = NativeSqliteDriver(
  schema = SqlDelightCheckCache.Schema,
  name = "tracive-check-cache.db",
)

val cache = SqlDelightCheckCache(driver = driver, dataKeyStore = KeychainCacheDataKeyStore())
```

`SqlDelightCheckCache.Schema` is re-exported by the cache so you never import the SDK's generated
`id.tracive.sdk.cache.db` package, which exists to serve the implementation and is not part of the
supported surface.

The cache never closes the driver. The driver's lifetime belongs to whoever created it — a host that
shares one across several components would be badly surprised if the cache closed it on their behalf.

### The key store is yours to supply

`CacheDataKeyStore` is an interface, and the SDK ships no platform implementation of it. That is
deliberate. The cipher is common Kotlin so that a record written on Android opens byte-for-byte on
iOS; that leaves exactly one job the platform must do, and it is this one — produce key material from
a real entropy source and keep it where the OS protects it. Common Kotlin has no cryptographically
secure RNG (`kotlin.random.Random` is not one), and a key from a weak source is worse than no
encryption because it *looks* like encryption.

`samples/android-compose/.../KeystoreCacheDataKeyStore.kt` is the reference implementation, meant to
be copied. It uses the wrapping pattern, which is the only shape that works with a software cipher:

```
data key (32 random bytes, in memory only)
  └─ AES-256-GCM ──> wrapped blob in the app's private files directory
       under a non-exportable AndroidKeystore key
```

A Keystore key cannot be exported — you can ask it to encrypt, never to hand over its bytes — while
the SDK's software cipher needs the 32 bytes. So the Keystore holds a *wrapping* key and the wrapped
data key goes to a file. The file is worthless alone: unwrapping it means executing code as your app
on that device, and on a device with a TEE or StrongBox the wrapping key never leaves secure
hardware. On iOS the equivalent is a Keychain item with
`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`.

Three rules an implementation must honour:

1. `createKey()` returns **fresh** 32 bytes from a secure source every time, persists them, and never
   returns the same key twice.
2. `loadKey(id)` returns exactly what was stored, or **null** if it is gone. Null is expected, not
   exceptional — an OS keystore reset, a restore onto new hardware, a biometric enrolment change.
   Return null rather than throwing; the SDK treats it as an empty cache, drops the unreadable rows,
   and starts over with a new key. The user sees nothing. It costs one re-fetch.
3. Keys are never logged, never written in the clear, never leave the device.

**Never pair `InMemoryCacheDataKeyStore` with a persistent cache.** It is for tests and for
`InMemoryCheckCache`. The reason is specific and unforgiving: the cache's nonces are a counter that
restarts at zero whenever the database is recreated, which is safe *only* because a new database also
gets a new key. A store that hands the same key to a fresh database produces a repeated (key, nonce)
pair, and that is the one failure AES-GCM does not degrade gracefully from — it leaks the
authentication subkey for every record written under it.

---

## TTL

| Risk tier | Default | Why |
|---|---|---|
| `HIGH` | 30 days | Rests on a body of corroborated reports. Almost never flips. |
| `ELEVATED` | 7 days | |
| `CAUTION` | 24 hours | |
| `NONE` | 1 hour | Flips the moment the first report lands. |

Tiers age at very different rates, which is the whole reason for four numbers instead of one. A
single TTL would either serve stale clean answers or throw away the expensive ones.

Tune them if you have a reason:

```kotlin
CachePolicy(highTtl = 7.days, noneTtl = 15.minutes)
```

### The server's override wins

A response may carry `cacheTtlSeconds`, and when it does, it replaces the tier default:

```kotlin
policy.effectiveTtl(riskTier, cacheTtlSeconds)
```

**Leave `honorServerTtlOverride` on.** It is the fleet-wide invalidation lever — the only way the
backend can make already-installed apps stop trusting cached answers without shipping a release.
Turning it off opts your users out of that.

Three edge cases, all tested:

- **`cacheTtlSeconds = 0`** means "do not keep this answer". Nothing is stored, *and any existing
  entry is removed* — that is how the backend retracts something already on the device.
- **A negative value** is treated as malformed and ignored, so the tier default applies. A bad
  response degrades to normal caching rather than silently disabling it.
- **An absurdly large value** saturates instead of overflowing. A wrapped `Long` would read as
  "expired decades ago", every entry would miss, every check would be billed, and nothing in the
  logs would say why.

### Expiry is lazy, plus a prune on open

A read that finds an expired entry deletes it and reports a miss, so correctness never depends on a
background sweeper. `prune()` runs once when the cache is first opened and is available to call on a
schedule; it is about disk space, not correctness.

```kotlin
client.cache.prune()   // returns how many went
client.cache.size()    // entries stored, expired ones included
```

---

## What is stored, and what is deliberately not

**Key:** `SHA-256(searchQueryIndex)`, lower-case hex. The identifier — the phone number, the account
number, the wallet address — is never written to disk in any form.

Be precise about what that buys. It is **not** a defence against someone who already suspects a
specific number: SHA-256 of a phone number is trivially brute-forced from a candidate list, and no
salt would change that, because a salt the device stores is a salt the attacker also has. It is a
defence against **bulk extraction**, which is the realistic threat. Identifiers cannot be enumerated
out of the file, only confirmed one guess at a time.

**Value:** AES-256-GCM under the data key, in a versioned envelope:

```
byte  0        envelope version
bytes 1..12    96-bit nonce
bytes 13..     ciphertext, then the 128-bit tag
```

Each record is authenticated under associated data that includes **its own cache key**. That stops a
swap attack: without it, someone with write access to the database file could copy the `NONE` record
of a clean number over the `HIGH` record of a known scammer's number — the ciphertext would still
authenticate, and the cache would confidently answer "no evidence found" for a fraudster. With the
key bound in, the moved row fails to open, degrades to a miss, costs one credit and returns the right
answer.

**The schema has no plaintext columns beyond the key and the expiry.** No risk tier, no `found` flag,
no report count. Someone who pulls the database off a rooted or jailbroken device learns how many
identifiers this user checked and roughly when each answer stops being trusted — nothing about who
was checked, and nothing about the verdicts. Adding a plaintext column because it would be convenient
to query on would give all of that away at once.

### Full detail is never cached

Only the fields invariant across `detail` and `locale` are stored. Dropped on the way in:
`topChronologies`, `comments`, `billing`, `checkId`.

The cache is keyed on the query **alone**, so anything varying with `detail` or `locale` would be
served back under the wrong request — an Indonesian narrative answering an English one. Consequently:

- A `Detail.FULL` request always goes to the server.
- `Source.CACHE` with `Detail.FULL` raises `CacheMiss` rather than returning a result missing the
  narratives that were asked for.

If a future version needs to cache full-detail content, **the key must become the triple of
(query, detail, locale)**. That comment is in the code at the point where it matters.

---

## Reading and writing

`checkScammer` uses the cache on its own; the methods below are for lifecycle work.

```kotlin
client.cache.get(query)     // null on miss/expired/unreadable — all three are the same to a caller
client.cache.put(query, r)
client.cache.remove(query)
client.cache.clear()        // call this on sign-out
client.cache.prune()
client.cache.size()
```

**Call `clear()` on sign-out.** The entries belong to the user who was authorised when they were
fetched, and the next user of the device must not inherit them.

`clear()` deliberately leaves the metadata row, and therefore the nonce counter, in place. Resetting
the counter while keeping the same data key is the one way to reissue a nonce.

A hit always comes back with `origin = Origin.CACHE` and its **original** `fetchedAt`, not the time
it was read — so your UI can say "checked 3 days ago" rather than implying it just happened.

### `Source` and the one that throws

| `Source` | Behaviour |
|---|---|
| `CACHE_FIRST` (default) | Live entry wins; otherwise the server. |
| `SERVER_FIRST` | Server; falls back to a live cache entry if the request fails for any reason. A miss rethrows the original failure rather than inventing a result. |
| `SERVER` | Always the server. Still writes the result to the cache. |
| `CACHE` | Cache only. **Raises `TraciveException.CacheMiss` when there is no live entry.** |

`Source.CACHE` never returns a silent empty result. An empty `CheckResult` reads as "this identifier
is clean", which is the most dangerous wrong answer this SDK can give.

---

## Invalidation on upgrade

`CachePolicy.SCHEMA_VERSION` is the kill switch. Bump it whenever the cached payload's shape **or the
folding grammar** changes, and every stored row is dropped on next open, under a fresh data key.

The grammar half matters more than it looks. Entries written under old folding rules are keyed on
strings the new rules would never produce, so without the bump they linger uselessly until their TTL
expires — up to 30 days of answers nobody can look up.

There are no migrations and there will not be any. These rows are answers that can be re-fetched, so
losing them costs credits and nothing else, whereas migrating them would mean decrypting under an old
key and re-sealing under a new one: real risk in exchange for saving something disposable.

The same discard path handles a lost data key, and for the same reason — the rows cannot be read, so
there is nothing to preserve.

---

## Testing against it

`InMemoryCheckCache` runs the entire production code path — key derivation, the AES-GCM envelope, TTL
policy, schema invalidation — with no database, no platform key store and no file system. Records are
still encrypted, which is not theatre: it means the path under test is the real one rather than a
plaintext-in-a-map lookalike that would never catch an envelope bug.

```kotlin
val clock = TestClock.atStart()
val cache = InMemoryCheckCache(
  dataKeyStore = InMemoryCacheDataKeyStore(ByteArray(32) { it.toByte() }),
  policy = CachePolicy.Default,
  clock = clock,
)

cache.put(query, highRiskResult)
clock.advance(30.days - 1.seconds)
assertNotNull(cache.get(query))
clock.advance(2.seconds)
assertNull(cache.get(query))
```

Every TTL assertion in this SDK's own suite is written that way — an injected clock, never a real
delay. See `CacheTtlTest`, `CacheContentTest` and `CacheLifecycleTest`.

`InMemoryCheckCache` is also a legitimate production choice for a host that will not persist check
history: a cache that dies with the process still saves the repeat lookups within one session, which
is where most of the duplication is, and leaves no encrypted record of what was checked on the device
at all.
