# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version is `0.x` the public
surface may still change in a minor release; it is frozen against the cross-language contract shared
with the Flutter and Swift SDKs, not against consumers, until `1.0.0`.

## [Unreleased]

### Added

- **A bank account can be named by its national bank code or its BIC.**
  `TraciveQuery.bankAccountByShortCode(countryAlpha3, bankShortCode, account)` and
  `TraciveQuery.bankAccountByBic(countryAlpha3, bic, account)` take the code a partner already
  stores — `014` or `CENAIDJA` for BCA — and build exactly the query `bankAccount("IDN", "BCA", …)`
  builds, so the cache key and the record key are the ones the server uses. The translation table
  is the Tracive API's own `bank-codes.json`, shipped inside the SDK so it works offline and parsed
  only on the first lookup. A code the country does not list, or a malformed BIC, throws the new
  `TraciveQueryException.UNKNOWN_BANK_CODE` (`unknown_bank_code`) before anything is sent. The
  shared grammar fixture moves to version 6 with the vectors for both.

- **Three newer server error codes now have a type.** `user_credit_not_permitted` and
  `translation_not_permitted` (both 403 on a check) map to the new
  `TraciveException.UserCreditNotPermitted` and `TraciveException.TranslationNotPermitted` instead
  of `Unknown`. `daily_quota_exceeded` stays `RateLimited`, so an existing handler keeps working;
  its `errorCode` is now the real code rather than `rate_limited`, and the new
  `RateLimited.isDailyQuotaExceeded` tells it apart from a short burst limit. Any other
  unrecognised 429 code is likewise kept on `errorCode`. The change is binary-compatible; an
  exhaustive `when` over `TraciveException` with no `else` gains two branches.

- **A check can now be recorded against your own transaction, so a transaction-detail screen can show
  it again later.** `client.cache` gains `putTransactionCheck(transactionId, result)`,
  `getTransactionCheck(transactionId)` and `removeTransactionCheck(transactionId)`.

  ```kotlin
  client.cache.putTransactionCheck("TRF26090814320771", result)   // once the transfer has a reference
  client.cache.getTransactionCheck("TRF26090814320771")           // null = no check on file
  ```

  There was no way to do this before, and the near-miss was worse than the gap: `checkScammer(query,
  source = Source.CACHE)` looks like it answers the question and does not. It returns the **latest**
  answer for that account rather than the one the user was shown, and it returns nothing at all once
  the tier TTL has run out — an hour, for a `NONE`. So the card on yesterday's transfer was either
  blank or, after the account was reported, quietly rewritten to claim the user had been warned.

  Each record is therefore a **snapshot**, not a pointer into the cache: a later check of the same
  account cannot edit an earlier transaction's card. One check can be recorded against several
  transactions — the second transfer to an account you already checked is a cache hit, so it costs no
  credit, and its record carries `origin = Origin.CACHE` to say so. Retention is
  `CachePolicy.transactionCheckRetention`, 90 days by default, and deliberately not a tier TTL: a
  clean answer stops being trustworthy after an hour but never stops being what someone was
  shown.

  It is a separate call rather than a parameter on `checkScammer` because of when the two things
  happen: the check runs on the confirmation screen and the reference number is minted after the PIN,
  so there is no transaction id to pass at check time.

  Storage-wise this is the same encrypted table, the same data key and the same `clear()` on
  sign-out. The transaction id is hashed like an identifier is — written in cleartext beside an
  encrypted verdict it would say which transfers a device holds a fraud answer for. `checkId` and
  `billing` are kept, unlike in a cache entry, because the record *is* that one call and those are
  what a dispute is reconciled against; report narratives are dropped, because nothing on a
  transaction detail shows them and a 90-day archive of other people's accounts of being defrauded is
  not something to keep on a phone.

  This is also the supported way to build that screen at all. The partner terms forbid uploading
  Tracive results to your own servers, derived values included; local storage on the user's device is
  where they may live. What may go to your server is the user's decision — `riskAccepted`,
  `riskAcceptedAt` — which is the audit trail you can defend and contains no result.

