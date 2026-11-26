package app.core.services.attribution

import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.core.services.BuildConfig
import app.core.services.attribution.installreferrer.GoogleInstallReferrerProvider
import app.core.services.attribution.installreferrer.InstallReferrerProvider
import app.core.services.attribution.model.AttributionConsent
import app.core.services.attribution.model.AttributionInstallRequestBody
import app.core.services.attribution.model.ConsentRequestBody
import app.core.services.attribution.model.ExternalAuthorizationRequestBody
import app.core.services.attribution.model.InstallParams
import app.core.services.attribution.model.toAttributionConsent
import app.core.services.billing.BillingStoreCountryProvider
import app.core.services.consent.Consent
import app.core.services.data.KeyValueStorage
import app.core.services.firebase.FirebaseAnalytics
import app.core.services.firebase.FirebaseAppInstanceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID

internal interface AttributionServerClient {
    /**
     * Sends the install once per app install; later calls answer from storage.
     *
     * @return the server's `isAB` verdict, or `null` when the request failed. A failed request is
     * retried on the next launch and when the app returns to the foreground. A response that
     * arrives after the caller stopped waiting is stored and answers the next call.
     */
    fun install(userId: String): Deferred<Boolean?>

    fun setExternalUserId(externalUserId: String)

    /**
     * Hands the user's consent to the attribution server. Before the install is sent it rides
     * along in the install request; after that, every value the server has not seen yet goes to
     * `POST /attribute/consent`, retried on the next launch and foreground until it is accepted.
     */
    fun setConsent(consent: Consent)

    suspend fun getInstallUserId(): String?

    companion object {
        fun create(
            applicationContext: Context,
            attributionServerConfig: AttributionServerConfig,
            deviceIdProvider: DeviceIdProvider,
            billingStoreCountryProvider: BillingStoreCountryProvider,
            keyValueStorage: KeyValueStorage,
        ): AttributionServerClient {
            return AttributionServerClientImpl(
                config = attributionServerConfig,
                appInfoProvider = AppInfoProvider.create(applicationContext),
                advertisingIdProvider = AdvertisingIdProvider.create(applicationContext),
                attributionServerApi = AttributionServerApi.create(
                    attributionServerConfig.token,
                    attributionServerConfig.serverUrl
                ),
                billingStoreCountryProvider = billingStoreCountryProvider,
                keyValueStorage = keyValueStorage,
                deviceIdProvider = deviceIdProvider,
                installReferrerProvider = GoogleInstallReferrerProvider(applicationContext),
                firebaseAppInstanceId = FirebaseAnalytics,
                processLifecycle = ProcessLifecycleOwner.get().lifecycle,
            )
        }
    }
}

