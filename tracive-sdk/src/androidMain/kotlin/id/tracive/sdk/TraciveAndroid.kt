package id.tracive.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * Holds the application `Context` the Android half of the SDK needs.
 *
 * Three things on Android cannot be done without one: asking the package manager whether the
 * Tracive app is installed, opening a Custom Tab, and reaching the Keystore-backed token store. The
 * SDK's public API takes no `Context` because threading one through every call site would put an
 * Android type into a multiplatform surface — so it is captured once, at process start, by
 * [TraciveInitProvider].
 *
 * Only the *application* context is kept, never an Activity. Holding an Activity in a process-wide
 * object is a leak of the whole view hierarchy.
 *
 * A host that strips the provider (`tools:node="remove"`, or a build that forbids startup
 * providers) must call [initialize] itself, before the first SDK call.
 */
object TraciveAndroid {

  /**
   * `@Volatile` because this is written once on the main thread, during `Application.onCreate`, and
   * read from everywhere else: `Dispatchers.IO` for the Keystore token store, and whatever
   * dispatcher the host's coroutine happens to be on for the presence check and the launcher.
   * Without it there is no happens-before edge, so a background thread may observe null in a process
   * where the SDK is initialised — and [requireContext] would then tell the developer to call
   * [initialize], which they never needed to call.
   */
  @Volatile private var applicationContext: Context? = null

  /**
   * Supplies the SDK's application context.
   *
   * Normally unnecessary — [TraciveInitProvider] does it during app startup. Safe to call more than
   * once; the last non-null value wins.
   */
  fun initialize(context: Context) {
    applicationContext = context.applicationContext ?: context
  }

  /** Whether a context is available. False means [initialize] has not run. */
  val isInitialized: Boolean
    get() = applicationContext != null

  /**
   * The application context.
   *
   * @throws IllegalStateException when the SDK has no context yet, naming the fix. A null return
   *   would push the same failure into the Keystore or the package manager, where the error message
   *   would say nothing about the real cause.
   */
  fun requireContext(): Context =
    applicationContext
      ?: error(
        "The Tracive SDK has no Android Context. Its manifest normally provides one automatically; " +
          "if the host removed TraciveInitProvider, call TraciveAndroid.initialize(context) before " +
          "using the SDK."
      )
}

/**
 * Captures the application context at process start.
 *
 * A no-op `ContentProvider` is the standard mechanism for this — it is what `androidx.startup` is
 * built on, and what any library that has to be ready before the host's first line of code ends up
 * using — because the framework creates every provider before `Application.onCreate` returns, and
 * the SDK's manifest is merged into the host app automatically. The alternative is making every
 * integrator remember an `initialize(context)` call, and the failure mode for forgetting is a crash
 * in a token store rather than a compile error.
 *
 * It is `exported="false"` and answers nothing: it exists purely for [onCreate].
 */
class TraciveInitProvider : ContentProvider() {

  override fun onCreate(): Boolean {
    context?.let { TraciveAndroid.initialize(it) }
    return true
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = 0
}
