# OAuth

The only authentication mode phase 1 implements. OAuth 2.0 authorization code with PKCE, against the
Tracive app on the same device.

> **The authorization endpoint is not live yet.** The flow below is implemented and unit-tested
> against an injected transport; it will not complete against a real server until the backend ships.

---

## Why this shape

The partner app never holds a credential. The user authorises inside the *Tracive* app, and the
partner app receives a short-lived token scoped to that user, billed to that user's credits.

This is also why **there is no API key parameter anywhere in the SDK.** A key shipped in an APK or
IPA is a key that has been published — `apktool` gets it out in about a minute, and every check
afterwards is billed to you. If you need a place to keep a secret, you need a backend, which is Proxy
mode ([docs/proxy-contract.md](proxy-contract.md)), and that is phase 2.

## The flow

```
authorize()
  │
  ├─ Tracive app installed?  ──no──> open the clean install link ──> AuthResult.AppNotInstalled
  │                                  (no OAuth parameters, ever)
  yes
  │
  ├─ generate code_verifier + code_challenge (S256) + state
  ├─ open the authorize URL in a Custom Tab / Universal Link       [never a WebView]
  │
  ⋮  (user consents in the Tracive app)
  │
  ├─ redirect arrives ──> handleCallback(url)
  │     └─ state mismatch ──> AuthResult.Failed(InvalidRequest)
  │
  ├─ ClientType.PUBLIC       ──> POST /oauth/token with code + verifier ──> Authorized(scopes)
  └─ ClientType.CONFIDENTIAL ──> CodeReceived(code, codeVerifier, redirectUri)
```

### 1. The install pre-check comes first

Before any redirect, the SDK checks whether the Tracive app is present — Android package visibility,
iOS `canOpenURL`. If it is missing, the user is sent to a **clean** install link carrying no OAuth
parameters whatsoever (an attribution tag naming the client is allowed), and `authorize()` returns
`AuthResult.AppNotInstalled` without starting a flow.

There is **no deferred resume.** Nothing is held; the user installs Tracive and retries in your app.
Handle it by showing "install Tracive, then try again" — the SDK has already opened the store page.

Two platform requirements make this pre-check actually work, and both fail *silently* in the same
direction — reporting "not installed" for users who do have it, and sending all of them to the store:

- **Android:** the `<queries>` entry ships in the SDK's AAR manifest and merges into your app
  automatically. Nothing to declare, but do not strip it.
- **iOS:** you must add `LSApplicationQueriesSchemes` = `tracive` to `Info.plist` yourself.

### 2. PKCE, S256 only

`code_verifier` is 43–128 characters from the RFC 7636 unreserved set;
`code_challenge = BASE64URL(SHA256(verifier))`.

**`plain` is not supported and will not be added.** With `plain` the challenge *is* the verifier, so
a malicious app that intercepted the authorization redirect could read the verifier straight out of
it and redeem the code — which is the entire attack PKCE exists to stop. RFC 8252 §8.1 requires S256
for native apps for exactly this reason.

### 3. Custom Tabs / Universal Link — never a WebView

The authorize page opens in the system browser: Custom Tabs on Android, `UIApplication.open` on iOS.

**Do not put this in a WebView**, and do not ask for a mode that does. Two independent reasons:

- An App Link / Universal Link **does not fire from an in-app WebView**, so the redirect back to your
  app would never arrive and the flow would hang.
- RFC 8252 §8.12 forbids embedded user agents for OAuth. A WebView is your process — it can read the
  user's Tracive credentials as they type them, which is precisely the trust boundary this flow
  exists to preserve.

### 4. `state` is verified before the code is used

`handleCallback(url)` checks `state` first. A mismatch resolves `authorize()` with
`AuthResult.Failed(InvalidRequest)` and the code is never redeemed — a callback whose `state` does
not match did not come from the request this SDK started.

`handleCallback` does not suspend, so it is safe to call directly from `onNewIntent` / `onOpenURL`.
Any token exchange happens on the coroutine that called `authorize()`.

It returns `false` when nothing is pending — a stray link, a duplicate delivery, or a callback that
outlived the process. Treat `false` as "not mine", not as an error.

Calling `authorize()` again while one is pending supersedes the first, which returns
`AuthResult.Failed` rather than hanging forever. Only one `state` can be outstanding, and honouring a
stale callback is the exact confusion `state` exists to prevent.

## Redirect URI: App Link over custom scheme

Register an **https App Link (Android) / Universal Link (iOS)** if you control a domain.

A verified App Link is bound to your app by a file you host, so no other app on the device can claim
it. A private-use scheme (`myapp://oauth`) can be declared by *any* installed app, and on a collision
the OS's choice is not yours — a malicious app can receive your authorization code. PKCE is what
stops that code being redeemable, which is exactly why S256 is non-negotiable above; but not handing
the code over in the first place is better.

