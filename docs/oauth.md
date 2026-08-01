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
  ├─ TokenStore.savePending(state, verifier, redirectUri, createdAt)   [before the browser, always]
  ├─ open the authorize URL in a Custom Tab / Universal Link           [never a WebView]
  │     └─ the OS refuses ──> roll the record back ──> AuthResult.Failed
  │
  └─ returns null: the page is open, and this call is finished
  ⋮
  ⋮  (user consents in the Tracive app; your process may be killed meanwhile)
  ⋮
handleCallback(url)
  │
  ├─ not our redirect, or no code/error/state on it ──> null, nothing touched
  │
  ├─ pending record: from memory, else from TokenStore.loadPending()  [survives a process death]
  │     ├─ nothing pending ──> AuthResult.Failed(InvalidRequest)
  │     ├─ state mismatch ──> AuthResult.Failed(InvalidRequest)   [the record is left alone]
  │     └─ older than 10 minutes ──> AuthResult.Failed(InvalidRequest)
  │
  ├─ error=access_denied      ──> AuthResult.Denied
  ├─ ClientType.PUBLIC        ──> POST /oauth/token with code + verifier ──> Authorized(scopes)
  └─ ClientType.CONFIDENTIAL  ──> CodeReceived(code, codeVerifier, redirectUri)
        │
        └─ every outcome that consumes the attempt ──> TokenStore.savePending(null)
```

Two calls rather than one awaitable call, because it is not one process. The consent screen belongs
to the Tracive app, so your app spends the whole of it in the background — which on Android makes it
an ordinary candidate for being killed. Anything parked waiting for the redirect would be gone by
the time it arrived. The Flutter and React Native SDKs are shaped the same way.

### 1. The install pre-check comes first

Before any redirect, the SDK checks whether the Tracive app is present — Android package visibility,
iOS `canOpenURL`. If it is missing, the user is sent to a **clean** install link and `authorize()`
returns `AuthResult.AppNotInstalled` without starting a flow.

That link is `https://tracive.chottu.link/install`, the same base URL in every Tracive SDK, and it
may carry exactly two optional non-sensitive query parameters:

- `ref` — your OAuth client id, as an attribution tag. Omitted when you have none.
- `utm_source` — `tracive-sdk-kotlin`, naming which SDK opened the link.

**No OAuth parameter ever appears on it** — not `state`, `code_challenge`, `code_verifier`,
`partner_user_id`, `redirect_uri` or `scope`. They would be useless by the time the user came back,
and the link goes through a third-party link service, so anything on it is handed to someone else's
servers. You can replace the whole link (`installUrl` on `TraciveClient`, `installUrlOverride` on
`TraciveOAuth`); hold to the same rule if you do.

#### Replacing the link: blank is refused, not substituted

```kotlin
TraciveClient(mode = …)                                  // default link
TraciveClient(mode = …, installUrl = null)               // default link — absence is a real answer
TraciveClient(mode = …, installUrl = "https://…/get")    // used verbatim
TraciveClient(mode = …, installUrl = "")                 // IllegalArgumentException
TraciveClient(mode = …, installUrl = "   ")              // IllegalArgumentException
```

A **blank** override — empty, or nothing but whitespace — is rejected as the object is built, and the
failure message carries the token `blank_install_url`. That token is identical in every Tracive SDK,
so match on it rather than on the wording around it.

Anything non-blank is used **verbatim**: not trimmed, not repaired, not checked for being a URL. This
is deliberately not URL validation — a wrong-but-non-blank link reaches your users exactly as you
wrote it, because vetting your own link is not the SDK's job.

The one value it does refuse is the one that carries no intent at all, and refusing it is the whole
point. Falling back to the Tracive default on a blank is the most dangerous of the available
behaviours, because the resulting link *works*: if your build pipeline ever yields an empty string,
you ship a release that silently sends your users to Tracive's install page instead of yours, and
nothing in your logs or ours reports it. A constructor throw runs once, in your own code, on every
run, in development — long before an end user is involved.

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

`handleCallback(url)` checks `state` before the code is used for anything. A mismatch answers
`AuthResult.Failed(InvalidRequest)` and the code is never redeemed — a callback whose `state` does
not match did not come from the request this SDK started.

**A mismatch does not cancel the authorization.** It is refused and nothing else happens: the pending
record stays exactly where it was, so the user's own redirect still completes the flow when it
arrives. Any app on the device can hand you a URL to pass in here, and erasing the record on a
mismatch would give any of them a one-call cancel button — the user finishes consenting in the
Tracive app, comes back, and finds the attempt already gone, with nothing in your logs or ours
saying why. Keeping it costs nothing in exposure: the verifier was always going to sit on the device
until the user finished or the ten-minute window closed, and a forged redirect does not move that
deadline. The Flutter, Swift and React Native SDKs behave the same way.

`handleCallback` **suspends**, because completing an authorization means reading the secure store
and, for a public client, exchanging the code. Call it from a coroutine in your deep-link handler,
and **act on what it returns** — this is the only place an authorization succeeds.

It returns `null` when the URL is not this client's redirect, or carries none of `code`, `error` or
`state`. That is "not mine": nothing was touched, and you should go on routing the link to whatever
else in your app handles deep links. It matters because a private-use redirect scheme can be
declared and fired by any app on the device, and one empty deep link must not be able to abort a
user's authorization.

