rootProject.name = "tracive-sdk-kotlin"

pluginManagement {
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
  }
}

// `:tracive-sdk` is the published library. The sample is a consumer of it and is deliberately a
// separate Gradle project: it depends on `:tracive-sdk` the way a partner app would, so a change
// that compiles only because it can see the library's internals fails here rather than in a
// partner's build.
include(":tracive-sdk")

// One Compose Multiplatform sample, in three parts. `composeApp` is the sample: the screen, the
// view model and the client wiring, with only key storage and the SQLite driver written per
// platform. `androidApp` is a launcher Activity; `iosApp` is an Xcode project holding the SwiftUI
// shell. The Android and iOS halves cannot be one module — from AGP 9 `com.android.application`
// registers its own `kotlin` extension, so `org.jetbrains.kotlin.multiplatform` cannot be applied
// beside it.
include(":samples:composeApp")

include(":samples:androidApp")
