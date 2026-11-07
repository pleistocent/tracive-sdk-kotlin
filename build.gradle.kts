import com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask
import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask

plugins {
  // Declared here without `apply` so every subproject resolves the same plugin version from the
  // version catalog and none of them re-declares one.
  alias(libs.plugins.androidApplication) apply false
  alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
  alias(libs.plugins.androidLibrary) apply false
  alias(libs.plugins.composeCompiler) apply false
  alias(libs.plugins.kotlinMultiplatform) apply false
  alias(libs.plugins.kotlinSerialization) apply false
  alias(libs.plugins.mavenPublish) apply false
  alias(libs.plugins.sqldelight) apply false
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.binaryCompatibilityValidator)
}

group = providers.gradleProperty("tracive.group").get()

version = providers.gradleProperty("tracive.version").get()

/*
 * Binary compatibility.
 *
 * This SDK is consumed by partner apps that upgrade on their own schedule, so an accidental
 * removal or signature change in the public surface is a broken build in someone else's release
 * train. `apiCheck` runs as part of `check`, which means CI fails on an unintended change to the
 * surface and the fix is to run `./gradlew apiDump` and review the diff — the review is the point.
 *
 * KLib validation is on because the surface that matters here is the *common* one: `commonMain` is
 * what the Flutter and Swift bridges compile against, and a JVM-only dump would not see a change
 * confined to the iOS targets. `strictValidation` stays off so a Linux runner, which cannot build
 * the Apple targets at all, infers those targets' ABI from the ones it can build instead of failing
 * the job. The macOS runner in CI is the one that actually checks them.
 *
 * The klib dump covers only the Apple targets, so it is half the story. This plugin wires an Android
 * dump automatically only for a compilation named `release`, which AGP's KMP library plugin does not
 * produce — so `:tracive-sdk` registers the Android dump itself, against `api/tracive-sdk.android
 * .api`. See the comment above `androidApiBuild` in `tracive-sdk/build.gradle.kts`; without it
 * `apiCheck` could not fail on a change to the Android surface at all.
 */
apiValidation {
  // The sample app is an application, not a published artifact. It has no ABI to keep stable.
  ignoredProjects += listOf("samples", "composeApp", "androidApp", "extendedAndroidApp")

  // SQLDelight's generated database code. It is public because the generator has no way to make it
  // anything else, but it is not surface this SDK offers anyone: `SqlDelightCheckCache.Schema`
  // re-exports the one piece a host actually needs, precisely so nobody imports this package. Left
  // in the dump, a SQLDelight upgrade that renames a generated symbol would read as a breaking
  // change to the Tracive SDK and churn a file whose diffs are supposed to mean something.
  ignoredPackages += "id.tracive.sdk.cache.db"

  @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
  klib {
    enabled = true
    strictValidation = false
  }
}

allprojects {
  apply(plugin = rootProject.libs.plugins.ktfmt.get().pluginId)

  // ktfmt Google style, 2-space indent — the repo-wide convention, and the same style the Tracive
  // app and backend repos use. `./gradlew ktfmtFormat` reformats; `ktfmtCheck` (wired into `check`)
  // fails the build on unformatted code.
  ktfmt { googleStyle() }

  // The plugin finds sources through the Kotlin Gradle plugin, which an AGP 9 Android module no
  // longer applies. Without these two, ktfmtCheck there reads no source file and passes.
  listOf("com.android.library", "com.android.application").forEach { android ->
    pluginManager.withPlugin(android) {
      val sources = fileTree("src") { include("**/*.kt") }
      val check = tasks.register<KtfmtCheckTask>("ktfmtCheckSources") { source = sources }
      val format = tasks.register<KtfmtFormatTask>("ktfmtFormatSources") { source = sources }
      tasks.named("ktfmtCheck") { dependsOn(check) }
      tasks.named("ktfmtFormat") { dependsOn(format) }
    }
  }
}
