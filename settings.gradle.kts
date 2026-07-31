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

include(":samples:android-compose")
