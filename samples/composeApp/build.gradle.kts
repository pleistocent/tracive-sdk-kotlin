plugins {
  // Order matters: the KMP plugin has to register the `kotlin` extension before AGP's KMP library
  // plugin extends it. Applying the Android one alone leaves `kotlin { }` unresolved.
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKotlinMultiplatformLibrary)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
}

/*
 * kotlinx-datetime is pinned back to the version `:tracive-sdk` was compiled against.
 *
 * Compose Multiplatform's material3 depends on kotlinx-datetime 0.7.1, so Gradle's conflict
 * resolution upgrades the SDK's 0.6.2 to it. On a Kotlin/Native target that upgrade is not the
 * harmless bump it would be on the JVM: 0.7.0 turned `kotlinx.datetime.Instant` into a typealias
 * for `kotlin.time.Instant`, so the class symbol the SDK's klib references stops existing. Klib
 * partial linkage does not fail the build for that — it replaces every use with a throw. The result
 * is an app that links cleanly and then dies the first time it renders `CheckResult.fetchedAt`.
 *
 * Forcing 0.6.2 does not remove the incompatibility, it chooses which side absorbs it. The
 * unresolved symbols move into material3's `KotlinxDatetimeCalendarModel` — the DatePicker's
 * calendar. This sample has no DatePicker, so nothing it can reach is affected; add one and it will
 * throw. Watch the link log for `i: <org.jetbrains.compose.material3> ... can not be called`.
 *
 * The real fix is the SDK's own migration to kotlinx-datetime 0.7.x, which the version catalog
 * defers on purpose because `CheckResult` serialises two `Instant` fields and that is a
 * wire-format-adjacent change. Delete this block when that lands.
 */
configurations.all { resolutionStrategy { force(libs.kotlinx.datetime.get().toString()) } }

kotlin {
  jvmToolchain(17)

  android {
    namespace = "id.tracive.sdk.sample.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
  }

  // iosX64 alongside the two current targets so a partner on an Intel Mac can still build the
  // simulator framework, matching what `:tracive-sdk` itself declares.
  listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "TraciveSample"
      // Static: nothing else links against this framework, so it needs no embedding step and no
      // code signature of its own inside the app bundle.
      isStatic = true
    }
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":tracive-sdk"))
      // Explicit, even though `:tracive-sdk` exposes it with `api`, so the version this module
      // resolves sits next to the `force` above rather than only in a dependency report.
      implementation(libs.kotlinx.datetime)

      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.material3)
    }

    // The SQLite driver is the host app's to choose, so the SDK does not force one. Each platform
    // picks its own; `SqlDelightCheckCache.Schema` is what both are created with.
    androidMain.dependencies { implementation(libs.sqldelight.driver.android) }

    iosMain.dependencies { implementation(libs.sqldelight.driver.native) }
  }
}
