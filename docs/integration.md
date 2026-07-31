# Integration

Wiring the Tracive SDK into an Android or iOS app, end to end.

> **The backend Open API is not live yet.** Everything below is correct against the frozen contract,
> and the local half of it — query folding, the cache, error mapping — works today. Calls that need
> the network will fail until the API ships. See the [README](../README.md) status section.

---

## 1. Depend on it

Not on Maven Central yet:

```bash
./gradlew :tracive-sdk:publishToMavenLocal
```

```kotlin
dependencies { implementation("id.tracive:tracive-sdk:0.1.0") }
```

Android `minSdk` 24, iOS 15+, JDK 17 to build.

## 2. Build one client, once

A `TraciveClient` owns an HTTP client, a token store and a database handle. Build it **once per
process** and share it — not once per screen. Hand it to whatever DI you already use.

```kotlin
val client = TraciveClient(
  mode = TraciveMode.OAuth(
    clientId = "your-client-id",
    redirectUri = "https://yourapp.example.com/oauth/callback",
    scopes = listOf("check:read", "profile:read"),
    partnerUserId = yourInternalUserId,   // or null
    clientType = ClientType.PUBLIC,
  ),
  environment = TraciveEnvironment.Sandbox,
  cachePolicy = CachePolicy.Default,
  defaultSource = Source.CACHE_FIRST,
  cache = yourCache,       // see docs/caching.md
)
```

Notes on the arguments that are easy to get wrong:

- **`redirectUri` must match your manifest / `Info.plist` exactly**, scheme and host included. A
  mismatch fails at the very end of the OAuth round trip, which is the most annoying possible time to
  find out. The sample keeps it in a `manifestPlaceholder` so the two cannot drift.
- **`scopes`** — ask for the narrowest set that works. `Detail.FULL` needs a scope most integrations
  do not have, and requesting it makes your consent screen scarier for no gain.
- **`partnerUserId`** is *your* identifier for the user, so a grant survives a reinstall. **Never
  pass a raw phone number or email here.** Pass `null` if you have no accounts.
- **There is no API key parameter.** Not here, not anywhere. See the README for why.

`environment` is `Production` by default; use `Sandbox` while building, and
`TraciveEnvironment.Custom(url)` for a local server in tests.

Call `client.close()` when the process is done with it — it releases the HTTP client the SDK built.
It does **not** close a SQL driver you passed in; that one is yours.

## 3. Android wiring

### Nothing to declare for package visibility

The `<queries>` entry for `com.pleistocent.tracive` ships inside the SDK's own AAR manifest and
merges into your app automatically. Do not add it yourself, and do not remove it.

This matters more than it looks. Without package visibility on Android 11+, the SDK's "is the Tracive
app installed?" pre-check silently answers "no" for every user who *does* have it — and every one of
them gets sent to the install page instead of the consent screen.

### The redirect activity

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTask">

  <intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="yourapp.example.com" android:path="/oauth/callback" />
  </intent-filter>
</activity>
```

`launchMode="singleTask"` is what makes the redirect arrive in `onNewIntent` on the existing task
rather than stacking a second copy of your app behind the browser.

Prefer an **https App Link** over a private-use scheme if you control a domain: a verified App Link
cannot be claimed by another app on the device, whereas any app may declare a custom scheme. The
sample uses a scheme because it controls no domain; [docs/oauth.md](oauth.md) covers the trade-off.

### Delivering the callback

```kotlin
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A cold start launched *by* the redirect arrives here, not in onNewIntent.
    intent?.data?.let { client.oauth?.handleCallback(it.toString()) }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.data?.let { client.oauth?.handleCallback(it.toString()) }
  }
}
```

Handle **both**. A cold start caused by the redirect never reaches `onNewIntent`.

`handleCallback` returns `false` when nothing was pending — a stray link, a duplicate delivery, or a
callback that outlived the process. Treat `false` as "not mine", not as an error.

### Context

`TraciveAndroid.initialize(context)` is called for you by a `ContentProvider` in the SDK's manifest,
before `Application.onCreate` returns. Only call it yourself if you strip that provider.

## 4. iOS wiring

`Info.plist`:

```xml
<key>LSApplicationQueriesSchemes</key>
<array><string>tracive</string></array>
```

Without it, `canOpenURL` returns `false` and every user with the Tracive app installed is sent to the
App Store instead.

Then route the callback into the same client instance your shared Kotlin code exposes to Swift.
`handleCallback` does not suspend, so it is callable directly from Swift with no completion handler:

```swift
.onOpenURL { url in
  // `shared` here is whatever your shared module exposes — an `object` holding the one
  // TraciveClient, the same way `TraciveSample` does on Android.
  SharedClient.shared.client.oauth?.handleCallback(url: url.absoluteString)
}
```

Register the redirect itself as a Universal Link (`Associated Domains` + `apple-app-site-association`)
rather than a custom scheme, for the same reason Android should prefer an App Link.

## 5. Authorise

```kotlin
when (val auth = client.oauth!!.authorize()) {
  is AuthResult.Authorized -> proceed(auth.grantedScopes)
  is AuthResult.CodeReceived -> forwardToYourBackend(auth.code, auth.codeVerifier, auth.redirectUri)
  is AuthResult.Denied -> respectThat()
  is AuthResult.AppNotInstalled -> showInstallMessage()
  is AuthResult.Failed -> report(auth.error)
}
```

`authorize()` suspends until the redirect comes back through `handleCallback`, so call it from a
coroutine tied to a scope that survives the trip to the browser — a ViewModel's, not a composable's.

`AppNotInstalled` means the SDK **already opened the install page**; there is nothing to resume when
the user returns, so show a "install Tracive, then try again" message and let them retry. Details in
[docs/oauth.md](oauth.md).

## 6. Check

```kotlin
val query = try {
  TraciveQuery.phone(userInput)
} catch (e: TraciveQueryException) {
  return showInputError(e.reason)   // costs nothing; nothing was sent
}

