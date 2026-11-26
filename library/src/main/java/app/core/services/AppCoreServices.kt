package app.core.services

import android.app.Activity
import android.content.Context
import app.core.services.AppCoreServices.Companion.configure
import app.core.services.amplitude.sessionreplay.SessionReplayConfig
import app.core.services.analytics.Analytics
import app.core.services.amplitude.AmplitudeConfig
import app.core.services.attribution.AttributionServerConfig
import app.core.services.billing.BillingClient
import app.core.services.billing.BillingConfig
import app.core.services.config.RemoteConfig
import app.core.services.config.RemoteConfigParameters
import app.core.services.consent.Consent
import app.core.services.core.AppCoreServiceProvider
import app.core.services.core.model.BootstrapResult
import app.core.services.deeplink.DeepLinkManager
import kotlinx.coroutines.flow.StateFlow

/**
 * The main entry point for the App Core Services SDK.
 *
 * Provides a unified access point for analytics, remote configuration, billing, and attribution.
 *
 * ### Typical startup sequence (inside `Application.onCreate`):
 * ```
 * val sdk = AppCoreServices.configure(configuration)
 * sdk.setConsent(consent)   // optional, call before start() if consent is already known
 * sdk.start(context)
 * ```
 * Then call [bootstrap] early in the first screen's lifecycle to preload attribution,
 * remote configs, and purchases before they are needed.
 */
interface AppCoreServices {
    /** Analytics module for tracking events and user properties. */
    val analytics: Analytics

    /** Remote configuration module for fetching and reading remote values. */
    val remoteConfig: RemoteConfig

    /**
     * Emits [BootstrapResult] progressively as data becomes available:
     * - `null` while loading has not started or is in progress
     * - [BootstrapResult] with `featureFlagsReady = false` once the initial FF fetch completes (no attribution)
     * - [BootstrapResult] with `featureFlagsReady = true` once FF are refreshed with attribution data
     *
     * Collect this flow on the splash screen to decide when to proceed.
     */
    val bootstrapFlow: StateFlow<BootstrapResult?>

    /** Billing module for managing products, purchases, and subscriptions. */
    val billingClient: BillingClient

    /** Deep link manager for handling and processing incoming deep links. */
    val deepLinkManager: DeepLinkManager

    /**
     * Applies user consent to all integrated SDKs (AppsFlyer, Amplitude, Firebase) and sends it to
     * the attribution server: in the install request when it is known by then, otherwise (and on
     * every later change) through `POST /attribute/consent`. Repeating the same value sends nothing.
     *
     * Call it only with the user's answer, or with a value that stands for one (e.g. everything
     * granted where no consent is required) — not with a placeholder while the answer is pending:
     * the attribution server would take the placeholder as the answer.
     *
     * Call this **before** [start] whenever possible so that the consent state is respected
     * from the very first SDK interaction. If called after [start], consent is still applied
     * immediately to all SDKs, but some early events may already have been sent without it.
     *
     * @param consent The user's consent choices for analytics and ad storage.
     */
    fun setConsent(consent: Consent)

    /**
     * Starts the SDK's runtime services — most importantly AppsFlyer attribution tracking.
     *
     * Must be called once, typically in `Application.onCreate`, after [configure] and
     * optionally after [setConsent]. Subsequent calls are ignored.
     *
     * @param context The application [Context].
     */
    fun start(context: Context)

    /**
     * Captures launch intent data from the launcher Activity for attribution/deep link
     * resolution.
     *
     * Call once from the launcher `Activity.onCreate`, before [start].
     *
     * @param activity The launcher [Activity].
     */
    fun onLauncherActivityCreated(activity: Activity)

    /**
     * Bootstraps the SDK by concurrently loading all data required for app startup:
     * attribution, remote configs, billing purchases, store country, and device info.
     *
     * Safe to call multiple times — only the first invocation triggers loading;
     * subsequent calls await and return the same result.
     *
     * @param isFirstLaunch Override for first-launch detection. Pass `false` to suppress
     *   first-launch logic regardless of the persisted flag; `null` defers to the stored value.
     * @return A [BootstrapResult] containing all data collected during initialization.
     */
    suspend fun bootstrap(isFirstLaunch: Boolean? = null): BootstrapResult

    /**
     * Returns the most recently completed [BootstrapResult], or `null` if [bootstrap]
     * has not finished yet.
     */
    fun getBootstrapResult(): BootstrapResult?

    /**
     * Returns the AppsFlyer UID used as the SDK's internal user identifier,
     * or `null` if AppsFlyer has not been started yet.
     */
    fun getUserId(): String?

    /**
     * Associates your own user ID with the current session.
     *
     * Use this after a user signs in to link your backend's user record with analytics
     * and attribution data. Pass `null` to clear the external ID and reset the Amplitude session.
     *
     * @param externalUserId Your system's user ID, or `null` to sign the user out.
     */
    fun setExternalUserId(externalUserId: String?)

    /**
     * Immutable configuration required to initialize the SDK via [configure].
     *
     * Create a single instance in `Application.onCreate` and pass it to [configure].
     */
    class Configuration(
        val context: Context,
        val appsFlyerDevKey: String,
        val amplitudeConfig: AmplitudeConfig,
        val billingConfig: BillingConfig,
        val remoteConfigParameters: RemoteConfigParameters,
        val attributionServerConfig: AttributionServerConfig? = null,
        val sessionReplayConfig: SessionReplayConfig = SessionReplayConfig(),
        val dataStoreFileName: String,
    )

    companion object {
        @Volatile
        private var INSTANCE: AppCoreServices? = null

        /**
         * The globally accessible singleton instance of the SDK.
         *
         * @throws UninitializedPropertyAccessException if [configure] has not been called yet.
         */
        @JvmStatic
        val sharedInstance: AppCoreServices
            get() {
                return INSTANCE
                    ?: throw UninitializedPropertyAccessException("SDK not configured. Call AppCoreServices.configure() first.")
            }

        /**
         * Creates and stores the singleton [AppCoreServices] instance.
         *
         * Must be called once in `Application.onCreate` before any other SDK usage.
         * Subsequent calls return the existing instance without re-initializing.
         *
         * @param configuration SDK settings built from [Configuration].
         * @return The configured singleton instance.
         */
        @JvmStatic
        fun configure(configuration: Configuration): AppCoreServices {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppCoreServiceProvider.create(configuration).also { INSTANCE = it }
            }
        }
    }
}