- **`access_denied` is not the only refusal, and now the OAuth guide says so.** The authorization
  server can end a consent with other codes, and the one it actually sends is
  `temporarily_unavailable` — for two situations that look identical to your app and are not the
  same to the user: the partner organisation has not accepted the current terms, or the device has
  reached its daily limit on new connections to your app. Neither is a refusal by *this* user, and
  both resolve on their own if the user retries later.

  This SDK has always passed the raw code through — `AuthResult.Failed` carrying
  `TraciveException.Unknown`, with the code on `errorCode` — but nothing told a partner to read it,
  so the natural handling was a generic failure or, worse, "you declined", which is the one thing it
  is not. The guide now shows how to branch, and says to branch on the **code** rather than the
  exception type: the type differs between the Tracive SDKs, the code string does not.
  `error_description` carries the server's own wording, including any reset time, so show that.

- **`normalizePhoneNumber`, so a locally-formatted number can reach `phone()`.** `phone()` takes
  E.164 and refuses `081234567891`, for a reason it states: the SDK cannot know your dial code, and a
  wrong guess still costs a credit. This supplies the missing dial code, so the strictness downstream
  stays intact rather than being loosened.

  ```
  phone(normalizePhoneNumber("IDN", "0857-1860-9079"))   // +6285718609079
  phone(normalizePhoneNumber("+62", "0857 1860 9079"))   // the same key
  ```

  The first argument is an ISO 3166-1 **alpha-3** code or a dial code with or without `+`, told apart
  by shape: letters mean alpha-3, digits mean a dial code. Alpha-2 (`ID`) is refused, because alpha-3
  is what `bankAccount()` already speaks and one code meaning two things is how a cache key stops
  matching. A country table of 245 entries comes with it.

  It is public and standalone rather than folded into `phone()`, because you need the E.164 form for
  your own records, logging and outbound messaging too.

  The rules copy the Tracive app's normalizer deliberately — Italy's leading zero is part of the
  number, Russia's trunk prefix is `8`, North America's is `1`, everywhere else it is `0` — because
  if the app folds a number one way and this SDK another, one person becomes two record keys and a
  report filed in the app is invisible to a check made here. `phone-normalization-vectors.json` pins
  that agreement, carries the country table so the enum cannot drift either, and is vendored
  byte-identically into all four SDK repositories with a hash pin in each CI.

  **Two rules deliberately do not match the app**, and they differ in kind. A leading `+` whose dial
  code contradicts the country named raises `country_conflict` rather than being concatenated —
  `("IDN", "+64857…")` would otherwise yield `+626485718609079`, a New Zealand number wearing an
  Indonesian dial code; every input this rejects produced an unusable key in the app too. And
  `+00<dial>` is refused, which the app accepts and folds correctly: `+` and `00` are alternative
  spellings of the same thing rather than stackable ones, what follows `+` must be a country code,
  and `00` is none.

### Changed

- **Breaking: `CheckResult.firstReportOn` and `lastReportOn` are now `String?` holding `"YYYY-MM"`
  (e.g. `"2026-06"`), no longer `Instant?`.** The Tracive API now sends them as a plain month, the
  same shape as `Chronology.incidentDate` and `Comment.date`: exact report dates never leave the
  server, because they could help identify the reporter. Show month and year only (e.g. "Jun 2026")
  and compute nothing from them, such as "3 days ago". A full date-time from an older server, or in
  a cache entry written by an earlier build, is read as its month; a value with no valid month reads
  as `null`. The sample formats the month without a day.