val result = client.checkScammer(query)
```

Validation runs before any network call, deliberately: a malformed key cannot possibly hit, and a
miss is still billed.

Reading the result:

```kotlin
when (result.riskTier) {
  RiskTier.HIGH, RiskTier.ELEVATED -> warn(result)
  RiskTier.CAUTION -> mention(result)
  RiskTier.NONE -> proceedQuietly()
}

result.disclaimer?.let { showIt(it) }   // required alongside any verdict you display
if (result.origin == Origin.CACHE) showAge(result.fetchedAt)
```

**Show the disclaimer.** A risk tier is aggregated user-reported evidence, not an adjudication.
Presenting it as a verdict on a named person is a legal problem for you, not for us.

`relatedScammerCount` is a count and never a list — the SDK will not hand you other people's
identifiers.

## 7. Errors worth handling by name

```kotlin
try {
  client.checkScammer(query, idempotencyKey = key)
} catch (e: TraciveException.InsufficientCredits) {
  promptTopUp()
} catch (e: TraciveException.GrantRevoked) {
  reauthorize()                                  // tokens are already cleared; never retry
} catch (e: TraciveException.RateLimited) {
  backOff(e.retryAfter)                          // never auto-retried
} catch (e: TraciveException.Network) {
  showOffline()
}
```

You do not need to handle `ExpiredToken` — the SDK refreshes and retries once, single-flight.

Two rules that cost real money if ignored:

**Do not call `credits()` before each check.** It doubles the requests per transaction, and it still
races with anything else spending the same balance, so it can report credits that are gone by the
time the check runs. Handle the 402. Use `credits()` to *display* a balance, not to gate a call.

**Do not retry without an `idempotencyKey`.** A 5xx or a timeout does not say whether the server
processed the check before it failed, so a blind retry is a request to be charged twice. The SDK
retries only when you passed a key; one key per logical check, reused only to retry that same check.

## 8. Sign-out

```kotlin
client.signOut()        // best-effort revoke, then clears local tokens regardless of outcome
client.cache.clear()    // the entries belong to the user who was authorised when they were fetched
```

Clear the cache. The next user of the device must not inherit the previous one's answers.

## 9. Testing your integration

There is no server, so test against the seam:

```kotlin
class FakeTransport(private val response: HttpResponse) : HttpTransport {
  var lastRequest: HttpRequest? = null
  override suspend fun execute(request: HttpRequest): HttpResponse {
    lastRequest = request
    return response
  }
}

val client = TraciveClient(
  mode = TraciveMode.OAuth(/* … */),
  environment = TraciveEnvironment.Custom("https://localhost/v1"),
  transport = fake,
  clock = testClock,
)
```

`transport` and `clock` are constructor parameters for exactly this. Every test in this SDK's own
suite runs with no network, and yours should too.

## Runnable sample

`samples/android-compose/` is a small but complete Compose app: client construction, an Android
Keystore-backed cache key store, the OAuth round trip, and result rendering.

```bash
./gradlew :samples:android-compose:assembleDebug
```

Its network calls will fail until the API ships. What it demonstrates today is the wiring and the
local half.
