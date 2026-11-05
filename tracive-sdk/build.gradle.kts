import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKotlinMultiplatformLibrary)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.sqldelight)
  alias(libs.plugins.mavenPublish)
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
    //
    // Default values let a host test drive AndroidReportFormLauncher.prepare() through a
    // ContextWrapper whose framework calls answer null instead of throwing "Stub!".
    withHostTest { isReturnDefaultValues = true }

    // The FileProvider's paths XML is a resource; the KMP library plugin has resources off.
    androidResources { enable = true }

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
      // A scripted engine, so `KtorTransport` itself can be driven without a socket. Everything
      // above the transport is tested against `FakeHttpTransport`; this is for the transport.
      implementation(libs.ktor.client.mock)
    }

    androidMain.dependencies {
      implementation(libs.ktor.client.okhttp)
      implementation(libs.kotlinx.coroutines.android)
      // Custom Tabs. RFC 8252 and Android App Links both rule out an in-app WebView for the
      // authorization step, so this is a functional dependency, not a convenience.
      api(libs.androidx.browser)
      // FileProvider, for the report package. Already on the classpath through androidx.browser
      // at this version; declared so it stops depending on that.
      implementation(libs.androidx.core)
      implementation(libs.sqldelight.driver.android)
    }

    iosMain.dependencies {
      implementation(libs.ktor.client.darwin)
      implementation(libs.sqldelight.driver.native)
    }
  }
}

/*
 * The Android half of the public surface, pinned.
 *
 * The binary-compatibility-validator plugin wires itself up automatically for a JVM target, and for
 * an Android target it looks for a compilation named `release` — which is what the old
 * `com.android.library` plugin produces. This module uses AGP's KMP library plugin, whose
 * compilations are `main` and `hostTest`, so BCV matched nothing and `apiCheck` covered only the
 * Kotlin/Native klib ABI. `TraciveAndroid`, `TraciveInitProvider`, `AndroidAppPresenceChecker`,
 * `AndroidAuthorizeLauncher` and the Android `SecureTokenStore()` were in no dump at all, so the CI
 * step named "Check public API surface" could not fail on a change to the half of this SDK most
 * partners integrate against. A check that cannot fail is worse than no check: it reads as coverage.
 *
 * The three tasks below hand BCV's own dumper the Android `main` compilation and compare the result
 * against `api/tracive-sdk.android.api`, so the Android surface is checked in the same format, with
 * the same `ignoredPackages`, and `./gradlew apiDump` still regenerates everything.
 */
val androidApiFileName = "tracive-sdk.android.api"

val androidMainClasses =
  kotlin.targets.getByName("android").compilations.getByName("main").output.classesDirs

val androidApiBuild =
  tasks.register<kotlinx.validation.KotlinApiBuildTask>("androidApiBuild") {
    description = "Dumps the public API of the Android target. Complementary to androidApiCheck."
    // Only the compiled output, matching how BCV treats an Android compilation: the dependency
    // classpath is an AAR-shaped mixture that its JVM reader cannot always resolve.
    inputClassesDirs.from(androidMainClasses)
    outputApiFile.set(layout.buildDirectory.file("api/android/$androidApiFileName"))
    runtimeClasspath.from(configurations.named("bcv-rt-jvm-cp-resolver"))
  }

val androidApiCheck =
  tasks.register<kotlinx.validation.KotlinApiCompareTask>("androidApiCheck") {
    group = "verification"
    description = "Checks the Android target's public API against api/$androidApiFileName."
    projectApiFile.set(layout.projectDirectory.file("api/$androidApiFileName"))
    generatedApiFile.set(androidApiBuild.flatMap { it.outputApiFile })
    dependsOn(androidApiBuild)
  }

val androidApiDump =
  tasks.register<Copy>("androidApiDump") {
    description = "Overwrites api/$androidApiFileName with the Android target's current surface."
    from(androidApiBuild.flatMap { it.outputApiFile })
    into(layout.projectDirectory.dir("api"))
  }

tasks.named("apiCheck") { dependsOn(androidApiCheck) }

tasks.named("apiDump") { dependsOn(androidApiDump) }

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

mavenPublishing {
  // Uploads and waits for Central to validate the deployment, but leaves Publish to a person: a
  // version on Maven Central can never be deleted or replaced.
  publishToMavenCentral(automaticRelease = false)
  signAllPublications()

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
    // Maven Central rejects a POM without at least one developer.
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
