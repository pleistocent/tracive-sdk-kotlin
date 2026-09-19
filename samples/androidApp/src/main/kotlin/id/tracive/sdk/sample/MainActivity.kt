package id.tracive.sdk.sample

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Background for the navigation bar on API 24-25, which cannot darken that bar's icons — they are
 * always white, so a transparent bar there would put white buttons on a light Surface. This is the
 * platform's own scrim. API 26 and above darken the icons and ignore this.
 */
private val NAVIGATION_BAR_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

/**
 * The Android shell: an Activity, edge-to-edge setup, and the redirect plumbing. The screen itself
 * is `SampleScreen`, which is shared with iOS.
 *
 * `launchMode="singleTask"` in the manifest is what makes [onNewIntent] the delivery point for the
 * OAuth redirect: the browser resumes this existing task instead of stacking a second copy of the
 * app behind it.
 */
class MainActivity : ComponentActivity() {

  /**
   * Built here rather than inside `setContent`, because a cold start caused by the redirect has to
   * reach the view model before the first composition has necessarily run. Creating it from the
   * composition left a window in which the redirect that started the app was delivered to nothing —
   * and that window is exactly the process-death case the SDK now recovers from.
   *
   * [SampleHost] is the shared sample's Android entry point; the screen itself is common code.
   */
  private val host by lazy {
    SampleHost(this, flavor = BuildConfig.FLAVOR, redirectUri = BuildConfig.TRACIVE_REDIRECT_URI)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    // At targetSdk 35 and above the platform draws this window behind the system bars and ignores
    // the theme's statusBarColor; at 36 the opt-out flag is gone too. So the only choice left is
    // between declaring edge-to-edge and getting it undeclared, and undeclared is what leaves the
    // header under the status bar with white icons over a light Surface. `light` rather than the
    // default `auto` because the theme in SampleScreen is the light scheme in every configuration —
    // `auto` follows the system into dark mode and would put those white icons back. The dark
    // navigation scrim is only reached on API 24-25, which cannot darken that bar's icons.
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, NAVIGATION_BAR_SCRIM),
    )
    super.onCreate(savedInstanceState)
    setContent { host.Content() }

    // A cold start launched *by* the redirect arrives here rather than in onNewIntent. Consuming
    // the URI keeps a configuration change from replaying an already-spent redirect, which the SDK
    // would answer as "no authorization is in progress".
    intent?.data?.let {
      intent.data = null
      host.handleRedirect(it.toString())
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.data?.let {
      intent.data = null
      host.handleRedirect(it.toString())
    }
  }
}