- **The sample is now one Compose Multiplatform app covering Android and iOS.** It was Android-only
  and written against Jetpack Compose. `samples/composeApp` now holds the screen, the view model and
  the whole OAuth configuration as common code; only the two things that are genuinely per-platform
  are written twice — key storage (`KeystoreCacheDataKeyStore` wraps the data key because an Android
  Keystore key will not hand back its bytes, `KeychainCacheDataKeyStore` simply stores it) and the
  SQLite driver. `samples/androidApp` is a launcher Activity, `samples/iosApp` an Xcode project.
  The iOS sample runs on devices and Apple-silicon simulators only, because Compose Multiplatform
  stopped publishing `iosX64` in 1.11.0; the SDK itself still ships `iosX64`.

  Three modules rather than one because from AGP 9 `com.android.application` registers its own
  `kotlin` extension, so `org.jetbrains.kotlin.multiplatform` cannot be applied beside it.

  Two traps are recorded in the build files rather than left to be rediscovered. `samples/androidApp`
  applies the Compose compiler although it declares no `@Composable` of its own, because
  `setContent { }` takes a composable lambda and without the plugin it builds and then dies with
  `NoSuchMethodError: setContent$default`. And kotlinx-datetime is forced back to the SDK's 0.6.2:
  Compose Multiplatform's material3 wants 0.7.1, where `kotlinx.datetime.Instant` became a typealias,
  and klib partial linkage answers the vanished symbol with a throw at run time rather than an error
  at build time — the app linked cleanly and died on `CheckResult.fetchedAt`. Forcing 0.6.2 moves the
  unresolved symbols into material3's DatePicker calendar, which this sample does not use.

### Breaking

- **Sandbox now looks for the Tracive sandbox app: `com.pleistocent.tracive.sandbox`, URL scheme
  `tracivesandbox`.** It looked for `com.pleistocent.tracive.dev` and `tracivedev`, which is now an
  internal build that claims no authorize host, so it can never answer a sandbox consent.
  `AppPresenceChecker.ANDROID_SANDBOX_PACKAGE` and `IOS_SANDBOX_URL_SCHEME` change value. On iOS,
  replace `tracivedev` with `tracivesandbox` in `LSApplicationQueriesSchemes`. On Android there is
  nothing to change: the AAR's `<queries>` names the authorize App Link, not a package. Production,
  `Custom` and the sandbox API and authorize hosts are unchanged.

- **The server TTL override is gone: `CachePolicy.honorServerTtlOverride`, `CachePolicy.effectiveTtl`
  and `CheckResult.cacheTtlSeconds` are all removed.** The backend stopped sending `cacheTtlSeconds`
   because its per-tier ladder was a copy of the one every SDK already has locally; cache lifetime
  is the SDK's own business. So the flag gated a branch that could never be taken.
  `policy.ttlFor(riskTier)` is now the whole of the lifetime policy — use it where you used
  `effectiveTtl`, and pass your own `CachePolicy` if you want different numbers.

  `CachePolicy` and `CheckResult` are data classes, so this also changes `copy()`, `componentN()`
  and the constructor arity: a caller that passed either field by name, or destructured either
  class, has to be updated. A `cacheTtlSeconds` still arriving from a proxy is ignored rather than
  fatal — every `Json` in the SDK sets `ignoreUnknownKeys`.

  One behaviour goes with it: a zero `cacheTtlSeconds` used to evict a row already on the device.
  Setting that tier's TTL to zero in your own `CachePolicy` is now the only way to reach that path.

- **`CheckCache` has three more members.** Anyone who implements the interface themselves — a test
  double, or a host that swapped the storage — has to add `putTransactionCheck`,
  `getTransactionCheck` and `removeTransactionCheck`. There are no default implementations on
  purpose: a default that silently kept nothing would leave a partner with a transaction-detail card
  that is always empty and nothing to say why. `SqlDelightCheckCache`, `InMemoryCheckCache` and
  `DisabledCheckCache` are unaffected, and so is every caller that only uses `client.cache`.

- **`defaultAppPresenceChecker()` takes a `TraciveEnvironment`.** It is the platform seam behind the
  probe above, and it cannot pick the right build without knowing the environment. Callers that pass
  their own `appPresenceChecker` to `TraciveClient` or `TraciveOAuth` are unaffected; only code
  calling this function directly needs the argument.

