package id.tracive.sdk.platform

import id.tracive.sdk.model.ReportTarget
import id.tracive.sdk.report.OpenInMenuOutcome
import id.tracive.sdk.report.VanishedSheetWatch
import id.tracive.sdk.report.schemeHandoff
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentInteractionController
import platform.UIKit.UIDocumentInteractionControllerDelegateProtocol
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Hands a report to the Tracive app through the "Open in" sheet
 * (`UIDocumentInteractionController.presentOpenInMenu`), where the user taps Tracive.
 *
 * The package is written as `report.<ReportTarget.fileExtension>` to a fresh folder under the
 * temporary directory; the extension is what makes iOS offer the target, and only the target. The
 * folder is deleted on the next call.
 *
 * **Info.plist.** `LSApplicationQueriesSchemes` must list the target's capability scheme as well as
 * its URL scheme: `tracive-report` and `tracive`, plus `tracivesandbox-report` and `tracivesandbox`
 * for sandbox. Without the capability scheme every user is sent the identifier-only link, which is
 * safe but drops the date and the receipt.
 *
 * Every UIKit call is made on the main thread; the launcher hops there itself.
 *
 * @param anchor the view the sheet comes from, normally the Report button. On iPad the sheet is a
 *   popover pointing at it. Null presents from the key window's top view controller instead.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosReportFormLauncher(private val anchor: UIView? = null) : ReportFormLauncher {

  override suspend fun prepare(target: ReportTarget): ReportHandoff =
    withContext(Dispatchers.Main) {
      NSFileManager.defaultManager.removeItemAtPath(packageRoot(), error = null)
      schemeHandoff(target) { url ->
        NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.canOpenURL(it) } ?: false
      }
    }

  override suspend fun deliverPackage(target: ReportTarget, reportPackage: ByteArray): Boolean =
    withContext(Dispatchers.Main) {
      val file = writePackage(target, reportPackage)
      val presenter =
        anchor
          ?: topViewController()?.view
          ?: throw IllegalStateException("No window to present the Open-in sheet from.")
      val rect =
        presenter.bounds.useContents {
          if (anchor != null) CGRectMake(origin.x, origin.y, size.width, size.height)
          else CGRectMake(origin.x + size.width / 2, origin.y + size.height / 2, 0.0, 0.0)
        }

      suspendCancellableCoroutine { continuation ->
        val controller = UIDocumentInteractionController.interactionControllerWithURL(file)
        val session =
          OpenInMenuSession(controller, target.applicationId) { delivered ->
            continuation.resume(delivered)
          }
        controller.delegate = session
        liveSessions += session
        session.watchForSheet(presenter.window)
        if (!controller.presentOpenInMenuFromRect(rect, presenter, animated = true)) {
          session.release()
          continuation.resumeWithException(
            IllegalStateException("No app on this device offered to open the report.")
          )
        }
        continuation.invokeOnCancellation {
          dispatch_async(dispatch_get_main_queue()) {
            controller.dismissMenuAnimated(false)
            session.release()
          }
        }
      }
    }

  override suspend fun openLink(target: ReportTarget, url: String): Boolean =
    withContext(Dispatchers.Main) {
      val link =
        NSURL.URLWithString(url)
          ?: throw IllegalArgumentException("The Tracive SDK built a malformed report link.")
      suspendCancellableCoroutine { continuation ->
        // false when the user cancels iOS's "Open in Tracive?" prompt.
        UIApplication.sharedApplication.openURL(link, emptyMap<Any?, Any>()) { opened ->
          continuation.resume(opened)
        }
      }
    }

  private fun writePackage(target: ReportTarget, reportPackage: ByteArray): NSURL {
    val folder = packageRoot() + "/" + NSUUID().UUIDString
    NSFileManager.defaultManager.createDirectoryAtPath(
      folder,
      withIntermediateDirectories = true,
      attributes = null,
      error = null,
    )
    val path = "$folder/report.${target.fileExtension}"
    val data = reportPackage.usePinned {
      NSData.create(bytes = it.addressOf(0), length = reportPackage.size.convert())
    }
    check(data.writeToFile(path, atomically = true)) { "Could not write the report package." }
    return NSURL.fileURLWithPath(path)
  }

  private fun packageRoot(): String = NSTemporaryDirectory().trimEnd('/') + "/tracive-sdk/report"

  override fun toString(): String = "IosReportFormLauncher"

  private companion object {
    /**
     * The controller holds its delegate weakly, and the file transfer runs on after the call has
     * returned, so each sheet is kept here until it is finished with. Main thread only.
     */
    val liveSessions = mutableSetOf<OpenInMenuSession>()
  }

  private class OpenInMenuSession(
    private val controller: UIDocumentInteractionController,
    targetBundleId: String,
    onResolved: (Boolean) -> Unit,
  ) : NSObject(), UIDocumentInteractionControllerDelegateProtocol {

    private val outcome = OpenInMenuOutcome(targetBundleId, onResolved)
    private var sheetCheck: NSTimer? = null

    /**
     * Call just before presenting, while [window]'s top view controller is the one the sheet will
     * cover. `presentOpenInMenu` returns before the sheet is presented, so each check looks for it.
     */
    fun watchForSheet(window: UIWindow?) {
      val topBefore = topViewController(window)
      var sheet: UIViewController? = null
      val watch =
        VanishedSheetWatch(
          isGone = {
            val seen = sheet
            if (seen == null) {
              sheet = presentedAbove(topBefore, window)
              false
            } else {
              seen.presentingViewController == null
            }
          },
          onVanished = {
            outcome.dismissed()
            release()
          },
        )
      sheetCheck =
        NSTimer.scheduledTimerWithTimeInterval(0.5, repeats = true) { _ -> watch.check() }
    }

    @ObjCSignatureOverride
    override fun documentInteractionController(
      controller: UIDocumentInteractionController,
      willBeginSendingToApplication: String?,
    ) {
      stopSheetCheck()
      outcome.willBeginSending(willBeginSendingToApplication)
    }

    @ObjCSignatureOverride
    override fun documentInteractionController(
      controller: UIDocumentInteractionController,
      didEndSendingToApplication: String?,
    ) {
      release()
    }

    override fun documentInteractionControllerDidDismissOpenInMenu(
      controller: UIDocumentInteractionController
    ) {
      outcome.dismissed()
      if (!outcome.sendingStarted) release()
    }

    fun release() {
      stopSheetCheck()
      controller.delegate = null
      liveSessions -= this
    }

    private fun stopSheetCheck() {
      sheetCheck?.invalidate()
      sheetCheck = null
    }
  }
}

/** The view controller on top of the key window, for a sheet with no anchor of its own. */
@Suppress("DEPRECATION")
private fun topViewController(): UIViewController? {
  val application = UIApplication.sharedApplication
  val window =
    application.connectedScenes
      .filterIsInstance<UIWindowScene>()
      .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
      .firstOrNull { it.isKeyWindow() } ?: application.keyWindow
  return topViewController(window)
}

/** What covers [topBefore] in [window] by now, or null while nothing has been presented over it. */
internal fun presentedAbove(topBefore: UIViewController?, window: UIWindow?): UIViewController? =
  // `!=`, not `!==`: Kotlin/Native wraps an Objective-C object anew each time it crosses over, so
  // `!==` calls the same controller different and the root passes for the sheet.
  topViewController(window)?.takeIf { it != topBefore }

private fun topViewController(window: UIWindow?): UIViewController? {
  var top = window?.rootViewController
  while (top?.presentedViewController != null) top = top.presentedViewController
  return top
}

/** The "Open in" sheet, presented from the key window's top view controller. */
actual fun defaultReportFormLauncher(): ReportFormLauncher = IosReportFormLauncher()
