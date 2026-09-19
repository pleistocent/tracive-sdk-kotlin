package id.tracive.sdk.sample

import id.tracive.sdk.model.TraciveEnvironment

/**
 * The two builds of the sample, one per Tracive environment: a Gradle product flavor on Android, an
 * Xcode scheme on iOS. Switching environment means building the other one, not editing code.
 *
 * Each flavor installs as its own app. The SDK's token store and check cache are not keyed by
 * environment, so a sandbox grant and sandbox verdicts left in an app that is then rebuilt as
 * production would be sent to production and served as production answers.
 */
internal enum class SampleFlavor(val environment: TraciveEnvironment) {
  SANDBOX(TraciveEnvironment.Sandbox),
  PRODUCTION(TraciveEnvironment.Production);

  companion object {
    /** The flavor an app shell names — `BuildConfig.FLAVOR` on Android, `Info.plist` on iOS. */
    fun named(name: String): SampleFlavor =
      entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: error("No sample flavor named '$name'. Expected 'sandbox' or 'production'.")
  }
}
