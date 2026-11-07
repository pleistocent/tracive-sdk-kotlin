import java.net.URI
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.composeCompiler)
}

val localProperties =
  Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
  }

/**
 * `tracive.sample.<name>` from the git-ignored `local.properties`, which outranks every default.
 */
fun sample(name: String): String? =
  localProperties.getProperty("tracive.sample.$name")?.trim()?.takeIf { it.isNotEmpty() }

fun javaString(value: String?): String = value?.let { "\"$it\"" } ?: "null"

// TODO(partner): your own sandbox redirect, registered on your sandbox client id.
val redirectUri = URI(sample("redirectUri") ?: "tracive-extended-sample-sandbox://oauth/callback")
val baseUrl = sample("baseUrl")
val traciveAppId = sample("applicationId")
val traciveAppScheme = sample("appScheme")

check((traciveAppId == null) == (traciveAppScheme == null)) {
  "Set tracive.sample.applicationId and tracive.sample.appScheme together: both name one build."
}

android {
  namespace = "id.tracive.sdk.extended.sample"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    // The token store is not keyed by environment, so a build pointed elsewhere installs beside the
    // Sandbox one.
    applicationId = "id.tracive.sdk.extended.sample" + if (baseUrl == null) ".sandbox" else ".local"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.compileSdk.get().toInt()
    versionCode = 1
    versionName = providers.gradleProperty("tracive.version").get()

    manifestPlaceholders +=
      mapOf(
        "sampleLabel" to if (baseUrl == null) "Reports Sandbox" else "Reports Local",
        "traciveRedirectScheme" to redirectUri.scheme,
        "traciveRedirectHost" to redirectUri.host,
        "traciveAppPackage" to (traciveAppId ?: "com.pleistocent.tracive.sandbox"),
      )

    // TODO(partner): the client id Tracive issued you in sandbox, with the redirect above on it.
    val clientId = sample("clientId") ?: "REPLACE_WITH_YOUR_SANDBOX_CLIENT_ID"
    buildConfigField("String", "TRACIVE_CLIENT_ID", javaString(clientId))
    buildConfigField("String", "TRACIVE_REDIRECT_URI", javaString(redirectUri.toString()))
    buildConfigField("String", "TRACIVE_BASE_URL", javaString(baseUrl))
    buildConfigField(
      "String",
      "TRACIVE_AUTHORIZE_ENDPOINT",
      javaString(sample("authorizeEndpoint")),
    )
    buildConfigField("String", "TRACIVE_APP_ID", javaString(traciveAppId))
    buildConfigField("String", "TRACIVE_APP_SCHEME", javaString(traciveAppScheme))
  }

  buildFeatures {
    buildConfig = true
    compose = true
  }

  // The well-known Android debug identity, committed so every machine signs the sample alike. Play
  // rejects it on upload. TODO(partner): a release keystore of your own, outside version control.
  signingConfigs {
    getByName("debug") {
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
    // Dates in the Tracive SDKs read java.time, which API 24 and 25 do not have without this.
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
  implementation(project(":tracive-sdk-extended-ui"))

  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.datetime)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.material3)

  coreLibraryDesugaring(libs.desugar.jdk.libs)
}
