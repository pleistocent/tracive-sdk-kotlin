# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version is `0.x` the public
surface may still change in a minor release; it is frozen against the cross-language contract shared
with the Flutter and Swift SDKs, not against consumers, until `1.0.0`.

## [Unreleased]

### Added

- **The pending authorization is written to the secure store before the browser opens, so a PKCE
  verifier no longer dies with the process that generated it.** Consent happens inside the Tracive
  app, which means the host app spends the whole of it in the background — and on Android that is an
  ordinary candidate for being killed, not an edge case. The `code_verifier` never leaves the device,
  so when it lived only in a field the kill took it with it: the redirect came back, its `state`
  matched nothing, the code could not be exchanged by anyone including this SDK, and the user was
  returned to a dead flow with no explanation and nothing to do but start again. The new public
  `PendingAuthorization` (`state`, `codeVerifier`, `redirectUri`, `createdAt`) is stored through
  `TokenStore`, so it lands in the Android Keystore / iOS Keychain beside the tokens — it carries a
  live verifier and is a secret on exactly the same terms. Its `toString()` redacts both the verifier
  and the `state`. The record is now also what a **new process** completes an authorization from;
  see the `handleCallback` entry under Changed for the API change that made that reachable.
- **A pending authorization now expires after ten minutes**, exposed as
  `TraciveOAuth.DEFAULT_PENDING_AUTHORIZATION_MAX_AGE` and overridable per instance with the new
  `pendingAuthorizationMaxAge` constructor parameter — the same knob the Flutter and React Native
  SDKs expose. Ten is the authorization code's own server-side lifetime, and that is the entire
  argument for the number: past it the code the redirect carries is already dead, so holding the
  verifier longer buys no recovery at all. It only leaves a secret on disk and turns a hopeless
  attempt into a round trip that can come back nothing but `invalid_grant`. A test pins both sides of
  the boundary a second apart, so the window cannot drift to some other value while still looking
  covered.
- **`TraciveQuery.email()` rejects a value containing no `@`**, with the new reason token
  `not_an_email` (`TraciveQueryException.NOT_AN_EMAIL`). It previously rejected only the empty
  string, so `email("budi")` built `EMAIL_budi` — and the server applies no shape validation to an
  email, so that is a perfectly legal record key: it was sent, looked up, missed, and **billed**.
  Rejecting client-side is the only thing that prevents the charge, exactly as `phone()` already
  does with its E.164 test. The check is `@`-presence and deliberately nothing more: a fuller
  address grammar would reject addresses the server would happily have matched, and refusing to
  search for a scammer somebody really did report is the worse of the two failures. `a@b`, `@`,
  `budi@`, `budi@gmail..com` and a quoted local part all still build a query, and a test pins that.
- The install link opened when the Tracive app is missing now carries `utm_source=tracive-sdk-kotlin`
  alongside the existing `ref` attribution tag, matching the link contract shared with the other
  Tracive SDKs. Both parameters are optional and non-sensitive; the link still carries no OAuth
  parameter of any kind, and a test now asserts that for `scope` and `code_verifier` as well.
  `TraciveOAuth.INSTALL_UTM_SOURCE_PARAM` and `INSTALL_UTM_SOURCE` name the two new values.

### Fixed

- **A spent or abandoned authorization is no longer left behind, and a long-dead one is no longer
  exchanged.** Two halves of the same omission. Nothing erased the attempt once it had resolved, so a
  redirect delivered twice — or delivered late by an OS that had queued it — met a verifier still
  sitting next to the `state` it answered to. And nothing looked at the clock: a callback arriving an
  hour after `authorize()` was accepted, its long-expired code sent to the token endpoint, and the
  only possible reply was an `invalid_grant` the user watched arrive for no reason anyone could
  explain. Every terminal outcome now clears the record — a completed exchange, a code handed back to
  a confidential client, a denial, a `state` mismatch, an expiry, `cancelPendingAuthorization()`, a
  failed exchange, a browser that would not open, and `signOut()` — and a callback older than the
  window is refused locally with `AuthResult.Failed(InvalidRequest)` before anything is sent.
