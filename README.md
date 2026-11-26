# android-app-core-services

An Android library that unifies analytics, remote config, billing, and attribution into a single SDK.

**SDK version:** `1.6.1`  
**Min SDK:** 23 · **Compile SDK:** 37 · **JVM target:** 17

---

## Table of Contents

- [Architecture](#architecture)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Modules](#modules)
  - [Analytics](#analytics)
  - [Remote Config](#remote-config)
  - [Billing](#billing)
  - [Deep Links](#deep-links)
  - [Age Signals](#age-signals)
  - [Session Replay](#session-replay)
- [Bootstrap](#bootstrap)
- [Consent](#consent)
- [Testing](#testing)
- [Dependencies](#dependencies)

---

## Architecture

The entry point is the `AppCoreServices` interface. The only way to get an instance is via `AppCoreServices.configure(configuration)`. After creation the singleton is accessible through `AppCoreServices.sharedInstance`.

```
AppCoreServices (interface)
├── analytics          ← Amplitude + AppsFlyer + Firebase
├── remoteConfig       ← Amplitude Experiment
├── billingClient      ← Google Play Billing
├── deepLinkManager    ← AppsFlyer One Link
└── bootstrapFlow      ← StateFlow<BootstrapResult?>
```

The internal implementation (`DefaultAppCoreServices`) is hidden; only the interface is visible outside the library.

---

## Quick Start

### 0. Add the SDK and its Gradle plugin

The SDK is published as `io.github.gitjordan94:core-services`; its Gradle plugin `io.github.gitjordan94.core-services` as
`io.github.gitjordan94:core-services-gradle-plugin`. Both share one version (`VERSION_NAME` in `gradle.properties`).
The app's build files are the same whether the SDK comes from Maven or from source:

```kotlin
// gradle/libs.versions.toml
[versions]
coreServices = "1.6.1"
[libraries]
core-services = { module = "io.github.gitjordan94:core-services", version.ref = "coreServices" }
[plugins]
core-services = { id = "io.github.gitjordan94.core-services", version.ref = "coreServices" }

// app/build.gradle.kts
plugins {
    id("com.android.application")
    alias(libs.plugins.core.services)
}
dependencies {
    implementation(libs.core.services)
}
```

Only `settings.gradle.kts` differs:

```kotlin
// From Maven: add the repository the SDK is published to, for plugins and for dependencies.
pluginManagement { repositories { maven("<repository>") } }
dependencyResolutionManagement { repositories { maven("<repository>") } }

// From source (git submodule): Gradle substitutes io.github.gitjordan94:core-services with this build's module,
// the version in the catalog is then ignored.
pluginManagement { includeBuild("android-app-core-services/build-logic") }
includeBuild("android-app-core-services")
```

From source, the SDK build must use **the same AGP version as the app** (`agp` in
`gradle/libs.versions.toml` here): Android does not allow two AGP versions in one build.

`io.github.gitjordan94.core-services` must be applied to the application module (it fails the build in a library module). It
applies the Amplitude Session Replay Gradle plugin (Compose capture) and aligns every Session Replay
artifact to the version the SDK was built with.

### 1. Configure and initialize the SDK

```kotlin
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val config = AppCoreServices.Configuration(
            context = this,
            appsFlyerDevKey = "YOUR_APPSFLYER_DEV_KEY",
            amplitudeConfig = AmplitudeConfig(apiKey = "YOUR_AMPLITUDE_KEY"),
            billingConfig = BillingConfig(
                secretKey = "YOUR_SECRET_KEY",
                iv = "YOUR_IV",
            ),
            remoteConfigParameters = RemoteConfigParameters(
                amplitudeDeploymentKey = "YOUR_DEPLOYMENT_KEY"
            ).param("feature_flag_name", defaultValue = "control"),
            dataStoreFileName = "app_prefs",
        )

        val sdk = AppCoreServices.configure(config)

        // Set consent before start() if it is already known
        sdk.setConsent(Consent.GRANTED_ALL)

        sdk.start(this)
    }
}
```

### 2. Bootstrap on the splash screen

```kotlin
viewModelScope.launch {
    val result = AppCoreServices.sharedInstance.bootstrap()
    // result.attribution, result.purchases, result.storeCountry, result.isFirstLaunch
}
```

Or reactively via Flow:

```kotlin
AppCoreServices.sharedInstance.bootstrapFlow
    .filterNotNull()
    .collect { result ->
        // navigate to next screen
    }
```

### 3. Set an external user ID after login

```kotlin
AppCoreServices.sharedInstance.setExternalUserId(userId)

// On logout:
AppCoreServices.sharedInstance.setExternalUserId(null)
```

---

## Configuration

### `AppCoreServices.Configuration`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `context` | `Context` | ✅ | Application context |
| `appsFlyerDevKey` | `String` | ✅ | AppsFlyer dev key |
| `amplitudeConfig` | `AmplitudeConfig` | ✅ | Amplitude configuration |
| `billingConfig` | `BillingConfig` | ✅ | Google Play Billing configuration |
| `remoteConfigParameters` | `RemoteConfigParameters` | ✅ | Remote config flag definitions |
| `attributionServerConfig` | `AttributionServerConfig?` | — | Custom attribution server (optional) |
| `sessionReplayConfig` | `SessionReplayConfig` | — | Session Replay settings (default: 1% sample rate, MEDIUM mask) |
| `dataStoreFileName` | `String` | ✅ | DataStore file name for SDK state persistence |

### `AmplitudeConfig`

```kotlin
AmplitudeConfig(
    apiKey = "YOUR_KEY",
    optOut = false, // true disables event delivery
)
```

### `BillingConfig`

```kotlin
BillingConfig(
    secretKey = "YOUR_SECRET_KEY",             // AES key for purchase verification
    iv = "YOUR_IV",                             // AES initialization vector
    consumedInAppPurchasesTimeMillis = null,    // cache TTL for one-time purchases
    acknowledgePurchases = true,                // auto-acknowledge purchases
)
```

### `RemoteConfigParameters`

```kotlin
RemoteConfigParameters(amplitudeDeploymentKey = "KEY")
    .param("my_flag", defaultValue = "control")
    .param("experiment_payload", defaultPayload = "{}", isStickyBucketed = true)
```

### `AttributionServerConfig` (optional)

```kotlin
AttributionServerConfig(
    token = "SERVER_TOKEN",
    externalAuthorization = false,
    serverUrl = "https://attribution.example.com",
    environment = AttributionServerConfig.Environment.RELEASE,
)
```

---

## Modules

### Analytics

```kotlin
val analytics = AppCoreServices.sharedInstance.analytics

analytics.logEvent("purchase_complete", mapOf("product_id" to "pro_monthly"))

analytics.setUserProperties(mapOf("subscription_status" to "active"))

// Set properties only once (no-op on subsequent calls)
analytics.setUserPropertiesOnce(mapOf("signup_date" to "2025-01-01"))
```

Events are dispatched to **Amplitude**, **AppsFlyer**, and **Firebase Analytics** simultaneously.

### Remote Config

```kotlin
val config = AppCoreServices.sharedInstance.remoteConfig

val stringVal: String?  = config.getString("my_flag")
val boolVal: Boolean?   = config.getBoolean("feature_enabled")
val longVal: Long?      = config.getLong("max_retries")
val payload: Any?       = config.getPayload("complex_config")

// Operator access
val value: RemoteConfigValue = config["my_flag"]
```

Flags are fetched automatically during `bootstrap()`. A background re-fetch runs if attribution data changes after the initial fetch.

### Billing

```kotlin
val billing = AppCoreServices.sharedInstance.billingClient

// Fetch products
val products = billing.getProducts(listOf("pro_monthly", "pro_annual"))

// Purchase (simple)
val purchase = billing.purchase(activity, "pro_monthly")

// Purchase a specific offer
val purchase = billing.purchase(activity, PurchaseRequest(productId, offerToken))

// Get active entitlements
val purchases: Purchases = billing.getPurchases()

// Reactive entitlements stream
billing.getPurchasesFlow().collect { purchases -> ... }

// Restore purchases
val restored = billing.restorePurchases()

// Show Google Play in-app messages (e.g. expired card notice)
billing.showInAppMessages(activity)

// Store country
val country: String? = billing.getStoreCountry() // "US", "GB", etc.
```

### Deep Links

```kotlin
AppCoreServices.sharedInstance.deepLinkManager.setDeepLinkListener { deepLink ->
    // handle incoming deep link
}
```

Backed by AppsFlyer One Link.

### Age Signals

A standalone component for Google Play Age Signals API integration:

```kotlin
val ageSignalsManager = AgeSignalsManager.Builder(context)
    .analytics(AppCoreServices.sharedInstance.analytics)
    .build()

ageSignalsManager.requestAgeSignals(object : AgeSignalsListener {
    override fun onSuccess(result: AgeSignalsResult) { ... }
    override fun onFailure(error: AgeSignalsException) { ... }
})
```

### Session Replay

Amplitude Session Replay 1.2.0. Configured via `SessionReplayConfig` inside `AppCoreServices.Configuration`:

```kotlin
SessionReplayConfig(
    autoStart = false,
    sampleRate = 0.05,                               // Double: 5% of sessions
    maskLevel = SessionReplayConfig.MaskLevel.MEDIUM, // LIGHT / MEDIUM / CONSERVATIVE
    enableRemoteConfig = true,                        // allow Amplitude Remote Config to override settings
)

// For local testing (100% of sessions, remote config disabled):
SessionReplayConfig.testingConfig()
```

Recording starts only while analytics consent is granted: withdrawing it (`setConsent`) disables Session
Replay. With `autoStart = true` it is enabled again as soon as consent is granted.

Runtime control via `analytics` (implements `SessionReplayController`):

```kotlin
analytics.enableSessionReplay()
analytics.disableSessionReplay()
```

Masking in Compose (`app.core.services.amplitude.sessionreplay.compose`):

| Modifier | Effect |
|---|---|
| `Modifier.sessionReplayMask()` | Masks text and inputs of the subtree |
| `Modifier.sessionReplayUnmask()` | Shows the subtree unmasked |
| `Modifier.sessionReplayBlock()` | Records the subtree as an opaque block; cannot be unmasked below |

Requirements in the app:

- the `io.github.gitjordan94.core-services` Gradle plugin (see [Quick Start](#0-add-the-sdk-and-its-gradle-plugin)) — without it
  Compose screens are not captured;
- Compose UI 1.9 or newer (the version Amplitude supports), AGP 8.5+, JDK 17+ to run Gradle.

---

## Bootstrap

`bootstrap()` is the central data-loading step for app startup. It runs the following sources in parallel:

| Data source | Timeout |
|---|---|
| Attribution (AppsFlyer / Install Referrer) | 6 500 ms (overall) |
| Initial attribution | 3 000 ms |
| Remote Config — initial fetch | within overall timeout |
| Remote Config — attribution-enriched fetch | background |
| Active purchases | within overall timeout |
| Store country | within overall timeout |
| Device info | within overall timeout |

### `BootstrapResult`

```kotlin
data class BootstrapResult(
    val attribution: Attribution,  // install media source
    val storeCountry: String?,     // ISO 3166-1 alpha-2 ("US", "DE", ...)
    val purchases: Purchases?,     // user's active entitlements
    val isFirstLaunch: Boolean,    // true on first ever app launch
)
```

### `bootstrapFlow`

`StateFlow<BootstrapResult?>` emits progressively:

1. `null` — loading has not started or is in progress
2. `BootstrapResult` — once the initial data (without full attribution) is ready
3. Updated `BootstrapResult` — once feature flags are refreshed with attribution data (emitted in background)

Collect this flow on the splash screen to decide when to proceed.

---

## Consent

```kotlin
// Preset values
Consent.GRANTED_ALL  // all signals granted
Consent.DENIED_ALL   // all signals denied

// Fine-grained
val consent = Consent(
    analyticsStorage  = true,
    adPersonalization = true,
    adStorage         = true,
    adUserData        = true,
    isUserSubjectToGDPR = true, // null (default) = unknown
)

// Call before start() to ensure consent is respected from the very first SDK event
AppCoreServices.sharedInstance.setConsent(consent)
```

Consent is applied to AppsFlyer, Amplitude, and Firebase Analytics simultaneously. Calling `setConsent` after `start()` still applies immediately, but some early events may already have been sent.

`isUserSubjectToGDPR` goes to AppsFlyer as is (with `null` AppsFlyer relies on TCF data).

Consent also goes to the attribution server: inside the install request when it is set before the install is sent (no answer yet — no `consent` in the request), and through `POST /attribute/consent` for every later value the server has not seen — an answer given after the install, the first answer after an update from an SDK without consent, a change in settings. Failed requests are retried on the next launch and foreground; repeating the same value sends nothing. Call `setConsent` only with the user's answer, never with a placeholder while it is pending.

---

## Testing

The library ships test doubles out of the box:

```kotlin
val testServices = TestAppCoreServices(
    analytics      = NoOpAnalytics,
    remoteConfig   = FakeRemoteConfig(),
    billingClient  = FakeBillingClient(...),
    deepLinkManager = NoOpDeepLinkManager,
)

// Override the bootstrap result
testServices.bootstrapResult = BootstrapResult(
    attribution  = Attribution(),
    isFirstLaunch = false,
)

// Fake Remote Config with arbitrary values
val fakeConfig = FakeRemoteConfig()
fakeConfig["my_flag"] = FakeRemoteConfigValue("treatment")
```

---

## Building and publishing

The repository is a standalone Gradle build (its own wrapper, `gradle/libs.versions.toml` as `libs`).
Coordinates, version and POM metadata are in `gradle.properties` (`GROUP`, `VERSION_NAME`, `POM_*`).

```bash
./gradlew :core-services:testDebugUnitTest
./gradlew :core-services:publishToMavenLocal          # io.github.gitjordan94:core-services
./gradlew -p build-logic publishToMavenLocal          # io.github.gitjordan94:core-services-gradle-plugin + plugin marker
```

Inside a consumer that includes it from source, address its tasks through the build name, e.g.
`./gradlew :android-app-core-services:core-services:testDebugUnitTest`.

### Maven Central

Published with [`com.vanniktech.maven.publish`](https://vanniktech.github.io/gradle-maven-publish-plugin/) to
the Central Portal under the namespace `io.github.gitjordan94`.

One-time setup (per machine or CI), in `~/.gradle/gradle.properties` — never in the repository:

```properties
mavenCentralUsername=<Central Portal user token: username>
mavenCentralPassword=<Central Portal user token: password>
signingInMemoryKey=<ASCII-armored private GPG key, one line>
signingInMemoryKeyPassword=<key password>
```

- User token: central.sonatype.com → Account → Generate User Token.
- GPG key: `gpg --full-generate-key`, publish the public key with
  `gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>`, export the private one with
  `gpg --export-secret-keys --armor <KEYID>`.

Without `signingInMemoryKey` artifacts are not signed: fine for `publishToMavenLocal`, rejected by Maven Central.

Release from CI (`.github/workflows/release.yaml`, secrets in the GitHub environment `release`:
`CENTRAL_PORTAL_USERNAME`, `CENTRAL_PORTAL_PASSWORD`, `ARTIFACT_SIGNING_PRIVATE_KEY`,
`ARTIFACT_SIGNING_PRIVATE_KEY_PASSWORD`):

1. Bump `VERSION_NAME` in `gradle.properties` and merge (a version on Maven Central can never be replaced or deleted).
2. `git tag v<VERSION_NAME> && git push origin v<VERSION_NAME>` — the workflow checks that the tag matches
   `VERSION_NAME`, runs the unit tests and uploads both artifacts.
3. central.sonatype.com → Deployments: check validation, press **Publish**.

Release from a machine (secrets in `~/.gradle/gradle.properties`, see above):

1. Bump `VERSION_NAME` in `gradle.properties`.
2. Upload both artifacts:
   ```bash
   ./gradlew :core-services:publishToMavenCentral
   ./gradlew -p build-logic publishToMavenCentral
   ```
3. central.sonatype.com → Deployments: check validation, press **Publish**. Artifacts reach Maven Central
   in about 10–30 minutes.

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| AppsFlyer Android SDK | 6.18.0 | Attribution, deep links, analytics |
| Amplitude Analytics Android | 1.37.2 | Event analytics |
| Amplitude Experiment Android | 1.16.1 | Remote Config / A-B testing |
| Amplitude Session Replay | 1.2.0 | Session recording (+ Gradle plugin via `io.github.gitjordan94.core-services`) |
| Firebase BOM | 34.14.0 | Firebase Analytics |
| Google Play Billing | 7.1.1 | In-app purchases and subscriptions |
| Google Play Age Signals | 0.0.3 | Age verification via Google Play |
| Google Play App Update | 2.1.0 | Forced app updates |
| Ktor | 3.0.3 | HTTP client (attribution server) |
| Room | 2.8.4 | Local database (purchases, experiments) |
| DataStore Preferences | 1.2.1 | SDK state persistence |
| Kotlin Coroutines | 1.11.0 | Async loading |
| Timber | 5.0.1 | Logging |