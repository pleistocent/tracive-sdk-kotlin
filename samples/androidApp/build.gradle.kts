import com.android.build.api.dsl.ApplicationProductFlavor
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

/**
 * A flavor's redirect, written once for the manifest's intent filter and for
 * `BuildConfig.TRACIVE_REDIRECT_URI`, which is what the SDK is configured with. A mismatch between
 * the two fails at the very end of the OAuth round trip, the most annoying time to discover it.
 *
 * A private-use scheme, which RFC 8252 permits for an app that controls no web domain. Any app on
 * the device may declare one, so prefer an https App Link if you control a domain — see
 * docs/oauth.md.
 */
fun ApplicationProductFlavor.redirect(scheme: String, host: String) {
  manifestPlaceholders["traciveRedirectScheme"] = scheme
  manifestPlaceholders["traciveRedirectHost"] = host
  buildConfigField("String", "TRACIVE_REDIRECT_URI", "\"$scheme://$host/callback\"")
}

android {
  namespace = "id.tracive.sdk.sample"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "id.tracive.sdk.sample"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.compileSdk.get().toInt()
    versionCode = 1
    versionName = "0.2.1"
  }

  buildFeatures { buildConfig = true }

  // One flavor per Tracive environment: `installSandboxDebug` or `installProductionDebug`, or the
  // Build Variants panel in Android Studio. `SampleFlavor` in the shared module says why each one
  // installs as its own app.
  //
  // Their own app means their own redirect scheme too. Two apps declaring one custom scheme leave
  // the OS to choose which of them receives the authorization code.
  flavorDimensions += "environment"
  productFlavors {
    create("sandbox") {
      dimension = "environment"
      isDefault = true
      applicationIdSuffix = ".sandbox"
      // TODO(partner): your own sandbox redirect, registered on your sandbox client id.
      redirect(scheme = "tracive-sample-sandbox", host = "oauth")
    }
    create("production") {
      dimension = "environment"
      // TODO(partner): your own production redirect, registered on your production client id.
      redirect(scheme = "tracive-sample", host = "oauth")
    }
  }

  // Checked in rather than left to the debug keystore AGP generates per machine in
  // `~/.android/debug.keystore` (`%USERPROFILE%\.android` on Windows). That one differs for every
  // developer, so an APK built on the Mac will not install over one built on the Windows box —
  // `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, which reads as a broken build rather than a different
  // signer. Committed, the sample has one certificate everywhere, and one SHA-256 to hand to
  // Tracive if you ever move the redirect from a private-use scheme to an App Link:
  // F1:54:F3:D6:80:9E:B1:79:00:91:6A:33:DF:78:39:4A:A8:AD:C5:67:04:44:ED:C0:D8:61:37:9E:9D:BA:F9:F1
  //
  // Committable only because it is the well-known Android debug identity — alias
  // `androiddebugkey`, password `android` — which Play rejects on upload. `.gitignore` still blocks
  // every other keystore in the tree; this file is its single negation.
  //
  // TODO(partner): a release keystore of your own, kept outside version control and read from
  // `local.properties` or an environment variable.
  signingConfigs {
    getByName("debug") {
      // Resolved against this module rather than the working directory, so the path is the same
      // whether Gradle was invoked from the repo root or from here, on either OS.
      storeFile = layout.projectDirectory.file("debug.keystore").asFile
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
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