internal class AttributionServerClientImpl(
    private val config: AttributionServerConfig,
    private val appInfoProvider: AppInfoProvider,
    private val advertisingIdProvider: AdvertisingIdProvider,
    private val attributionServerApi: AttributionServerApi,
    private val billingStoreCountryProvider: BillingStoreCountryProvider,
    private val deviceIdProvider: DeviceIdProvider,
    private val keyValueStorage: KeyValueStorage,
    private val installReferrerProvider: InstallReferrerProvider,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val firebaseAppInstanceId: FirebaseAppInstanceId,
    processLifecycle: Lifecycle,
) : AttributionServerClient {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val installUserId = MutableStateFlow<String?>(null)

    @Volatile
    private var isFirstStart = true

    private val installMutex = Mutex()
    private val externalUserIdMutex = Mutex()
    private val consentMutex = Mutex()

    /** The newest consent of this process; read at send time, so the order of launches does not matter. */
    private val latestConsent = MutableStateFlow<AttributionConsent?>(null)

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ATTRIBUTION_USER_ID = "attribution_user_id"
        private const val KEY_ATTRIBUTION_IS_AB = "attribution_is_ab"
        private const val KEY_EXTERNAL_USER_ID = "external_user_id"
        private const val KEY_ATTRIBUTION_EXTERNAL_USER_ID = "attribution_external_user_id"

        /** The newest consent the app has set, as [AttributionConsent] JSON. */
        private const val KEY_CONSENT_LATEST = "attribution_consent_latest"

        /** The consent the server has accepted, in the install or through `/attribute/consent`. */
        private const val KEY_CONSENT_SENT = "attribution_consent_sent"

        /** A consent the server rejected with a 4xx; not sent again until the consent changes. */
        private const val KEY_CONSENT_REJECTED = "attribution_consent_rejected"

        private val consentJson = Json { ignoreUnknownKeys = true }
    }

    init {
        processLifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (isFirstStart) {
                        isFirstStart = false
                    } else {
                        retryFailedRequests()
                    }
                }
            }
        )

        retryFailedRequests()
    }

    override fun install(userId: String): Deferred<Boolean?> {
        return scope.async {
            keyValueStorage.putString(KEY_USER_ID, userId)
            installInternal(userId)
        }
    }

    override fun setExternalUserId(externalUserId: String) {
        scope.launch {
            keyValueStorage.putString(KEY_EXTERNAL_USER_ID, externalUserId)
            setExternalUserIdInternal(externalUserId)
        }
    }

    override fun setConsent(consent: Consent) {
        latestConsent.value = consent.toAttributionConsent()
        scope.launch { syncConsent() }
    }

    override suspend fun getInstallUserId(): String? {
        return keyValueStorage.getString(KEY_ATTRIBUTION_USER_ID)
    }

    /** @return the stored or received `isAB`, or `null` when the request failed. */
    private suspend fun installInternal(userId: String): Boolean? {
        getSentInstallIsAb()?.let { return it }

        return installMutex.withLock {
            getSentInstallIsAb()?.let { return@withLock it }

            try {
                sendInstall(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.e(e, "Failed to send install request.")
                null
            }
        }
    }

    /** `isAB` of an install that was already sent, or `null` when it has not been sent yet. */
    private suspend fun getSentInstallIsAb(): Boolean? {
        if (keyValueStorage.getString(KEY_ATTRIBUTION_USER_ID) == null) return null

        Timber.d("Install already sent.")
        // Installs sent before the server returned isAB have no stored value.
        return keyValueStorage.getBoolean(KEY_ATTRIBUTION_IS_AB) ?: false
    }

    private suspend fun sendInstall(userId: String): Boolean? {
        val appVersion = requireNotNull(appInfoProvider.getAppVersionName()) {
            "App version name is required but was null."
        }

        val advertisingId = advertisingIdProvider.provide()

        val installParams = installReferrerProvider.getInstallReferrer()?.let {
            InstallParams(
                installReferrer = it.installReferrer,
                referrerClickTimestampSeconds = it.referrerClickTimestampSeconds,
                installBeginTimestampSeconds = it.installBeginTimestampSeconds,
                googlePlayInstant = it.googlePlayInstant,
                referrerClickTimestampServerSeconds = it.referrerClickTimestampServerSeconds,
                installBeginTimestampServerSeconds = it.installBeginTimestampServerSeconds,
                installVersion = it.installVersion
            )
        }

        val request = AttributionInstallRequestBody(
            sdkVersion = BuildConfig.SDK_VERSION,
            osVersion = Build.VERSION.RELEASE,
            appVersion = appVersion,
            appVersionCode = appInfoProvider.getAppVersionCode(),
            limitAdTracking = advertisingId.isLimitAdTrackingEnabled,
            advertisingId = advertisingId.id ?: UUID.randomUUID().toString(),
            appsflyerId = userId,
            storeCountry = billingStoreCountryProvider.getStoreCountry(),
            environment = config.environment.value,
            externalAuthorization = config.externalAuthorization,
            deviceId = deviceIdProvider.provide(),
            installParams = installParams,
            firebaseAppInstanceId = firebaseAppInstanceId.getAppInstanceId(),
            consent = getLatestConsent(),
        )

        return attributionServerApi.install(request).fold(
            onSuccess = { response ->
                // The server answered: an omitted verdict means it runs no detection.
                val isAB = response.isAB ?: false
                Timber.d("Successfully sent install request, isAB=%b.", isAB)
                // isAB and the consent first: a stored user id marks the install as sent, and
                // from then on both are read back from storage.
                keyValueStorage.putBoolean(KEY_ATTRIBUTION_IS_AB, isAB)
                request.consent?.let { keyValueStorage.putString(KEY_CONSENT_SENT, it.encode()) }
                keyValueStorage.putString(KEY_ATTRIBUTION_USER_ID, response.userId)
                installUserId.value = response.userId
                // The consent may have changed while the install was in flight.
                scope.launch { syncConsent() }
                isAB
            },
            onFailure = { e ->
                Timber.e(e, "Failed to send install request.")
                null
            }
        )
    }

    private suspend fun setExternalUserIdInternal(externalUserId: String) {
        val attributionExternalUserId = keyValueStorage.getString(KEY_ATTRIBUTION_EXTERNAL_USER_ID)

        if (externalUserId == attributionExternalUserId) {
            Timber.d("External user ID $externalUserId already sent.")
            return
        }

        externalUserIdMutex.withLock {
            val attributionExternalUserId =
                keyValueStorage.getString(KEY_ATTRIBUTION_EXTERNAL_USER_ID)

            if (externalUserId == attributionExternalUserId) {
                Timber.d("External user ID $externalUserId already sent.")
                return
            }

            keyValueStorage.putString(KEY_EXTERNAL_USER_ID, externalUserId)

            val userId = installUserId.first { it != null }

            val externalAuthResult = attributionServerApi.externalAuthorization(
                request = ExternalAuthorizationRequestBody(
                    userId = userId!!,
                    productUserId = externalUserId
                )
            )

            externalAuthResult
                .onSuccess {
                    Timber.d("Successfully sent external user id.")
                    keyValueStorage.putString(KEY_ATTRIBUTION_EXTERNAL_USER_ID, externalUserId)
                }
                .onFailure { e ->
                    Timber.e(e, "Failed to send external user id.")
                }
        }
    }

    /**
     * Sends the newest consent to `POST /attribute/consent` unless the server already has it.
     * Before the install is sent there is nothing to do: the install carries the consent itself.
     */
    private suspend fun syncConsent() {
        try {
            consentMutex.withLock {
                val latest = getLatestConsent() ?: return
                val encoded = latest.encode()
                keyValueStorage.putString(KEY_CONSENT_LATEST, encoded)

                if (keyValueStorage.getString(KEY_ATTRIBUTION_USER_ID) == null) {
                    Timber.d("Consent waits for the install request.")
                    return
                }
                if (encoded == keyValueStorage.getString(KEY_CONSENT_SENT)) {
                    Timber.d("Consent already sent.")
                    return
                }
                if (encoded == keyValueStorage.getString(KEY_CONSENT_REJECTED)) {
                    Timber.d("Consent was rejected by the server; waiting for a change.")
                    return
                }
                val appsflyerId = keyValueStorage.getString(KEY_USER_ID) ?: run {
                    Timber.w("Consent not sent: no AppsFlyer id stored.")
                    return
                }

                attributionServerApi.consent(ConsentRequestBody(appsflyerId, latest))
                    .onSuccess {
                        Timber.d("Successfully sent consent.")
                        keyValueStorage.putString(KEY_CONSENT_SENT, encoded)
                    }
                    .onFailure { e ->
                        Timber.e(e, "Failed to send consent.")
                        if (e is AttributionServerHttpException && e.isClientError) {
                            keyValueStorage.putString(KEY_CONSENT_REJECTED, encoded)
                        }
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.e(e, "Failed to sync consent.")
        }
    }

    /** This process's newest consent, or the one stored by an earlier launch. */
    private suspend fun getLatestConsent(): AttributionConsent? {
        latestConsent.value?.let { return it }

        val stored = keyValueStorage.getString(KEY_CONSENT_LATEST) ?: return null
        return runCatching { consentJson.decodeFromString<AttributionConsent>(stored) }
            .onFailure { Timber.e(it, "Stored consent is unreadable.") }
            .getOrNull()
    }

    private fun AttributionConsent.encode(): String = consentJson.encodeToString(this)

    private fun retryFailedRequests() {
        scope.launch {
            Timber.d("Checking for failed requests.")

            installUserId.value = keyValueStorage.getString(KEY_USER_ID)

            val userId = keyValueStorage.getString(KEY_USER_ID)
            if (userId != null) {
                Timber.d("Retrying install request.")
                installInternal(userId)
            }

            val externalUserId = keyValueStorage.getString(KEY_EXTERNAL_USER_ID)
            if (externalUserId != null) {
                Timber.d("Retrying external user id request.")
                setExternalUserIdInternal(externalUserId)
            }
        }

        // Its own coroutine: the external user id retry above can wait for the install indefinitely.
        scope.launch { syncConsent() }
    }
}