- **`partnerUserId` is now required, and a blank one is refused.** `TraciveMode.OAuth.partnerUserId`
  is a non-null `String`. It was optional in all four SDKs, and the documentation here told you to
  omit it when your integration has no user accounts. Following that reached a consent screen that
  refuses **client-side, before any network call, and returns no redirect at all** — so your app is
  handed nothing to handle, the user sees a generic "invalid request", and no server-side log
  records that it happened. The field is what the grant is keyed on: stored as you sent it, echoed
  back in `grant.revoked` and `grant.superseded` so you can map the event to your own customer, and
  what enforces one of your accounts to one Tracive account. Send an opaque identifier — never a raw
  phone number or email.

### Fixed

- **A national number that starts with its own dial code's digits keeps them.** Every Kazakh
  number starts with 7, so `normalizePhoneNumber("KAZ", "701 234 5678")` gave `+7012345678` while
  `"+7 701 234 5678"` gave `+77012345678`. The same hit closed numbering plans where the bare number
  is how people write it: Norway (`476 12 345`), Costa Rica (`5062 4585`), Singapore and 90-odd
  others. Without a `+`, digits that start with the dial code now keep it when their length is a
  national-number length and their length without the dial code is not. The lengths, per dial
  code, come from libphonenumber 9.0.42 and ship in the fixture as `nationalNumberLengths`. A number
  typed with `+`, or where both readings are possible (Indonesia's `62812…`, Italy's `393…`), is
  stripped as before, so no number typed with its country code changes key. The phone fixture moves
  to v9 with 6 new vectors and the table; a new test sweeps every dial code and length against it.

- **`normalizePhoneNumber` now folds a number typed the local way to the same key as its
  `+<code>` form in eight more countries.** Hungary's trunk prefix is `06`, so `06 30 123 4567` gave
  `+366301234567` while `+36 30 123 4567` gave `+36301234567`: one person, two record keys. The
  trunk `8` of Belarus (`8 029 …`), Turkmenistan, Tajikistan and Uzbekistan, and the `8` Lithuania
  used until March 2024, were kept the same way. The 8 is dropped only when exactly a national
  number's length follows it, because some national numbers start with 8 themselves (Uzbek `88`
  mobiles, Lithuanian `800` freephone). **The output changes for Côte d'Ivoire, Benin and the
  Republic of the Congo:** the leading 0 is part of the number there and is now kept
  (`+2250712345678`, not `+225712345678`), which is the E.164 form their regulators publish and the
  one a contact card or another E.164 source holds. Every rule was checked against the numbering
  plan the country filed with the ITU. The phone fixture moves to v8 with 18 new vectors and one new
  rejection.

- **The Compose sample drew itself under the status bar.** `samples/android-compose` targets the
  compile SDK, which is past 35, so Android lays the window out edge to edge and ignores the theme's
  `statusBarColor` — and from 36 the opt-out flag is gone, so this is not something a partner can
  defer. The sample handled no insets at all, so its header sat behind the clock and the theme's
  white status-bar icons landed on a white `Surface`, invisible. It now declares
  `enableEdgeToEdge` with a light bar style, because the `MaterialTheme` here is the light scheme in
  every configuration and the default `auto` would follow the system into dark mode and put the
  white icons back, and pads the scrolling column by `safeDrawing` — system bars, display cutout and
  the IME, the last of which an edge-to-edge window no longer resizes for. Verified on an API 36
  emulator, before and after.

  Only the sample was affected. The SDK ships no UI: its AAR carries a `<queries>` block and a
  startup provider, no activity, theme or layout, so it cannot impose this on a host app and cannot
  fix it for one either. The Swift SDK's example is a SwiftUI view that opts out of nothing, so it
  is already correct.

- **The authorize URL pointed at a host no Tracive build claims.** Kotlin was already correct here;
  the fix landed in the Flutter and React Native SDKs. The Tracive app claims
  `https://tracive.id/oauth/authorize`, and `sandbox.tracive.id` for its sandbox build, as an App
  Link and a Universal Link — nothing else opens the app, so consent could not start at all. Both
  wrong spellings were pinned by their own repository's tests, so no single-repo check could have
  caught it.

