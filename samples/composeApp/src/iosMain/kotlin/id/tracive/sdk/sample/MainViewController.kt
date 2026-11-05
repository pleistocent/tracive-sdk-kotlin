package id.tracive.sdk.sample

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The bridge Swift calls, and the **only** type this framework exports.
 *
 * Everything else in the sample is `internal` on purpose. Kotlin/Native generates an Objective-C
 * adapter for every exported declaration, and a public one whose signature mentions a type from a
 * dependency the framework does not export — `CoroutineScope` on `CheckViewModel`'s constructor,
 * `TraciveClient` on `TraciveSample` — crashes that generator outright:
 * `IrExternalPackageFragmentImpl cannot be cast to IrClass`, raised from
 * `createConstructorAdapter`. Keeping the exported surface to this one class is both the fix and
 * the right shape: Swift needs a view controller and a way to hand over a URL, and nothing else.
 *
 * `iosApp` holds one of these for the life of the app.
 *
 * The view model is created here rather than inside the composition so that a redirect arriving
 * before the first frame — a cold start caused by the callback URL — still reaches it. That is the
 * same reason the Android sample reads its view model in `onCreate` instead of `setContent`.
 */
class SampleController {

  private val model = CheckViewModel(traciveClient())

  private val reportModel = ReportViewModel(traciveClient())

  /** The Compose screen, as something a `UIHostingController` can show. */
  fun viewController(): UIViewController = ComposeUIViewController {
    SampleScreen(model, reportModel)
  }

  /**
   * Forwards a deep link from Swift's `onOpenURL`.
   *
   * Every incoming URL should come through here. The SDK answers null for anything that is not its
   * redirect, which is what makes routing all of them safe.
   */
  fun handleRedirect(url: String) {
    model.handleRedirect(url)
  }
}
