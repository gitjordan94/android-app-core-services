import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val afVersion = "7.0.1"
val ageSignalsVersion = "0.0.3"

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
            "\"1.6.1\""
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

//noinspection UseTomlInstead
dependencies {
    // Core
    api("androidx.fragment:fragment-ktx:1.8.9")
    implementation("com.google.android.play:app-update-ktx:2.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Compose
    implementation("androidx.compose.ui:ui:1.11.2")

    // Lifecycle
    val lifecycleVersion = "2.10.0"
    implementation("androidx.lifecycle:lifecycle-common:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-process:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")

    // Billing
    implementation("com.android.billingclient:billing-ktx:9.1.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Google Play Services
    implementation("com.google.android.gms:play-services-ads-identifier:18.3.0")
    implementation("com.google.android.gms:play-services-appset:16.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    implementation("com.google.android.play:age-signals:$ageSignalsVersion")

    // Analytics
    implementation("com.amplitude:analytics-android:1.30.0")
    implementation("com.amplitude:experiment-android-client:1.16.1")
    implementation("com.amplitude:plugin-session-replay-android:0.27.0")
    api("com.appsflyer:af-android-sdk:$afVersion")

    // Database
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    // Firebase
    api(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-config")
    implementation("com.google.firebase:firebase-analytics")

    // ATT
    implementation("com.android.installreferrer:installreferrer:2.2")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.google.firebase:firebase-crashlytics-buildtools:3.0.7")

    // Network
    val ktorVersion = "3.5.1"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-android:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.4.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
}