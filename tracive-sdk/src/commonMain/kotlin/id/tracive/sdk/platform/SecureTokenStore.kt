package id.tracive.sdk.platform

import id.tracive.sdk.oauth.TokenStore

/**
 * The platform's hardware-backed token store: Android Keystore, or the iOS Keychain.
 *
 * Named like a constructor because that is what it is from the caller's side — the concrete type
 * differs per platform and is deliberately not part of the common surface.
 *
 * The refresh token this holds is the long-lived half of the grant, and it rotates: the server
 * hands back a new one on every refresh and treats a *reused* one as evidence of theft, revoking
 * the whole grant. That makes the storage guarantee load-bearing rather than decorative. It never
 * goes in `SharedPreferences` or `UserDefaults` in the clear, and never in a plain file.
 *
 * @throws IllegalStateException on Android if the SDK has no application `Context` yet. That
 *   normally cannot happen — the SDK's manifest registers a `ContentProvider` that captures it
 *   during app startup — but a host that strips the provider with `tools:node="remove"` has to call
 *   `TraciveAndroid.initialize(context)` itself.
 */
expect fun SecureTokenStore(): TokenStore
