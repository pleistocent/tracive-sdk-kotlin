# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version is `0.x` the public
surface may still change in a minor release; it is frozen against the cross-language contract shared
with the Flutter and Swift SDKs, not against consumers, until `1.0.0`.

## [Unreleased]

### Fixed

- **A Keychain or Keystore write that fails is no longer reported as a success.** The iOS token
  store discarded every `OSStatus` it received, so a save that did not happen looked exactly like
  one that did. After a refresh rotation that leaves the device holding the *retired* refresh token,
  which the server's reuse detection reads as theft and answers by revoking the whole grant. Both
  platform stores now raise the new `TraciveException.TokenStorageFailed`, carrying the platform's
  numeric status and nothing else.
- **`InMemoryCacheDataKeyStore.createKey()` returns fresh key material.** It used to hand back the
  same bytes under a new id, forever. The cache's nonce counter restarts at zero for every new key,
  so reissuing one produced a repeated (key, nonce) pair — the single failure AES-GCM does not
  degrade gracefully from. `SqlDelightCheckCache` now refuses this key store outright.
- **A stray deep link can no longer abort an authorization in flight.** `handleCallback` treated any
  URL as a callback once a request was pending, including one with no query string at all, so any
  app on the device could cancel a user's authorization by firing an empty link. It now answers
  `false` and leaves the request alone unless the URL is this client's own redirect and carries at
  least one of `code`, `error` or `state`.
- The iOS `UIApplication` calls hop to the main queue themselves instead of asking the caller to.
- The default HTTP client has explicit timeouts (request 30s, connect 10s, socket 30s) rather than
  whatever the engine chose, and response bodies are capped at 512 KiB rather than read unbounded.
- `TraciveAndroid`'s application `Context` and `TraciveOAuth`'s pending request are `@Volatile`; the
  sample's double-checked lock is repaired.

### Changed

- **Breaking:** `AppPresenceChecker.isTraciveAppInstalled`, `AuthorizeLauncher.launchAuthorize` and
  `AuthorizeLauncher.launchInstall` are now `suspend`, so the iOS implementations can reach the main
  queue. A custom implementation needs `suspend` added.
- **Breaking:** `cancelPendingAuthorization` takes an optional authorization id and returns
  `Boolean`. Passing the id read from the new `pendingAuthorizationId` stops a late "browser closed"
  signal from cancelling a newer request. The no-argument call still compiles and behaves as before.
- **Breaking:** `TokenSet.isExpiredAt`, `TokenSet.DEFAULT_EXPIRY_SKEW` and
  `CryptoProvider.sha256Hex` are removed. All three were unused; the SDK refreshes on the 401 rather
  than on a device clock it does not control.
- `TraciveOAuth.close()` is public. Call it on any instance you built without passing a `transport`,
  or it leaks the HTTP client it made for itself.
- `apiCheck` now validates the **Android** public surface as well, against
  `api/tracive-sdk.android.api`. It previously covered only the Kotlin/Native klib ABI, so no change
  to `TraciveAndroid`, `TraciveInitProvider` or the Android platform classes could fail CI.
- `query-grammar-vectors.json` updated to **version 3**: `crossLanguageSemantics.nfcScope` no longer
  contradicts the `rules` section about NFC on the bank code, and a 34th vector pins the difference.
- One SHA-256 implementation instead of two, and one lower-case hex encoder instead of two.
- `docs/caching.md` no longer tells iOS integrators to construct a class that does not exist; it
  shows the Keychain key store inline and says plainly that the SDK ships none.
- `query-grammar-vectors.json` updated to **version 2**: 33 vectors (8 new) plus two new sections,
  `crossLanguageSemantics` and `documentIdGuard`. Every expected value in it was produced by running
  the real backend normalizers, so where the SDK and the fixture disagreed, the SDK was wrong.
- **Query folding now reimplements JavaScript's string semantics instead of using Kotlin's.** The
  backend folds in JavaScript, and the two platforms disagree in ways that are invisible until a
  search silently stops finding a scammer — and a miss is billed:
  - Trimming and whitespace-stripping use a hand-written character class equal to JavaScript's
    `String.prototype.trim()` set. Kotlin's `trim()` leaves U+FEFF (JavaScript strips it) and strips
    U+001C–U+001F (JavaScript keeps them); `Regex("\\s")` is ASCII-only and would leave the U+00A0
    that arrives in every paste from a bank statement. U+200B is deliberately still not stripped,
    because the server does not strip it either.
  - The EVM crypto fold continues to require a lower-case `0x` prefix; `0X…` is left unfolded, which
    matches the backend's regex on both its write and its read path.
- `QueryGrammarVectorsJson.kt`, the embedded copy of the fixture, is now generated by
  `tools/generate_vectors_kt.py` rather than by hand, and CI fails if the two ever differ.

### Added

- The document-id guard now rejects a key carrying an unpaired UTF-16 surrogate. Such a key has no
  UTF-8 encoding, so every layer between the SDK and storage substitutes U+FFFD for it: the key
  that arrives is not the key that was built, and the request is a guaranteed billed miss. It also
  measures UTF-8 length by counting rather than by encoding a throwaway copy of the key.
- Tests driving `crossLanguageSemantics` and `documentIdGuard` directly, including the `safe: true`
  half of the guard cases — `__x`, `x__` and `_` are legal record keys, and a guard that rejected
  them would refuse queries the backend would have answered.

## [0.1.0] — 2026-07-31

First release. phase 1: **OAuth mode only**, and **the backend Open API is not live yet** — every
call that needs the network will fail until it ships. What works today is everything that does not:
query folding, the encrypted cache, error mapping, and the OAuth flow up to its handoff to a live
authorization endpoint. The whole test suite runs with no network.

