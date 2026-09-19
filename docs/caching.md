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

val cache = SqlDelightCheckCache(driver = driver, dataKeyStore = myKeychainDataKeyStore)
```

**The SDK ships no iOS `CacheDataKeyStore`.** There is no `KeychainCacheDataKeyStore` class to
import — you write one, and the sketch under [The key store is yours to
supply](#the-key-store-is-yours-to-supply) is the whole of what it has to do.

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

#### Android

`samples/composeApp/src/androidMain/.../KeystoreCacheDataKeyStore.kt` is the Android reference
implementation, and `src/iosMain/.../KeychainCacheDataKeyStore.kt` the iOS one — meant to
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
hardware.

#### iOS

iOS is the simpler case, and there is no sample module to copy from — the SDK ships no iOS
implementation and no sample iOS app. The Keychain stores arbitrary bytes and hands them back, so
there is no wrapping key and no file: the data key *is* the Keychain item's payload.

Sketch, in your own `iosMain`. `IosKeychainTokenStore.kt` in this repo does the same CoreFoundation
calls for the OAuth grant and is worth reading beside it:

```kotlin
@OptIn(ExperimentalForeignApi::class)
class KeychainCacheDataKeyStore(
  private val service: String = "com.example.myapp.tracive.cache-keys",
) : CacheDataKeyStore {

  override suspend fun createKey(): CacheDataKey {
    val bytes = ByteArray(CacheDataKey.KEY_SIZE)
    val random = bytes.usePinned {
      SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), it.addressOf(0))
    }
    check(random == errSecSuccess) { "SecRandomCopyBytes failed with $random" }

    val id = NSUUID().UUIDString                       // a fresh id per generation
    val added = keychainAdd(service, account = id, payload = bytes)
    check(added == errSecSuccess) { "the cache data key could not be stored: $added" }
    return CacheDataKey(id, bytes)
  }

  // Null when the item is gone. Expected, not exceptional — never throw here.
  override suspend fun loadKey(id: String): CacheDataKey? =
    keychainRead(service, account = id)?.let { CacheDataKey(id, it) }

  // Deleting a key that is already gone is the desired end state, so errSecItemNotFound is fine.
  override suspend fun deleteKey(id: String) {
    keychainDelete(service, account = id)
  }
}
```

Three attributes on the item are load-bearing:

- `kSecClass` = `kSecClassGenericPassword`, with `kSecAttrService` set to your own service string and
  `kSecAttrAccount` set to the key's id. One item per generation, so an old key can be deleted
  without touching the new one.
- `kSecAttrAccessible` = **`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`** — the same class the
  SDK's own token store uses, and *not* `WhenUnlocked`. Under `WhenUnlocked`, `loadKey` returns null
  whenever the device happens to be locked, and the SDK reads a null key as "the key is gone": it
  drops every cached row and starts a new generation. A lock screen would become a cache wipe, and
  each wipe is paid for in re-checks.
- `ThisDeviceOnly`, because it keeps the key out of iCloud Keychain and out of encrypted backups. A
  key restored onto a second device, beside a database that did not travel with it, is key material
  meeting a nonce counter that has already used it.

Check every `OSStatus`. `SecItemAdd` reports failure only through its return value, so a discarded
one turns "the key was never stored" into "the key was stored", and the next `loadKey` returns null
for a database that is otherwise perfectly readable.

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
gets a new key. `InMemoryCacheDataKeyStore` derives its keys from a fixed seed, so a database that
outlives the process would meet a counter at zero beside key material already used under it. That is
a repeated (key, nonce) pair, and it is the one failure AES-GCM does not degrade gracefully from — it
leaks the authentication subkey for every record written under that key.

`SqlDelightCheckCache` rejects the pairing in its constructor rather than trusting you to have read
this paragraph.

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

`topChronologies`, `comments`, `billing` and `checkId` are dropped on the way in. What survives is
invariant across `detail` — but not across `locale`: `tags` and `disclaimer` are localized and are
kept. Safety comes from what is never written, not from the projection being locale-proof. A minimal
check does not send a `locale` at all, so its answer has only one form; a `Detail.FULL` request,
which does send one, never reaches the cache in either direction.

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
client.cache.clear()        // signOut() already does this
client.cache.prune()
client.cache.size()

client.cache.putTransactionCheck(transactionId, r)   // see "Recording what a transaction was shown"
client.cache.getTransactionCheck(transactionId)
client.cache.removeTransactionCheck(transactionId)
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

## Recording what a transaction was shown

`get(query)` answers "what does Tracive say about this account **now**". A transaction-detail screen
asks something else: "what was this user shown **before they sent the money**". Those two answers
diverge, and the cache cannot give you the second one — it holds the latest answer per identifier and
expires it as soon as it stops being trustworthy, so by the next morning a `NONE` result is gone and
the card on yesterday's transfer would be blank.

So record it against your own transaction id:

```kotlin
// 1. On the confirmation screen, before the PIN.
val result = client.checkScammer(TraciveQuery.bankAccount("IDN", "BCA", accountNumber))
showCheckResult(result)          // tier, counts, and result.disclaimer

