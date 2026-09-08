import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.androidApplication)
  // No `org.jetbrains.kotlin.android` here. From AGP 9 the Android plugin brings its own Kotlin
  // support, and applying the standalone one on top of it fails the build outright rather than
  // being merely redundant. The `kotlin { }` block below is AGP's.
  //
  // The Compose compiler *is* needed, even though this module declares no `@Composable` of its
  // own. `setContent { host.Content() }` passes a `@Composable` lambda, and without the plugin
  // that lambda is compiled as an ordinary one — no `$composer` parameter — which builds cleanly
  // and then dies at launch with `NoSuchMethodError: setContent$default`.
  alias(libs.plugins.composeCompiler)
}

android {
  namespace = "id.tracive.sdk.sample"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "id.tracive.sdk.sample"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.compileSdk.get().toInt()
    versionCode = 1
    versionName = "0.1.0"

    // The redirect this sample registers in its manifest. Kept in one place so the manifest
    // placeholder and `TraciveSample.REDIRECT_URI` cannot drift apart — a mismatch there fails at
    // the very end of the OAuth round trip, which is the most annoying possible time to discover
    // it. The iOS shell's `Info.plist` carries the same pair.
    //
    // TODO(partner): your own scheme and host, and keep TraciveSample.REDIRECT_URI in step. Any
    // app on the device may declare a custom scheme, so prefer an https App Link if you control a
    // domain — see docs/oauth.md.
    manifestPlaceholders["traciveRedirectScheme"] = "tracive-sample"
    manifestPlaceholders["traciveRedirectHost"] = "oauth"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
  // The whole sample. This module is only a launcher: an Activity, a manifest and the redirect
  // plumbing that has to live in an Android component.
  implementation(project(":samples:composeApp"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)

  // The runtime the `setContent` lambda above compiles against. `:samples:composeApp` brings
  // Compose Multiplatform's own artifacts, which on Android resolve to these same androidx ones.
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.runtime)
}
