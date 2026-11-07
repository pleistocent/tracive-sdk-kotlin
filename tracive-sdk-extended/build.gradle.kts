import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKotlinMultiplatformLibrary)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.mavenPublish)
}

group = providers.gradleProperty("tracive.group").get()

version = providers.gradleProperty("tracive.version").get()

kotlin {
  jvmToolchain(17)

  android {
    namespace = "id.tracive.sdk.extended"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
    aarMetadata { minCompileSdk = libs.versions.android.minCompileSdk.get().toInt() }
    withHostTest { isReturnDefaultValues = true }
    compilations.configureEach {
      compileTaskProvider.configure { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    }
  }

  // The binary the Swift package ships. A dynamic framework, so a Swift partner links one copy of
  // the Kotlin runtime however many of its own targets import the package.
  val xcFramework = XCFramework("TraciveReportCore")
  listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
    // A klib stores each source path, which would publish the build machine's home directory.
    target.compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions.freeCompilerArgs.add("-Xklib-relative-path-base=${rootDir.absolutePath}")
      }
    }
    target.binaries.framework {
      baseName = "TraciveReportCore"
      isStatic = false
      xcFramework.add(this)
    }
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":tracive-sdk"))
      implementation(libs.avifkit)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.ktor.client.core)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
    androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
    iosMain.dependencies { implementation(libs.ktor.client.darwin) }
  }
}

mavenPublishing {
  publishToMavenCentral(automaticRelease = false)
  signAllPublications()

  pom {
    name.set("Tracive SDK Extended")
    description.set(
      "Lets a user report a scammer from inside a partner app: evidence compression, upload, " +
        "submit and status."
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
