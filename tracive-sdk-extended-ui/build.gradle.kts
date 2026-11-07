import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.mavenPublish)
}

group = providers.gradleProperty("tracive.group").get()

version = providers.gradleProperty("tracive.version").get()

android {
  namespace = "id.tracive.sdk.extended.ui"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  resourcePrefix = "tracive_report_"

  defaultConfig {
    minSdk = libs.versions.android.minSdk.get().toInt()
    aarMetadata { minCompileSdk = libs.versions.android.minCompileSdk.get().toInt() }
    consumerProguardFiles("consumer-rules.pro")
  }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }
}

kotlin {
  jvmToolchain(17)
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
  api(project(":tracive-sdk-extended"))

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.datetime)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.browser)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.viewmodel.savedstate)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)

  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.robolectric)
}

mavenPublishing {
  // Empty javadoc: AGP's generator dies on sealed classes ("PermittedSubclasses requires ASM9"),
  // and Maven Central needs the jar to exist, not to have content.
  configure(AndroidSingleVariantLibrary(JavadocJar.Empty(), SourcesJar.Sources(), "release"))
  publishToMavenCentral(automaticRelease = false)
  signAllPublications()

  pom {
    name.set("Tracive SDK Extended UI")
    description.set(
      "Tracive's report form and status screens for Android, inside an Activity any host can start."
    )
    url.set("https://github.com/pleistocent/tracive-sdk-kotlin")
    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
      }
    }
    developers {
      developer {
        id.set("tracive")
        name.set("Tracive")
        email.set("developer@pleistocent.com")
        organization.set("PT Pleistocent Teknologi Nusantara")
        organizationUrl.set("https://tracive.id")
      }
    }
    scm {
      url.set("https://github.com/pleistocent/tracive-sdk-kotlin")
      connection.set("scm:git:https://github.com/pleistocent/tracive-sdk-kotlin.git")
    }
  }
}