- **The app-presence probe reported "not installed" on every Android device, including the ones
  holding the app.** It probed a custom-scheme URL carrying a host the app does not register, and
  Android resolves such a probe by intent-filter matching: a filter that declares a host requires
  the URI to carry a matching one. It now probes the bare scheme, which the Tracive app claims with
  a scheme-only intent-filter added for the purpose. Verified on a device — `tracivedev://` went
  from "unable to resolve Intent" to starting the app.

- **The presence probe now follows the environment, with nothing for you to declare.** It was
  hardcoded to production, so testing against sandbox on a device carrying only the sandbox build
  reported the app as absent and sent every partner engineer to the store. `environment` now picks
  the build on both platforms.

  This SDK used to be the exception, because its Android half checks package visibility and the
  sandbox application id was banned from a published repository. That ban rested on the id being
  undisclosed, which it never was: each authorize host publishes its own application id and signing
  fingerprints at `/.well-known/assetlinks.json`, because that is what an App Link association is.

  What replaced it is narrower and load-bearing — nothing may add a Tracive application id to *your*
  shipped manifest. So the AAR's `<queries>` block no longer carries a `<package>` entry at all.
  Visibility comes from an `<intent>` matching each environment's authorize App Link, which reaches
  whichever build claims that host. Verified end to end on an emulator carrying only the sandbox
  build: with no declaration in the sample's own manifest, the probe went from opening Play Store to
  opening the Tracive app's consent screen.

  **On iOS, add `tracivesandbox` to `LSApplicationQueriesSchemes`** if you run against sandbox. An
  `Info.plist` is not merged, so no library can ship that one for you.

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
- **`CachePolicy.maxEntries`, defaulting to 5000, bounds the cache.** There was previously no way to
  cap it at all, deliberately or otherwise: nothing but a TTL ever removed a row, and a `HIGH`
  verdict lives thirty days, so an app that checks every number in an incoming call log grew an
  encrypted database on the user's device that no one asked for and no one was watching. The cap is
  enforced by the prune pass — when the cache is opened, and on every `CheckCache.prune()` — rather
  than on every write, which would put a second query in the path of every check for a bound that is
  about disk space rather than correctness. Eviction drops the rows **closest to expiry first**: they
  have the least value left, so a `NONE` answer (one hour) goes long before a `HIGH` one (thirty
  days), which is also the right way round for the bill. Zero or less means no cap, for a host that
  would rather manage the file itself. The number and the eviction rule match the Flutter SDK.

- The install link opened when the Tracive app is missing now carries `utm_source=tracive-sdk-kotlin`
  alongside the existing `ref` attribution tag, matching the link contract shared with the other
  Tracive SDKs. Both parameters are optional and non-sensitive; the link still carries no OAuth
  parameter of any kind, and a test now asserts that for `scope` and `code_verifier` as well.
  `TraciveOAuth.INSTALL_UTM_SOURCE_PARAM` and `INSTALL_UTM_SOURCE` name the two new values.

### Fixed

- **A refresh reply that omitted `refresh_token` was read as the grant no longer having one.** RFC
  6749 §6 lets the token endpoint leave that field out of a refresh reply, and leaving it out means
  "keep the one you have" — so a fully conforming server could answer with an access token and
  nothing else, and the SDK stored a null refresh token beside it. Nothing failed at the time; the
  next expiry did. There was no token left to present, the call surfaced `InvalidToken`, and the
  user was sent back through the consent screen for a grant that had never ended. The previous
  refresh token is now carried forward whenever the reply omits one, which is what the Flutter and
  Swift SDKs already did. Only the refresh path has a previous token to carry: the code exchange is
  the grant's first round trip, so a reply with no refresh token there really does mean the grant
  has none, and that case is unchanged. A carried-forward token is also taken back out of the
  spent-token history, because a reply that issues no refresh token has not retired the one it was
  given — leaving it marked would have made the very next refresh refuse the only token the grant
  had.

