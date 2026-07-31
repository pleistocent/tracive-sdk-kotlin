import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.androidApplication)
  // No `org.jetbrains.kotlin.android` here. From AGP 9 the Android plugin brings its own Kotlin
  // support, and applying the standalone one on top of it fails the build outright rather than
  // being merely redundant. The `kotlin { }` block below is AGP's.
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
    // placeholder and the `TraciveMode.OAuth(redirectUri = ...)` value cannot drift apart — a
    // mismatch there fails at the very end of the OAuth round trip, which is the most annoying
    // possible time to discover it.
    manifestPlaceholders["traciveRedirectScheme"] = "tracive-sample"
    manifestPlaceholders["traciveRedirectHost"] = "oauth"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
  implementation(project(":tracive-sdk"))

  // The SQLite driver is the host app's to choose, so the SDK does not force one on it. This is the
  // Android one; `SqlDelightCheckCache.Schema` is what it is created with.
  implementation(libs.sqldelight.driver.android)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