- **The document-id guard no longer refuses `__a\nb__`, a key the API accepts.** The reserved-id
  pattern is `^__.*__$`, and a regex `.` does not match a line terminator unless the `s` flag is
  set — which it is not. The guard was written as `startsWith("__") && endsWith("__")`, which has no
  notion of a line terminator, so it was stricter than the server and rejected keys the server would
  have answered. Over-strictness is not the safe direction here: the caller was told their
  identifier was invalid when it was not, and lost a lookup that was available. The guard now
  ignores the enclosing `__…__` shape when a U+000A, U+000D, U+2028 or U+2029 sits between the
  markers; `__proto__`, `__a__` and `____` are still reserved, and a tab or a plain space between
  the markers still is too, because `.` matches those.
- **The OAuth scope names were wrong and no integration using them could ever have authorized.** The
  SDK asked for `check:read` and `profile:read`. The authorization server's scopes are
  dot-separated, and the first one is not called `check` at all: they are `scammer.check`,
  `credits.read` and `profile.read`. A server rejects a consent request naming a scope it does not
  recognise, so this was not a narrower grant or a cosmetic inconsistency — every partner following
  the README would have failed at their first authorization, with nothing locally to debug. The
  literals are corrected everywhere, including the sample and the docs. New public
  `TraciveScopes.SCAMMER_CHECK`, `CREDITS_READ` and `PROFILE_READ` hold the exact wire strings, and
  a test pins them so a rename cannot pass silently. `usage.read` is deliberately absent until the
  SDK models daily usage aggregates.
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
  `null` — "not mine", go on routing it — and leaves the request alone unless the URL is this
  client's own redirect and carries at least one of `code`, `error` or `state`.
- The iOS `UIApplication` calls hop to the main queue themselves instead of asking the caller to.
- The default HTTP client has explicit timeouts (request 30s, connect 10s, socket 30s) rather than
  whatever the engine chose, and response bodies are capped at 512 KiB rather than read unbounded.
- `TraciveAndroid`'s application `Context` and `TraciveOAuth`'s pending request are `@Volatile`; the
  sample's double-checked lock is repaired.

### Changed