RFC 8252 permits a private-use scheme for a native app with no web domain — the sample uses one for
that reason. It is the fallback, not the recommendation.

Whatever you pick, **the value must match `TraciveMode.OAuth(redirectUri = …)` exactly**, scheme and
host included. A mismatch fails at the very last step of the round trip.

## Public vs confidential clients

```kotlin
ClientType.PUBLIC        // the SDK performs the PKCE token exchange itself
ClientType.CONFIDENTIAL  // the SDK stops at the code; your backend exchanges it
```

**Public** is the normal choice for a mobile app with no backend. `authorize()` returns
`AuthResult.Authorized(grantedScopes)` and the tokens are already stored.

**Confidential** is for a partner whose backend holds a `client_secret`. The SDK does no exchange and
returns everything your backend needs:

```kotlin
is AuthResult.CodeReceived -> {
  val tokens = yourBackend.exchange(auth.code, auth.codeVerifier, auth.redirectUri)
  client.oauth!!.adoptTokens(
    accessToken = tokens.accessToken,
    refreshToken = tokens.refreshToken,   // omit only if your backend will not delegate refresh
    expiresIn = tokens.expiresIn,
    grantedScopes = tokens.scopes,
  )
}
```

Pass the refresh token if your backend is willing to delegate refreshing. Without one the SDK cannot
refresh, and an expiry surfaces as `TraciveException.InvalidToken` for you to handle.

## Tokens

**Access token** — JWT, ~1 hour. **Refresh token** — opaque, rotating.

Both live in the Android Keystore / iOS Keychain. **Never** in `SharedPreferences`, `UserDefaults`,
or a plain file. You do not manage them; `TokenStore` is injectable only as a test seam.

### Refresh is automatic and single-flight

On a 401 `expired_token` the SDK refreshes and retries the request once. You do not handle
`ExpiredToken`.

**Concurrent 401s trigger exactly one refresh**; the rest wait on its result. This is not an
optimisation. Refresh tokens rotate, so two simultaneous refreshes would present the same token
twice, and the second presentation looks exactly like a stolen-token replay — the server's reuse
detection fires and **revokes the entire grant**, signing your user out. The SDK holds a mutex so that
cannot happen. If you ever wrap the token store or the transport, preserve that property.

A new refresh token replaces the stored one atomically.

### `grant_revoked` is terminal

A 401 `grant_revoked` clears the stored tokens and raises `TraciveException.GrantRevoked`. **Do not
retry.** Send the user through `authorize()` again.

## Sign-out

```kotlin
client.signOut()
client.cache.clear()
```

`signOut()` POSTs to `/oauth/revoke` best-effort, then clears local tokens **regardless of outcome**.
The unconditional local clear is deliberate: a revoke that fails because the device is offline must
still leave the user signed out here. Leaving a live refresh token behind because a network call
failed is the wrong side to err on.

It revokes the **refresh** token (RFC 7009 §2.1), which invalidates every access token issued under
it. Revoking only the access token would leave the refresh token alive and let a "signed out" device
mint a new session.

Clear the cache too — those entries belong to the user who was authorised when they were fetched.

## Testing it

```kotlin
class FakePresence(private val installed: Boolean) : AppPresenceChecker {
  override suspend fun isTraciveAppInstalled() = installed
}

class RecordingLauncher : AuthorizeLauncher {
  var authorizeUrl: String? = null
  var installUrl: String? = null
  override suspend fun launchAuthorize(url: String) { authorizeUrl = url }
  override suspend fun launchInstall(url: String) { installUrl = url }
}

val oauth = TraciveOAuth(
  mode = TraciveMode.OAuth(/* … */),
  environment = TraciveEnvironment.Custom("https://localhost/v1"),
  transport = FakeTransport(tokenResponse),
  appPresenceChecker = FakePresence(installed = true),   // false exercises the install path
  authorizeLauncher = RecordingLauncher(),
  tokenStore = InMemoryTokenStore(),
  clock = testClock,
)
```

Every seam the flow needs is a constructor parameter — presence check, launcher, transport, token
store, clock — so the whole thing runs with no browser, no device and no network. That is how the
suite in this repo tests it, and there is no server to test against anyway.

**Call `oauth.close()` on any `TraciveOAuth` you built yourself without passing a `transport`.**
Without one it lazily builds its own HTTP client and owns it — on Android that is an OkHttp
dispatcher and a connection pool that outlive every reference to the object. Instances reached
through `TraciveClient.oauth` need nothing: they share the client's transport, and
`TraciveClient.close()` releases it once.