// 2. Once the transfer exists and has a reference number.
client.cache.putTransactionCheck(transactionId = "TRF26090814320771", result = result)

// 3. Weeks later, on the transaction detail.
val shown = client.cache.getTransactionCheck("TRF26090814320771")
if (shown != null) renderSecurityCheckCard(shown)   // never "no reports found" when it is null
```

It is a separate call rather than a parameter on `checkScammer` because of the order those two things
happen in: the check runs on the confirmation screen, and the reference number is minted after the
PIN. There is no transaction id to pass when the check is made.

**Each record is a snapshot, not a pointer into the cache.** That is the whole design decision, and
it is what makes the card truthful. A user transfers to the same account in September, when it is
clean, and again in October, after it has been reported. A pointer would rewrite September's card to
show October's verdict — an audit trail saying the user was warned before a transfer where they were
shown nothing of the kind. Two records, two answers, neither one editing the other.

**One check can serve several transactions.** The second transfer to an account you already checked
is a cache hit: no request, no credit, and `putTransactionCheck` called a second time with the id of
that transfer. Both details get a card, and the second one's `origin` is `Origin.CACHE`, which is how
your UI knows that transfer spent nothing.

| | `put` / `get` | `putTransactionCheck` / `getTransactionCheck` |
|---|---|---|
| Keyed on | the identifier | your transaction id, hashed |
| Lifetime | `CachePolicy` tier TTL, 1 hour to 30 days | `transactionCheckRetention`, 90 days by default |
| Server `cacheTtlSeconds` | applied, including retraction | **not applied** — a record of the past cannot be retracted |
| Overwritten by a later check | yes | no |
| `origin` on read | forced to `CACHE` | kept as recorded |
| `checkId`, `billing` | dropped | kept — this record *is* that one call |
| `topChronologies`, `comments` | dropped | dropped |

Four things to know before you build a screen on it:

- **`null` means "no check is on file", never "nothing was found".** A transfer where the user chose
  to skip the check has no record, and rendering that as a clean result invents a reassurance nobody
  gave. Show nothing.
- **The id is hashed, and it is matched verbatim** apart from a trim. `TRF-1` and `trf-1` are two
  records; normalise your own reference before it gets here if its case can vary.
- **`clear()` takes the records with it**, so a Tracive sign-out empties the cards on transactions
  that are still in your history. That follows from whose data it is: the verdict was read under a
  grant that no longer exists.
- **Records are counted by `maxEntries`** and, with the defaults, evicted last: eviction drops
  whatever is closest to expiry, and 90 days outlives every tier TTL. That ordering is a consequence
  of the numbers, not a rule — raise a tier TTL above `transactionCheckRetention`, or lower the
  retention below one, and records start going first. If you record more transactions than
  `maxEntries`, raise it: an evicted cache entry costs a credit to replace and an evicted record
  cannot be replaced at all.

And the reason this lives in the SDK rather than in your backend: the partner terms forbid uploading
Tracive results, derived values included — a risk tier and a report count are as much our database as
the rows behind them. Local storage on the user's own device is the permitted place for them, which
is exactly what these rows are. What may go to your server is the user's *decision*: `riskAccepted`,
`riskAcceptedAt`. That is the audit trail you can defend, and it contains no result at all.

---

## Invalidation on upgrade

`CachePolicy.SCHEMA_VERSION` is the kill switch. Bump it whenever the cached payload's shape **or the
folding grammar** changes, and every stored row is dropped on next open, under a fresh data key.

The grammar half matters more than it looks. Entries written under old folding rules are keyed on
strings the new rules would never produce, so without the bump they linger uselessly until they
expire — up to 30 days for a `HIGH` answer, and up to `transactionCheckRetention` for a record.

There are no migrations and there will not be any. Cache entries are answers that can be re-fetched,
so losing them costs credits and nothing else, whereas migrating them would mean decrypting under an
old key and re-sealing under a new one: real risk in exchange for saving something disposable.

The same discard path handles a lost data key — an OS keystore reset, a device restore, a biometric
enrolment change — and for the same reason: the rows cannot be read, so there is nothing to preserve.

**Transaction records go with them, and that half is not disposable.** The discard is one
`deleteAll()` over the table, and by design there is no column that could tell the two kinds of row
apart, so a schema bump or a lost data key empties the check card on every transaction in the user's
history as well. A cache entry that goes costs one credit; a record that goes cannot be recovered at
all. Two consequences worth planning around: bump `SCHEMA_VERSION` only when the payload shape or the
folding grammar genuinely changed, and do not build a flow that *depends* on a record still being
there — treat the card as something a transaction detail shows when it can, not as the audit trail.
The audit trail is the `riskAccepted` / `riskAcceptedAt` pair on your own server.

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