### Added

**Client**

- `TraciveClient` with `checkScammer`, `credits`, `profile`, `signOut` and `close`.
- `TraciveMode.OAuth` and `TraciveMode.Proxy`. Proxy is phase 2 and throws
  `TraciveException.NotImplemented`; the case exists now so that adding it later is not a breaking
  change to a sealed hierarchy.
- `TraciveEnvironment.Production`, `.Sandbox`, `.Custom(baseUrl)`.
- `Source` (`CACHE_FIRST`, `SERVER`, `SERVER_FIRST`, `CACHE`) and `Detail` (`MINIMAL`, `FULL`).
- `HttpTransport` as an injectable seam, with a Ktor implementation (OkHttp on Android, Darwin on
  iOS). Injecting it is how the SDK is tested without a server, which matters more than usual here
  because there is no server to test against.
- `RetryPolicy`. A check is retried **only** when the caller passed an `idempotencyKey`: a 5xx or a
  timeout does not say whether the server processed the check, so a blind retry is a request to be
  charged twice. A 429 is never auto-retried.

**Queries**

- `TraciveQuery.phone`, `.email`, `.bankAccount`, `.iban`, `.crypto`, `.username`, each exposing the
  folded `searchQueryIndex`.
- Folding pinned to `query-grammar-vectors.json`, vendored verbatim into all three SDK repos, with a
  test asserting every vector and every rejection.
- Validation before any network call — a key that cannot hit would still be billed.
- The document-id guard: rejects `/`, `__…__`, `.`, `..`, and anything over 1500 UTF-8 bytes.
- **No `domain()` builder.** The Tracive API never shipped a DOMAIN type, and
  building such a key would guarantee a billed miss.

**Cache**

- `CheckCache` interface, `SqlDelightCheckCache` (persistent), `InMemoryCheckCache`, and
  `DisabledCheckCache`.
- Keys are `SHA-256(searchQueryIndex)`; the raw identifier is never written to disk.
- AES-256-GCM per record, with a version byte, a 96-bit nonce and a 128-bit tag. Each record is
  authenticated under associated data that includes its own cache key, so a row copied over another
  row fails to open instead of being served.
- SHA-256 and AES-GCM implemented in common Kotlin rather than per platform, so a record written on
  Android opens byte-for-byte on iOS. Both are pinned against the FIPS 180-4 and NIST SP 800-38D
  test vectors.
- `CacheDataKeyStore` for platform key material (Android Keystore / iOS Keychain). The SDK ships the
  interface; the reference implementation is in the sample.
- TTL by risk tier — `HIGH` 30 d, `ELEVATED` 7 d, `CAUTION` 24 h, `NONE` 1 h — with the response's
  `cacheTtlSeconds` overriding the default. A zero TTL stores nothing *and* removes any existing
  entry, which is how the backend retracts an answer already on a device.
- Schema-version invalidation, lazy expiry on read, and a prune pass on open.
- Full-detail content (`topChronologies`, `comments`, `billing`, `checkId`) is never cached, because
  the cache is keyed on the query alone.

**OAuth**

- PKCE, **S256 only**; `plain` is not supported and will not be added.
- Install pre-check before any redirect, via Android package visibility (declared in the SDK's own
  AAR manifest, so it merges into the host app automatically) and iOS `canOpenURL`. A missing app
  opens a clean install link carrying no OAuth parameters and returns `AuthResult.AppNotInstalled`.
- Authorize page opened in Custom Tabs / a Universal Link. Never a WebView — an App Link does not
  fire from one, and RFC 8252 §8.12 forbids it.
- `state` verified before the code is redeemed.
- `ClientType.PUBLIC` (the SDK exchanges the code) and `.CONFIDENTIAL` (the SDK stops at the code and
  returns it, plus `adoptTokens` for handing the result back).
- Tokens in the Android Keystore / iOS Keychain. Auto-refresh on 401 `expired_token`,
  **single-flight** — concurrent refreshes would trip the server's refresh-token reuse detection and
  revoke the whole grant. `grant_revoked` clears tokens and is never retried.
- `signOut()` revokes the refresh token best-effort, then clears local tokens regardless of outcome.

**Errors**

- `TraciveException` hierarchy mapped from `error.code`, covering 400/401/402/403/409/429/5xx and
  transport failure. An unrecognised code maps to `Server`/`Unknown` carrying the raw code rather
  than crashing.
- `TraciveQueryException(reason)` using the fixture's own reason strings.
- `RateLimited.retryAfter` read from the `Retry-After` header.
- Unknown JSON fields are ignored rather than fatal, so a build shipped today survives phase 2
  adding fields.

**Repo**

- Kotlin Multiplatform: Android (`minSdk` 24) and `iosX64` / `iosArm64` / `iosSimulatorArm64`.
- ktfmt Google style, 2-space indent, enforced by `ktfmtCheck` in `check`.
- Binary-compatibility-validator with KLib validation on, so a change to the *common* surface — the
  one the Flutter and Swift bridges compile against — cannot land unreviewed.
- GitHub Actions running build and unit tests on a macOS runner.
- `samples/android-compose`: a runnable Compose app with an Android Keystore-backed cache key store.
- Apache-2.0.

### Security

The invariants this release establishes, listed in full in [SECURITY.md](SECURITY.md): no API-key
parameter anywhere in the public surface in any mode; no raw identifier or token in any log,
exception message or `toString()`; no identifier on disk; tokens only in platform key stores; S256
only; no WebView; single-flight refresh.

[Unreleased]: https://github.com/pleistocent/tracive-sdk-kotlin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/pleistocent/tracive-sdk-kotlin/releases/tag/v0.1.0
