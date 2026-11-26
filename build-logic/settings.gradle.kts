// Gradle plugin `io.github.gitjordan94.core-services` of the core-services SDK, published as GROUP:core-services-gradle-plugin.
// A consumer building the SDK from source (submodule) includes this build:
//
//   // settings.gradle.kts
//   pluginManagement { includeBuild("android-app-core-services/build-logic") }
//   includeBuild("android-app-core-services")
//
// and applies `id("io.github.gitjordan94.core-services")` in its application module (see README, Quick Start).

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        // The SDK catalog, so the plugin and the SDK share the Session Replay version.
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "core-services-gradle-plugin"
