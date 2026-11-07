@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package id.tracive.sdk.extended

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/*
 * What the Swift overlay calls. Cancelling a Swift Task does not reach a suspend function exported
 * to Swift, so a closed form would keep uploading: each call here hands back a handle the overlay
 * cancels from `withTaskCancellationHandler`. The completion runs exactly once, also on cancellation,
 * or the overlay's continuation would never resume.
 */

/** Cancels the call it came from. */
class ReportCallHandle internal constructor(private val job: Job) {
  fun cancel() {
    job.cancel()
  }
}

private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private fun <T : Any> bridged(
  completion: (T?, Throwable?) -> Unit,
  call: suspend () -> T,
): ReportCallHandle =
  // UNDISPATCHED so the body always starts: a job cancelled before it began would skip completion.
  ReportCallHandle(
    bridgeScope.launch(start = CoroutineStart.UNDISPATCHED) {
      val outcome =
        try {
          Result.success(call())
        } catch (failure: Throwable) {
          Result.failure(failure)
        }
      completion(outcome.getOrNull(), outcome.exceptionOrNull())
    }
  )

fun TraciveReports.startRequirements(
  completion: (ReportRequirements?, Throwable?) -> Unit
): ReportCallHandle = bridged(completion) { requirements() }

fun TraciveReports.startPrepare(
  data: NSData,
  mimeType: String,
  completion: (PreparedEvidence?, Throwable?) -> Unit,
): ReportCallHandle = bridged(completion) { prepare(data.toByteArray(), mimeType) }

fun TraciveReports.startSubmit(
  draft: ReportDraft,
  onProgress: ReportProgressListener?,
  completion: (SubmitResult?, Throwable?) -> Unit,
): ReportCallHandle = bridged(completion) { submit(draft, onProgress) }

fun TraciveReports.startStatus(
  reportId: String,
  completion: (ReportStatus?, Throwable?) -> Unit,
): ReportCallHandle = bridged(completion) { status(reportId) }

/** What [TraciveReports.openInTracive] opens. Swift opens it itself, to learn whether it worked. */
fun TraciveReports.reportDetailsLink(reportId: String): String = detailsLink(reportId)

/** The prepared bytes, so Swift never touches `KotlinByteArray`. */
val PreparedEvidence.data: NSData
  get() = bytes.toNSData()

/** Whether [failure] is the cancellation of a call. */
fun isCancellation(failure: Throwable): Boolean = failure is CancellationException

/**
 * Throws [failure] back at Swift. `@Throws` hands a Swift error from the token closure back as the
 * error it was; `asError()` would wrap it in a Kotlin one and lose its type.
 */
@Throws(Throwable::class)
fun rethrow(failure: Throwable) {
  throw failure
}

internal fun ByteArray.toNSData(): NSData =
  if (isEmpty()) NSData()
  else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

internal fun NSData.toByteArray(): ByteArray {
  val size = length.toInt()
  val result = ByteArray(size)
  if (size > 0) result.usePinned { memcpy(it.addressOf(0), bytes, length) }
  return result
}