- **Breaking: an authorization now ends at `handleCallback`, and `authorize()` returns as soon as
  the consent page is open.** `handleCallback` is `suspend` and returns `AuthResult?` instead of
  `Boolean`; `authorize()` returns `AuthResult?` instead of `AuthResult`, where `null` means "the
  page was opened, the outcome will arrive at `handleCallback`". `cancelPendingAuthorization` is
  `suspend` as well, because abandoning an authorization now has to erase the stored record itself.
  **What an integrator must change:** move the `when` that handled `authorize()`'s result into the
  deep-link handler and switch it to `handleCallback`'s; give that handler a coroutine scope that
  outlives it (`lifecycleScope`, not a composable's), and act on the value — a handler that drops it
  now drops a real grant. `authorize()` keeps only three answers: `null`, `AppNotInstalled`, and
  `Failed` for an operating system that refused to open the page. Prefer `client.oauth!!` over
  `client.oauth?.authorize()`, since a safe call folds "Proxy mode" and "the page opened" into the
  same `null`. On iOS, `handleCallback` and `cancelPendingAuthorization` are now `async` in the
  generated Swift interface and need a `Task`.
  **Why it is worth the break now.** The previous release persisted the PKCE verifier so that
  consent could survive the host process being killed — which is the ordinary case, not an edge
  one, because consent happens inside the Tracive app and the partner app spends the whole of it in
  the background. But the record could not be *used*: `authorize()` was parked on a continuation,
  and a continuation dies with its process, so a redirect arriving in a new process answered `false`
  and the user's consent was thrown away anyway. Half the fix shipped and the visible half did not.
  `handleCallback` can now read the record back from the secure store, exchange the code, and return
  the grant, so a killed app comes back to a finished authorization. The alternative — giving the
  SDK its own `CoroutineScope` so the parked `authorize()` could still be resumed — was rejected
  because it leaves one result with two shapes: an outcome that sometimes returns from `authorize()`
  and sometimes from `handleCallback` is one integrators reliably get wrong. This is also the shape
  the Flutter and React Native SDKs already have, so a partner spanning more than one of them writes
  the same flow in each. Breaking it while the version is `0.x` and the Open API is not live costs
  less than breaking it later.
- **Breaking: `TokenStore` gains `loadPending()` and `savePending(PendingAuthorization?)`.** A custom
  implementation has to add both; there is no default body, deliberately. A no-op default would have
  compiled everywhere and silently switched off the one thing the record exists for, and a store that
  quietly drops a PKCE verifier is precisely the failure that is invisible until a user's phone is
  killed mid-consent. `savePending` follows `save`'s contract and must throw
  `TraciveException.TokenStorageFailed` when it did not store — but unlike `save`, the SDK treats
  that failure as **non-fatal** and carries on with the authorization. Only process-death survival is
  lost, the in-flight call still resolves, and refusing instead would make the SDK unusable for a
  confidential client whose backend holds the tokens and whose store legitimately keeps nothing.
  `clear()` must now drop the pending record as well as the grant: signing out means nothing of the
  user's stays on the device, and a half-finished authorization is something of theirs. The shipped
  Keystore and Keychain stores do all of this already, so an integration that never implemented
  `TokenStore` itself needs no change.
- **Breaking: a blank install-link override is now refused at construction instead of being replaced
  by the default.** `TraciveOAuth(installUrlOverride = …)` and `TraciveClient(installUrl = …)` throw
  `IllegalArgumentException` for an empty or whitespace-only string, with the reason token
  `blank_install_url` in the message — the same token every Tracive SDK uses, and now also
  `TraciveOAuth.REASON_BLANK_INSTALL_URL`. Passing `null`, or leaving the parameter out, is
  unchanged and still yields the default link, because absence is a real answer and a blank is not.
  Silent substitution was the most dangerous of the behaviours available here, precisely because the
  link it produced *worked*: a partner whose configuration pipeline yielded an empty string shipped a
  release that sent their users to Tracive's own install page rather than theirs, and nothing in
  their logs or ours ever said so. A constructor throw runs once, in the host app's code, on every
  run, during development, before any end user is involved. Anyone who was passing a blank on purpose
  has to pass `null` instead. Non-blank values are now also taken **verbatim** rather than trimmed:
  the SDK does no URL validation of any kind here, and a wrong-but-non-blank link reaches the user
  exactly as written, so a value that relied on the old trimming needs trimming at the call site.
- **Breaking (reason token):** an empty crypto address now reports `empty_address` and an empty
  username platform now reports `empty_platform`, instead of both reporting the generic
  `empty_value`. The behaviour is unchanged — both were already refused, because `CRYPTO_ETH_` and
  `USERNAME__scammerx` can only miss and a miss is billed — but the borrowed token made two
  different mistakes indistinguishable, so a caller could not tell which field to point the user at.
  `TraciveQueryException.EMPTY_ADDRESS` and `EMPTY_PLATFORM` hold the new tokens; `EMPTY_VALUE` now
  means one thing only, an email that folded away to nothing. Code branching on `EMPTY_VALUE` for a
  crypto or username query needs updating.
- **Breaking:** `AppPresenceChecker.isTraciveAppInstalled`, `AuthorizeLauncher.launchAuthorize` and
  `AuthorizeLauncher.launchInstall` are now `suspend`, so the iOS implementations can reach the main
  queue. A custom implementation needs `suspend` added.
- **Breaking:** `cancelPendingAuthorization` takes an optional authorization id and returns
  `Boolean`. Passing the id read from the new `pendingAuthorizationId` stops a late "browser closed"
  signal from cancelling a newer request. `pendingAuthorizationId` is memory-only and is therefore
  null in a process that did not open the browser; cancelling with no id still erases whatever is
  stored.
- **Breaking:** `TokenSet.isExpiredAt`, `TokenSet.DEFAULT_EXPIRY_SKEW` and
  `CryptoProvider.sha256Hex` are removed. All three were unused; the SDK refreshes on the 401 rather
  than on a device clock it does not control.
- `TraciveOAuth.close()` is public. Call it on any instance you built without passing a `transport`,
  or it leaks the HTTP client it made for itself.
- `apiCheck` now validates the **Android** public surface as well, against
  `api/tracive-sdk.android.api`. It previously covered only the Kotlin/Native klib ABI, so no change
  to `TraciveAndroid`, `TraciveInitProvider` or the Android platform classes could fail CI.
- `query-grammar-vectors.json` updated to **version 5**: 14 rejection vectors (3 new) and 9
  document-id guard cases (1 new), covering `not_an_email`, `empty_address`, `empty_platform` and
  the `__a\nb__` key the API accepts. Every one of them was settled by running the API's own
  canonical key normalizer, so where the SDK and the fixture disagreed, the SDK was wrong.
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
