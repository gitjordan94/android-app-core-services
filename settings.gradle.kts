pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-app-core-services"

// Published as GROUP:core-services (gradle.properties). The project is named after the artifact so that a
// consumer including this build from source gets `GROUP:core-services` substituted with it automatically.
include(":core-services")
project(":core-services").projectDir = file("library")
