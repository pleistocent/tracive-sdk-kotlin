import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKotlinMultiplatformLibrary)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.sqldelight)
  `maven-publish`
}

group = providers.gradleProperty("tracive.group").get()

version = providers.gradleProperty("tracive.version").get()

kotlin {
  jvmToolchain(17)

  android {
    namespace = "id.tracive.sdk"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()

    // Runs `commonTest` (and any `androidHostTest`) on the JVM. Without it the KMP library plugin
    // treats `src/commonTest` as an orphaned source set and warns, and the cache tests would only
    // ever run on an Apple host — which would make Linux CI green while testing nothing.
    withHostTest {}

    compilations.configureEach {
      compileTaskProvider.configure { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    }
  }

  // iosX64 is kept alongside the two current targets so a partner still on an Intel Mac can build
  // the simulator framework. It costs one more compile task and nothing else.
  iosX64()
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain.dependencies {
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      api(libs.kotlinx.datetime)
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.contentNegotiation)
      implementation(libs.ktor.serialization.json)
      // `api`, not `implementation`: `SqlDelightCheckCache` takes a `SqlDriver` in its constructor,
      // so a host app that builds one has to see the type. Hiding it would make the cache
      // unconstructible from outside this module.
      api(libs.sqldelight.runtime)
    }

    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }

    androidMain.dependencies {
      implementation(libs.ktor.client.okhttp)
      implementation(libs.kotlinx.coroutines.android)
      // Custom Tabs. RFC 8252 and Android App Links both rule out an in-app WebView for the
      // authorization step, so this is a functional dependency, not a convenience.
      api(libs.androidx.browser)
      implementation(libs.sqldelight.driver.android)
    }

    iosMain.dependencies {
      implementation(libs.ktor.client.darwin)
      implementation(libs.sqldelight.driver.native)
    }
  }
}

sqldelight {
  databases {
    create("TraciveCacheDatabase") {
      packageName.set("id.tracive.sdk.cache.db")
      // No migrations directory yet, and there will not be one. The cache is disposable: a schema
      // change bumps `CachePolicy.SCHEMA_VERSION` and the store drops every row rather than
      // migrating it, because the rows are encrypted answers that can be re-fetched. Migrating them
      // would mean decrypting under an old key and re-sealing under a new one — real risk, for
      // data whose whole purpose is to be cheap to lose.
      verifyMigrations.set(false)
    }
  }
}

publishing {
  publications.withType<MavenPublication>().configureEach {
    pom {
      name.set("Tracive SDK")
      description.set(
        "Kotlin Multiplatform client for the Tracive scammer-check Open API (phase 1, OAuth mode)."
      )
      url.set("https://github.com/pleistocent/tracive-sdk-kotlin")
      licenses {
        license {
          name.set("The Apache License, Version 2.0")
          url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        }
      }
      scm {
        url.set("https://github.com/pleistocent/tracive-sdk-kotlin")
        connection.set("scm:git:https://github.com/pleistocent/tracive-sdk-kotlin.git")
      }
    }
  }
}