- **Concurrent identical checks were sent twice and billed twice.** Two overlapping calls for the
  same identifier both read the cache, both missed, both POSTed: the cache write is the only thing
  that serialises them, and it happens after both requests have already gone out. A double-tapped
  button, or a list screen rendering the same identifier in two cells, doubled the bill. Overlapping
  checks now share one request, latched on the record key together with the detail level, the locale
  and the caller's own `idempotencyKey` when they supplied one. A minimal answer must never be
  handed to a caller who asked for full detail, and `tags` and `disclaimer` come back in the
  language that was asked for. A key is the caller's explicit statement about a billable
  transaction, so it belongs in the latch on the same terms: two overlapping checks carrying
  different keys are two transactions and are sent separately, and a call that supplies a key never
  joins a flight started by one that did not — which would silently cost it the retry budget that
  only its own key grants. A caller who supplies no key is unaffected, which is what keeps the
  everyday cases above sharing one flight. The shared request belongs to no
  single caller: it runs under a job of its own, so one caller giving up cannot cancel a check
  another is still waiting for, and it is cancelled when the last caller waiting for it leaves, so
  sharing never removes a caller's ability to stop a check. Sequential checks are unaffected — the
  latch collapses overlap and nothing else. The same latch has always guarded the token refresh;
  this puts it in the one place where the unit is currency.

- **`SERVER_FIRST` fell back to the cache on every failure, not only the ones worth falling back
  from.** A 403 `missing_scope`, a 402 or a revoked grant degraded to a cached verdict exactly like
  a 503 would. A partner whose access had been withdrawn kept being served fraud verdicts, and the
  withdrawal was invisible for as long as the cache held. The fallback is now gated on
  transport-shaped failures — network error, 429, 5xx — and everything else is rethrown.

- **A non-production application id shipped inside the published AAR.** `androidMain`'s manifest
  declared a second, non-production application id alongside the real one, and that manifest merges
  into every
  integrator's app. A host that genuinely needs to probe another build passes the id to
  `AndroidAppPresenceChecker` and declares it in its own manifest instead.

- **The OAuth `state` was compared with `!=`.** Now constant-time, matching the other three SDKs.
  Exploiting a timing side channel on a 32-byte random value from an on-device caller is not a
  realistic threat; the point is not to diverge by accident on a comparison whose whole job is to be
  a security check.

- **A token round trip already in flight could rewrite the grant after `signOut()`.** A refresh or a
  code exchange that started before the sign-out and finished after it wrote its tokens back, and
  the user was signed in again without asking. Guarded by a credential generation, the same shape
  Swift and Flutter use.

- **A negative `cacheTtlSeconds` kept the old answer alive instead of retracting it.** The guard
  read `>= 0`, so a negative value fell through to the tier default and a `HIGH` verdict the server
  had withdrawn went on being served for up to thirty days. Non-positive now means delete, which is
  what a zero already meant.

- **A check could be replayed after a token refresh with no `Idempotency-Key`.** The SDK re-sends
  the same POST once the refresh lands, and without a key the server has nothing to tell that replay
  from a second check. One is now generated per call when the caller supplies none. A caller-supplied
  key still means more: it is the only thing that grants a retry budget on a 5xx or a timeout.

- **Only an exact `expired_token` body triggered a refresh.** A bare 401 — no body, or a
  `WWW-Authenticate` header and nothing else — is what CDNs, API gateways and misconfigured reverse
  proxies emit, and this SDK is designed to sit behind a partner gateway. Requiring the exact code
  left the client permanently stuck there: never refreshing, every call failing identically. Any 401
  that is not `grant_revoked` or `invalid_token` now refreshes once and replays.