A URL that *is* this client's redirect but has nothing left to complete — already spent, expired, or
started by nobody — answers `AuthResult.Failed(InvalidRequest)` rather than `null`. Deliver each
redirect once: on Android, clear `intent.data` after handing it over, or a configuration change will
replay a spent redirect and produce that failure for no reason.

Calling `authorize()` again while one is pending supersedes the first. Only one `state` can be
outstanding, so the earlier attempt's redirect is then refused as a mismatch — honouring a stale
callback is the exact confusion `state` exists to prevent.

`cancelPendingAuthorization()` suspends too, and it is how you erase the record when the browser
closes with no redirect. There is no `AuthResult.Denied` for the caller to receive at that point;
nothing is waiting.

### 5. The pending authorization is written down, and it expires

Consent happens in the *Tracive* app, so your app is in the background for the whole of it — which on
Android makes it an ordinary candidate for being killed. The `code_verifier` never leaves the device,
so a verifier held only in memory dies with the process, and the returning redirect then names a
`state` nothing can match and carries a code nobody can redeem.

So before the browser opens — never after — the SDK writes a `PendingAuthorization` to the same
`TokenStore` that holds the tokens:

```kotlin
class PendingAuthorization(
  val state: String,        // the CSRF nonce that has to come back unchanged
  val codeVerifier: String, // the half of the PKCE pair that stays here
  val redirectUri: String,  // repeated verbatim in the token exchange, per RFC 6749 §4.1.3
  val createdAt: Instant,   // when the attempt started
)
```

**It is a secret.** `codeVerifier` is exactly what makes a returned authorization code redeemable, so
it belongs in the Keystore / Keychain beside the refresh token — never in `SharedPreferences`,
`UserDefaults` or a plain file. If you supply your own `TokenStore`, its `savePending` has to meet
that bar. Nothing in the record reaches a log line: `toString()` redacts both the verifier and the
`state`.

**It expires after ten minutes** (`TraciveOAuth.DEFAULT_PENDING_AUTHORIZATION_MAX_AGE`). A callback
arriving later is refused as `AuthResult.Failed(InvalidRequest)` and no request is sent. Ten is not a
guess: it is the authorization code's own server-side lifetime, so past it the code the redirect
carries is already dead and holding the verifier longer buys nothing — it only leaves a secret on
disk and turns a hopeless attempt into a round trip that can come back nothing but `invalid_grant`.
Pass `pendingAuthorizationMaxAge` to narrow it; the Flutter and React Native SDKs expose the same
knob.

**Every outcome that consumes the attempt erases it** — a completed exchange, a code handed back to
a confidential client, a denial, an expiry, `cancelPendingAuthorization()`, a failed exchange, a
browser that would not open, and `signOut()`. A spent attempt cannot be replayed by a redirect that
turns up afterwards. A `state` mismatch is not on that list, for the reason given in §4: it is a URL
this SDK did not ask for, not an ending of the attempt the user started.

**And a redirect arriving in a new process completes the flow.** `handleCallback` takes the record
from memory when this process is the one that started the authorization, and from the `TokenStore`
when it is not. So a user whose phone killed your app while they were consenting comes back to a
finished authorization rather than to a dead flow, which is the whole reason the record is written
down at all.

A `TokenStore` that cannot write the record does not fail the authorization. Only process-death
recovery is lost: a callback that reaches the process that started the flow is still completed from
memory. A store that cannot be *read* answers "nothing pending" rather than throwing —
`handleCallback` is called from a deep-link handler, which is no place to raise from.

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

**Public** is the normal choice for a mobile app with no backend. `handleCallback()` returns
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

Both live in the Android Keystore / iOS Keychain, and so does the `PendingAuthorization` above.
**Never** in `SharedPreferences`, `UserDefaults`, or a plain file. You do not manage them;
`TokenStore` is injectable only as a test seam.

### Refresh is automatic and single-flight

On a 401 `expired_token` the SDK refreshes and retries the request once. You do not handle
`ExpiredToken`.

**Concurrent 401s trigger exactly one refresh**; the rest wait on its result. This is not an
optimisation. Refresh tokens rotate, so two simultaneous refreshes would present the same token
twice, and the second presentation looks exactly like a stolen-token replay — the server's reuse
detection fires and **revokes the entire grant**, signing your user out. The SDK holds a mutex so that
cannot happen. If you ever wrap the token store or the transport, preserve that property.

A new refresh token replaces the stored one atomically.

### A blank access token is repaired, not re-consented

A stored grant can come back with an **empty access token beside a refresh token that still works** —
a partial write, a save interrupted by the process being killed, or a restored backup. The SDK reads
that as damage rather than as the end of the grant: the refresh token is a live credential, so it is
spent to mint a new access token through the same single-flight refresh, before the first request is
built. Your call proceeds and you are told nothing.

This has to be checked locally, because nothing on the wire can fix it. An empty bearer is not an
*expired* token — a malformed one is answered with `invalid_token`, which is terminal here — so the
damaged record would never heal itself on a retry. It would fail permanently, one wasted round trip
later.

With **no** refresh token there is no credential left in the record at all. That reads as no stored
grant, raises `TraciveException.InvalidToken`, and sends nothing; the user authorizes again. Note
that `adoptTokens()` stores what you hand it without validating it, so a confidential-client backend
that returns an empty access token lands in exactly the recoverable case above — provided it also
delegated a refresh token.

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
