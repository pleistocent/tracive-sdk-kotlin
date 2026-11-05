package id.tracive.sdk.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

@OptIn(ExperimentalForeignApi::class)
class IosReportFormLauncherTest {

  @Test
  fun nothingCoversTheControllerThatWasOnTop() {
    val window = UIWindow()
    window.rootViewController = UIViewController()

    assertNull(presentedAbove(window.rootViewController, window))
  }

  @Test
  fun aPresentedControllerIsWhatCoversIt() {
    val root = UIViewController()
    val window = UIWindow()
    window.rootViewController = root
    window.hidden = false
    val sheet = UIViewController()
    root.presentViewController(sheet, animated = false, completion = null)

    assertEquals(sheet, presentedAbove(root, window))
  }
}
