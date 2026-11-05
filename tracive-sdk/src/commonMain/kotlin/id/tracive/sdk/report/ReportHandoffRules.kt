package id.tracive.sdk.report

import id.tracive.sdk.model.ReportTarget
import id.tracive.sdk.oauth.AuthorizeUrlBuilder
import id.tracive.sdk.platform.ReportHandoff

/**
 * The identifier-only link an older Tracive app accepts.
 *
 * Everything outside the RFC 3986 unreserved set is percent-encoded, so the `+` of a phone number
 * travels as `%2B`: Android's `Uri.getQueryParameter` reads a bare `+` as a space.
 */
internal fun reportLink(urlScheme: String, query: String): String =
  "$urlScheme://report/create?media=" + AuthorizeUrlBuilder.percentEncode(query)

/*
 * The two rules below are iOS behaviour, kept in common code so they run in the JVM test suite as
 * well as on a simulator.
 */

/**
 * iOS cannot tell an old Tracive app from a new one by its "Open in" sheet, which shows even with
 * no receiver installed. An app that accepts the package also registers
 * [ReportTarget.capabilityScheme], so that is asked first. A host missing that scheme from
 * `LSApplicationQueriesSchemes` is told "no" and takes the link, which is the safe direction.
 */
internal fun schemeHandoff(target: ReportTarget, canOpenUrl: (String) -> Boolean): ReportHandoff =
  when {
    canOpenUrl("${target.capabilityScheme}://") -> ReportHandoff.PACKAGE
    canOpenUrl("${target.urlScheme}://") -> ReportHandoff.LINK
    else -> ReportHandoff.APP_NOT_INSTALLED
  }

/**
 * Resolves the "Open in" sheet once. The delegate calls arrive as `willBeginSendingToApplication`,
 * then `didDismissOpenInMenu`, then `didEndSendingToApplication`, and "Save to Files" sends no
 * `willBegin` at all. So `willBegin` decides when it comes, by bundle id, and a dismissal without
 * it means nothing went to the target.
 */
internal class OpenInMenuOutcome(
  private val targetBundleId: String,
  private val onResolved: (delivered: Boolean) -> Unit,
) {

  private var resolved = false

  /** Whether any app has started receiving the file, the target or not. */
  var sendingStarted: Boolean = false
    private set

  fun willBeginSending(application: String?) {
    sendingStarted = true
    resolve(application == targetBundleId)
  }

  fun dismissed() {
    resolve(false)
  }

  private fun resolve(delivered: Boolean) {
    if (resolved) return
    resolved = true
    onResolved(delivered)
  }
}

/**
 * Notices the "Open in" sheet has gone without a word to its delegate. iPadOS takes it down when
 * the device rotates and calls no delegate method at all, so a call waiting on the delegate would
 * never return.
 *
 * Polled. The sheet must be seen gone on two checks in a row, so that a delegate answer already on
 * its way still decides the outcome.
 */
internal class VanishedSheetWatch(
  private val isGone: () -> Boolean,
  private val onVanished: () -> Unit,
) {

  private var goneChecks = 0
  private var fired = false

  fun check() {
    if (fired) return
    goneChecks = if (isGone()) goneChecks + 1 else 0
    if (goneChecks < 2) return
    fired = true
    onVanished()
  }
}