- **A refresh token could be presented twice after a refresh that got no answer.** A socket timeout
  is indistinguishable from a success whose reply was lost, so the server may already have rotated
  the token and be waiting to see the new one. Sending it again is what reuse detection is looking
  for, and it costs the whole grant rather than one call. The SDK now remembers which tokens it has
  put on the wire and refuses to send one twice; a complete HTTP reply of any status releases the
  token again, since an error reply issues no tokens (RFC 6749 §5.2).

  **Visible change:** a refresh whose transport fails now raises `TraciveException.InvalidToken`
  rather than `Network`, and the grant is unrefreshable for the rest of the process. That is the
  trade: one consent the user may not have strictly needed, against a revocation event on top of a
  grant that already needed re-authorizing.

- **A stored grant whose access token is blank is now repaired from its refresh token instead of
  being thrown away.** A partial write, a keystore save interrupted by the process being killed, or
  a restored backup can leave that field empty next to a refresh token that still works. Two things
  then happened, both silently. The stored record was discarded wholesale on the way in — a blank
  access token was read as an unusable blob, so the live refresh token went with it, and the user
  was sent back through consent to repair a field they could not see and did not cause. And had the
  record survived, `Bearer ` would have gone on the wire: an empty bearer is not an *expired* token,
  so it comes back `invalid_token`, which this SDK treats as terminal — the damaged record could
  never heal itself on a retry, it simply failed permanently one wasted round trip later. A grant
  now survives storage whenever either half of the pair is usable, and a blank access token is
  renewed through the **existing** single-flight refresh before the first request is built, so the
  caller sees nothing at all. Re-consent was the wrong answer to this: it discards a grant that
  still works. With no refresh token there is genuinely no credential left in the record, and that
  case is unchanged — it reads as no stored grant and raises `TraciveException.InvalidToken` without
  sending anything. `TokenSet` correspondingly asks for *a* live credential rather than specifically
  an access token, and still refuses one holding neither. The React Native, Swift and Flutter SDKs
  carry the same repair, so the four behave identically here.
- **A spent or abandoned authorization is no longer left behind, and a long-dead one is no longer
  exchanged.** Two halves of the same omission. Nothing erased the attempt once it had resolved, so a
  redirect delivered twice — or delivered late by an OS that had queued it — met a verifier still
  sitting next to the `state` it answered to. And nothing looked at the clock: a callback arriving an
  hour after `authorize()` was accepted, its long-expired code sent to the token endpoint, and the
  only possible reply was an `invalid_grant` the user watched arrive for no reason anyone could
  explain. Every outcome that consumes the attempt now clears the record — a completed exchange, a
  code handed back to a confidential client, a denial, an expiry, `cancelPendingAuthorization()`, a
  failed exchange, a browser that would not open, and `signOut()` — and a callback older than the
  window is refused locally with `AuthResult.Failed(InvalidRequest)` before anything is sent. A
  `state` mismatch is refused without erasing anything; see the entry below for why it is not one of
  these.
- **A callback whose `state` does not match no longer erases the pending authorization.** It is
  still refused on exactly the same terms — `state` is compared before `error` or `code` is read,
  the code is never redeemed, nothing is sent, and the answer is still
  `AuthResult.Failed(InvalidRequest)` — but the record now stays where it is, so the user's own
  redirect still completes the flow when it arrives. Erasing it handed every other app on the device
  a one-call cancel button. Hosts are told to route every incoming deep link through
  `handleCallback`, and a private-use redirect scheme is one any installed app may declare, so a
  single URL carrying a foreign `state` was enough to end an authorization the user was in the
  middle of giving: they finished consenting in the Tracive app, came back, and found the attempt
  already abandoned with nothing left to complete. Nothing reported it either, because from the
  SDK's side the forged URL had been turned away exactly as it should have been. The objection to
  keeping the record is that a forged redirect must not leave a live PKCE verifier parked on the
  device, and it resolves the other way: the forgery does not make the verifier sit there any
  *longer* — it was already going to, for the ten minutes the user has to finish consenting — and
  the expiry still retires it on time. What the erase bought was not a shorter window, only the
  cancel button. A redirect arriving when no authorization is in progress is still
  `AuthResult.Failed(InvalidRequest)`; there is nothing there to protect. The Flutter, Swift and
  React Native SDKs all keep the record for the same reason.
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
