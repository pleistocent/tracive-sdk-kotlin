package id.tracive.sdk.sample

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * What `:samples:androidApp` sees, and the mirror of `SampleController` on iOS.
 *
 * The screen and the view model are `internal` on purpose. On iOS that is load-bearing:
 * Kotlin/Native generates an Objective-C adapter for every exported declaration, and a public one
 * whose signature mentions a type the framework does not export — `CoroutineScope` on
 * `CheckViewModel`'s constructor, `TraciveClient` on its client parameter — crashes that generator
 * outright. Android has no such constraint, but keeping one entry point per platform means the two
 * shells stay symmetric and the shared code has a single, deliberate way in.
 */
class SampleHost(context: Context) {

  private val model = CheckViewModel(traciveClient(context))

  /** The shared screen. */
  @Composable fun Content() = SampleScreen(model)

  /**
   * Forwards a deep link from the Activity.
   *
   * Every incoming URL should come through here. The SDK answers null for anything that is not its
   * own redirect, which is what makes routing all of them safe.
   */
  fun handleRedirect(url: String) = model.handleRedirect(url)
}
