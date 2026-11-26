import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.vanniktech.maven.publish)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

val afVersion: String = libs.versions.appsflyer.get()
val ageSignalsVersion: String = libs.versions.ageSignals.get()

android {
    namespace = "app.core.services"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 23

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "SDK_VERSION",
            "\"$version\""
        )

        buildConfigField(
            "String",
            "AF_SDK_VERSION",
            "\"$afVersion\""
        )

        buildConfigField(
            "String",
            "AGE_SIGNALS_SDK_VERSION",
            "\"$ageSignalsVersion\""
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Core
    api(libs.androidx.fragment.ktx)
    implementation(libs.play.app.update.ktx)
    implementation(libs.kotlinx.coroutines.core)

    // Compose
    implementation(libs.androidx.compose.ui)

    // Lifecycle
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Billing
    implementation(libs.billing.ktx)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Google Play Services
    implementation(libs.play.services.ads.identifier)
    implementation(libs.play.services.appset)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.play.age.signals)

    // Analytics
    // Session Replay 1.2.0 depends on analytics-core 1.37.1; keep analytics-android on the same line.
    implementation(libs.amplitude.analytics.android)
    implementation(libs.amplitude.experiment.android)
    // Compose capture also needs the Amplitude Gradle plugin in the app module: `app.core.services`.
    implementation(libs.amplitude.session.replay.plugin)
    api(libs.appsflyer.android.sdk)

    // Database
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    // Firebase
    api(platform(libs.firebase.bom))
    implementation(libs.firebase.config)
    implementation(libs.firebase.analytics)

    // ATT
    implementation(libs.install.referrer)

    // Logging
    implementation(libs.timber)
    implementation(libs.firebase.crashlytics.buildtools)

    // Network
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.content.negotiation)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.logging.interceptor)

    // Testing
    testImplementation(libs.junit)
}

// Maven Central: GROUP, VERSION_NAME and POM_* come from gradle.properties (see README, Publishing).
mavenPublishing {
    coordinates(artifactId = "core-services")
    publishToMavenCentral()
    // Maven Central requires signed artifacts; the key lives in ~/.gradle/gradle.properties
    // (signingInMemoryKey, signingInMemoryKeyPassword). Without it, local publishing still works unsigned.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
}
