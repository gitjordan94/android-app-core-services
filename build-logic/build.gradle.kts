import java.util.Properties

plugins {
    `kotlin-dsl`
    alias(libs.plugins.vanniktech.maven.publish)
}

// Coordinates and version are the SDK's (../gradle.properties): plugin X.Y.Z goes with SDK X.Y.Z.
val sdkProperties = Properties().apply {
    rootDir.resolve("../gradle.properties").inputStream().use(::load)
}
group = sdkProperties.getProperty("GROUP")
version = sdkProperties.getProperty("VERSION_NAME")

dependencies {
    // Implementation of `com.amplitude.session-replay`. Runtime only: CoreServicesPlugin applies it by
    // id, so no Amplitude (or newer kotlin-stdlib) classes end up on this build's compile classpath.
    // It does not depend on AGP; it uses the AGP the consumer's app module already has.
    runtimeOnly(libs.amplitude.session.replay.gradle.plugin)
}

// The Session Replay version CoreServicesPlugin aligns the app to. Generated because the catalog is not
// available at plugin runtime.
val generateProperties by tasks.registering(WriteProperties::class) {
    destinationFile = layout.buildDirectory.file("generated/resources/app-core-services-app.properties")
    property("sessionReplayVersion", libs.versions.amplitudeSessionReplay)
}

sourceSets.main {
    resources.srcDir(generateProperties.map { it.destinationFile.get().asFile.parentFile })
}

gradlePlugin {
    plugins {
        create("appCoreServices") {
            // Inside the Maven Central namespace: the plugin marker is published as <id>:<id>.gradle.plugin.
            id = "io.github.gitjordan94.core-services"
            implementationClass = "app.core.services.gradle.CoreServicesPlugin"
        }
    }
}

// Maven Central. Same coordinates and POM as the SDK (../gradle.properties): this build does not read that
// file as its own properties, so it is passed explicitly.
mavenPublishing {
    coordinates(artifactId = "core-services-gradle-plugin")
    publishToMavenCentral()
    // Maven Central requires signed artifacts; the key lives in ~/.gradle/gradle.properties
    // (signingInMemoryKey, signingInMemoryKeyPassword). Without it, local publishing still works unsigned.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    pom {
        fun sdk(key: String): String = sdkProperties.getProperty(key)
        name = "core-services-gradle-plugin"
        description = "Gradle plugin of core-services: Amplitude Session Replay setup for the application module."
        inceptionYear = sdk("POM_INCEPTION_YEAR")
        url = sdk("POM_URL")
        licenses {
            license {
                name = sdk("POM_LICENSE_NAME")
                url = sdk("POM_LICENSE_URL")
                distribution = sdk("POM_LICENSE_DIST")
            }
        }
        developers {
            developer {
                id = sdk("POM_DEVELOPER_ID")
                name = sdk("POM_DEVELOPER_NAME")
                url = sdk("POM_DEVELOPER_URL")
            }
        }
        scm {
            url = sdk("POM_SCM_URL")
            connection = sdk("POM_SCM_CONNECTION")
            developerConnection = sdk("POM_SCM_DEV_CONNECTION")
        }
    }
}
