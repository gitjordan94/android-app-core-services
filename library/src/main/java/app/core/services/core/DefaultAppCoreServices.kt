package app.core.services.core

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import app.core.services.AppCoreServices
import app.core.services.BuildConfig
import app.core.services.amplitude.analytics.AmplitudeAnalytics
import app.core.services.analytics.Analytics
import app.core.services.analytics.AnalyticsEvents
import app.core.services.analytics.AnalyticsEvents.AF_CONVERSION_DATA_FAIL
import app.core.services.analytics.AnalyticsEvents.AF_CONVERSION_DATA_SUCCESS
import app.core.services.analytics.AnalyticsProperties
import app.core.services.analytics.toAnalyticsProperties
import app.core.services.appsflyer.AppsFlyerAnalytics
import app.core.services.appsflyer.ConversionDataResult
import app.core.services.appupdates.AppUpdateManager
import app.core.services.attribution.AdvertisingIdProvider
import app.core.services.attribution.AttributionProvider
import app.core.services.attribution.AttributionServerClient
import app.core.services.attribution.DeviceIdProvider
import app.core.services.attribution.model.AdvertisingId
import app.core.services.billing.BillingClient
import app.core.services.common.awaitUntil
import app.core.services.common.getCompletedOrNull
import app.core.services.common.isSystemInDarkTheme
import app.core.services.common.mapOfNotNull
import app.core.services.common.measureExecutionTime
import app.core.services.config.RemoteConfig
import app.core.services.config.RemoteConfigParams.MIN_SUPPORTED_APP_VERSION
import app.core.services.consent.Consent
import app.core.services.core.appsetid.AppSetIdProvider
import app.core.services.core.model.Attribution
import app.core.services.core.model.BootstrapResult
import app.core.services.core.model.LoadSources
import app.core.services.core.model.MediaSource
import app.core.services.core.model.isOrganic
import app.core.services.core.util.toMap
import app.core.services.data.PreferencesDataStore
import app.core.services.deeplink.DeepLinkManager
import app.core.services.deviceinfo.DeviceInfo
import app.core.services.deviceinfo.DeviceInfoProvider
import app.core.services.firebase.FirebaseAnalytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

internal class DefaultAppCoreServices(
    override val analytics: Analytics,
    override val billingClient: BillingClient,
    override val remoteConfig: RemoteConfig,
    override val deepLinkManager: DeepLinkManager,
    private val firebaseAnalytics: FirebaseAnalytics,
    private val appUpdateManager: AppUpdateManager,
    private val amplitudeAnalytics: AmplitudeAnalytics,
    private val attributionServerClient: AttributionServerClient?,
    private val appsFlyerAnalytics: AppsFlyerAnalytics,
    private val configuration: AppCoreServices.Configuration,
    private val preferencesDataStore: PreferencesDataStore,
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val internalAttributionProvider: AttributionProvider,
    private val externalAttributionProvider: AttributionProvider,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val deviceIdProvider: DeviceIdProvider,
    private val appSetIdProvider: AppSetIdProvider,
    private val advertisingIdProvider: AdvertisingIdProvider,
) : AppCoreServices {
    private val mutex = Mutex()
    private val internalScope = CoroutineScope(SupervisorJob() + coroutineDispatcher)

    private val isStarted = AtomicBoolean(false)

    private val startedDeferred = CompletableDeferred<Unit>()

    private var bootstrapResultDeferred: Deferred<BootstrapResult>? = null

    private val consentFlow = MutableStateFlow<Consent?>(null)

    private val _bootstrapFlow = MutableStateFlow<BootstrapResult?>(null)
    override val bootstrapFlow: StateFlow<BootstrapResult?> = _bootstrapFlow.asStateFlow()

    override fun setConsent(consent: Consent) {
        Timber.i(
            "[consent] set: analyticsStorage=%b, adStorage=%b, isUserSubjectToGDPR=%s",
            consent.analyticsStorage,
            consent.adStorage,
            consent.isUserSubjectToGDPR,
        )

        consentFlow.value = consent

        appsFlyerAnalytics.setConsent(consent)
        amplitudeAnalytics.setConsent(consent)
        firebaseAnalytics.setConsent(consent)
        attributionServerClient?.setConsent(consent)
    }

    override fun onLauncherActivityCreated(activity: Activity) {
        appsFlyerAnalytics.collectLauncherActivityData(activity)
    }

    override fun start(context: Context) {
        if (!isStarted.compareAndSet(false, true)) {
            Timber.w("[start] skipped: already started")
            return
        }

        Timber.i("[start] begin")

        appsFlyerAnalytics.start()
        val appsFlyerUID = appsFlyerAnalytics.appsFlyerUID

        Timber.i("[start] AppsFlyer started, uid=%s", appsFlyerUID)

        observeUserId(appsFlyerUID)
        observeConversionData(appsFlyerUID)
        observeFirebaseAppInstanceId()

        startedDeferred.complete(Unit)
        Timber.i("[start] complete")
    }

    override suspend fun bootstrap(isFirstLaunch: Boolean?): BootstrapResult {
        Timber.i("[initialize] requested, isFirstLaunch=%s", isFirstLaunch)

        return mutex.withLock {
            val existing = bootstrapResultDeferred
            if (existing != null) {
                Timber.d("[initialize] already in progress or completed, awaiting existing result")
                existing
            } else {
                Timber.d("[initialize] starting fresh load")
                internalScope.async { load(isFirstLaunch) }.also {
                    bootstrapResultDeferred = it
                }
            }
        }.await()
    }

    override fun getBootstrapResult(): BootstrapResult? = _bootstrapFlow.value

    override fun getUserId(): String? {
        return appsFlyerAnalytics.appsFlyerUID
    }

    override fun setExternalUserId(externalUserId: String?) {
        if (externalUserId != null) {
            Timber.i("[external_user_id] set: %s", externalUserId)
            amplitudeAnalytics.setUserId(externalUserId)
            attributionServerClient?.setExternalUserId(externalUserId)
        } else {
            Timber.i("[external_user_id] cleared, resetting amplitude")
            amplitudeAnalytics.reset()
        }
    }

    private fun setUserId(userId: String?) {
        if (configuration.attributionServerConfig?.externalAuthorization == true) {
            Timber.d("[user_id] skipped: external authorization is enabled")
            return
        }

        Timber.d("[user_id] applying to amplitude and firebase: %s", userId)
        amplitudeAnalytics.setUserId(userId)
        firebaseAnalytics.setUserId(userId)
    }

    private suspend fun load(isFirstLaunch: Boolean?): BootstrapResult {
        Timber.d("[load] waiting for start()")
        startedDeferred.await()
        Timber.i("[load] begin")

        return coroutineScope {
            val consentSnapshot = consentFlow.value
            Timber.d(
                "[load] consent snapshot: %s",
                consentSnapshot?.let { "analytics=${it.analyticsStorage}, ad=${it.adStorage}" }
                    ?: "not_set",
            )

            measureExecutionTime("framework_load") {
                val loadStart = SystemClock.elapsedRealtime()
                val deadline = loadStart + MAX_TIMEOUT_MS

                Timber.i("[load] begin: timeout=%d ms", MAX_TIMEOUT_MS)

                val isFirstLaunchDeferred = async("first_launch") {
                    isFirstLaunch != false && preferencesDataStore.isFirstLaunch()
                }

                val internalAttributionDeferred = async("internal_attribution") {
                    internalAttributionProvider.provide()
                }

                val externalAttributionDeferred = async("external_attribution") {
                    externalAttributionProvider.provide()
                }

                fun getAttribution(tag: String, deadline: Long) = async(tag) {
                    // Providers get a slightly earlier deadline so this composite
                    // deferred completes before consumers awaiting it with [deadline]
                    // give up and fall back to organic.
                    val providersDeadline = deadline - PROVIDERS_DEADLINE_MARGIN_MS

                    raceAttributions(
                        internalAttributionDeferred = internalAttributionDeferred,
                        externalAttributionDeferred = externalAttributionDeferred,
                        providersDeadline = providersDeadline,
                    )
                }

                val initialAttribution = getAttribution(
                    tag = "initial_attribution",
                    deadline = loadStart + INITIAL_ATTRIBUTION_TIMEOUT_MS
                )

                val attribution = getAttribution(tag = "attribution", deadline = deadline)

                val deviceInfoDeferred = async("device_info") {
                    deviceInfoProvider.collectDeviceInfo()
                }

                val appSetIdDeferred = async("app_set_id") {
                    if (consentSnapshot == null || consentSnapshot.adStorage) {
                        appSetIdProvider.provide()
                    } else {
                        Timber.d("[app_set_id] skipped: adStorage denied")
                        null
                    }
                }
                val advertisingIdDeferred = async("advertising_id") {
                    if (consentSnapshot == null || consentSnapshot.adStorage) {
                        advertisingIdProvider.provide()
                    } else {
                        Timber.d("[advertising_id] skipped: adStorage denied")
                        null
                    }
                }
                val purchasesDeferred = async("purchases") {
                    billingClient.getPurchases()
                }
                val storeCountryDeferred = async("store_country") {
                    billingClient.getStoreCountry()
                }

                val featureFlagsInitialDeferred = async("feature_flags_initial") {
                    val attribution = initialAttribution.awaitUntil(deadline)
                    val storeCountry = storeCountryDeferred.awaitUntil(deadline)

                    fetchFeatureFlags(
                        tag = "feature_flags_initial",
                        storeCountry = storeCountry,
                        attribution = attribution
                    )
                }

                val featureFlagsDeferred = async("feature_flags") {
                    val initialAttribution = initialAttribution.awaitUntil(deadline)
                    val attribution = attribution.awaitUntil(deadline)

                    if (attribution == null || attribution.isOrganic || attribution.toMap() == initialAttribution?.toMap()) {
                        Timber.d("[feature_flags] skipped: attribution null or organic")
                        return@async featureFlagsInitialDeferred.await()
                    }

                    val storeCountry = storeCountryDeferred.awaitUntil(deadline)

                    fetchFeatureFlags(
                        tag = "feature_flags",
                        storeCountry = storeCountry,
                        attribution = attribution
                    )
                }

                attachDeviceId()
                val isAbDeferred = sendApplicationInstall()

                val sources = LoadSources(
                    isFirstLaunch = isFirstLaunchDeferred,
                    attribution = attribution,
                    featureFlagsInitialDeferred = featureFlagsInitialDeferred,
                    featureFlags = featureFlagsDeferred,
                    deviceInfo = deviceInfoDeferred,
                    appSetId = appSetIdDeferred,
                    advertisingId = advertisingIdDeferred,
                    purchases = purchasesDeferred,
                    storeCountry = storeCountryDeferred,
                    isAB = isAbDeferred,
                )

                val jobs = launch(sources, deadline)
                Timber.d("[load] launched %d post-source jobs, awaiting...", jobs.size)
                jobs.joinAll()
                Timber.d("[load] jobs joined in %d ms", SystemClock.elapsedRealtime() - loadStart)

                internalScope.launch("force_update") {
                    sources.featureFlagsInitialDeferred.await()

                    runCatching {
                        val minVersion = remoteConfig.getLong(MIN_SUPPORTED_APP_VERSION)
                        minVersion?.let(appUpdateManager::setMinSupportedVersionCode)
                        Timber.i("[force_update] min supported version applied: %d", minVersion)
                    }.onFailure {
                        Timber.e(it, "[force_update] failed to apply min supported version")
                    }
                }

                val result = buildResult(sources)

                internalScope.launch {
                    onFrameworkFinished(result)
                }

                if (!sources.featureFlags.isCompleted) {
                    Timber.d("[load] feature_flags pending — scheduling background refresh")
                    internalScope.launch("feature_flags_background_update") {
                        sources.featureFlags.join()
                        Timber.i("[feature_flags] background refresh complete")
                    }
                }

                Timber.i("[load] complete in %d ms", SystemClock.elapsedRealtime() - loadStart)

                _bootstrapFlow.value = result
                result
            }
        }
    }

    private fun sendApplicationInstall(): Deferred<Boolean?> {
        return async("application_install") {
            val uuid = appsFlyerAnalytics.appsFlyerUID
            if (uuid == null) {
                Timber.w("[application_install] skipped: appsflyer uid is null")
                return@async null
            }
            if (attributionServerClient == null) {
                Timber.d("[application_install] skipped: attribution server client is not configured")
                return@async null
            }
            Timber.d("[application_install] sending install for uid=%s", uuid)
            attributionServerClient.install(uuid).await()
        }
    }

    private fun CoroutineScope.launch(
        sources: LoadSources,
        deadline: Long,
    ): List<Job> = listOf(
        launch("first_launch") {
            val isFirstLaunch = sources.isFirstLaunch.awaitUntil(deadline) ?: true
            val deviceInfo = sources.deviceInfo.awaitUntil(deadline)
            if (isFirstLaunch) onFirstLaunch(deviceInfo)
        },
        launch("attribution_started") {
            val appSetId = sources.appSetId.awaitUntil(deadline)
            val advertisingId = sources.advertisingId.awaitUntil(deadline)
            sendAttributionStarted(appSetId, advertisingId)
        },
        launch("initial_feature_flags") {
            sources.featureFlagsInitialDeferred.awaitUntil(deadline)
        },
        launch("attribution") {
            val attribution = sources.attribution.awaitUntil(deadline)
                ?: Attribution(MediaSource())

            onUserAttributed(attribution)
        },
        launch("feature_flags") {
            sources.featureFlags.awaitUntil(deadline)
        },
        launch("is_ab") {
            sources.isAB.awaitUntil(deadline)
        }
    )

    private fun attachDeviceId() {
        internalScope.launch("attach_device_id") {
            try {
                if (attributionServerClient == null) {
                    Timber.d("[attach_device_id] skipped: attribution server client is not configured")
                    return@launch
                }
                val installUserId = attributionServerClient.getInstallUserId()
                if (installUserId != null) {
                    Timber.d("[attach_device_id] skipped: install user id already present")
                    return@launch
                }
                val deviceId = deviceIdProvider.provide()
                Timber.d("[attach_device_id] applying device id to amplitude: %s", deviceId)
                amplitudeAnalytics.setDeviceId(deviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.e(e, "[attach_device_id] failed")
            }
        }
    }

    private fun CoroutineScope.launch(name: String, block: suspend () -> Unit): Job = launch {
        measureExecutionTime(name) {
            try {
                block()
            } catch (e: CancellationException) {
                Timber.d("[%s] cancelled", name)
                throw e
            } catch (e: Throwable) {
                Timber.e(e, "[%s] load failed", name)
            }
        }
    }

    private fun buildResult(sources: LoadSources): BootstrapResult {
        val attribution = sources.attribution.getCompletedOrNull() ?: run {
            Timber.w("[build_result] attribution timed out — defaulting to empty MediaSource")
            Attribution(MediaSource())
        }
        val purchases = sources.purchases.getCompletedOrNull()
        val storeCountry = sources.storeCountry.getCompletedOrNull()
        val isFirstLaunch = sources.isFirstLaunch.getCompletedOrNull() ?: false
        // null: no answer within the timeout, or the install failed or was skipped.
        val isAB = sources.isAB.getCompletedOrNull()

        Timber.i(
            "[build_result] mediaSource=%s | storeCountry=%s | purchases=%b | firstLaunch=%b | isAB=%s",
            attribution.mediaSource.value.ifEmpty { "organic" },
            storeCountry ?: "timeout",
            purchases != null,
            isFirstLaunch,
            isAB,
        )

        return BootstrapResult(
            attribution = attribution,
            purchases = purchases,
            storeCountry = storeCountry,
            isFirstLaunch = isFirstLaunch,
            isAB = isAB,
        )
    }

    private suspend fun onFirstLaunch(deviceInfo: DeviceInfo?) {
        if (deviceInfo == null) {
            Timber.w("[first_launch] device info unavailable (timeout or failure)")
        }

        val deviceInfoProperties = deviceInfo?.toAnalyticsProperties()?.takeIf { it.isNotEmpty() }

        if (deviceInfoProperties != null) {
            Timber.d("[first_launch] applying %d device info properties", deviceInfoProperties.size)
            amplitudeAnalytics.setUserProperties(deviceInfoProperties)
        } else {
            Timber.d("[first_launch] no device info properties to apply")
        }

        Timber.i("[first_launch] logging FIRST_LAUNCH event")
        amplitudeAnalytics.logEvent(AnalyticsEvents.FIRST_LAUNCH, deviceInfoProperties)

        amplitudeAnalytics.flush()
        amplitudeAnalytics.sendCohort()
        preferencesDataStore.setFirstLaunch(false)
        Timber.d("[first_launch] flushed, cohort sent, flag persisted")
    }

    private fun observeUserId(appsFlyerUid: String?): Job {
        return consentFlow
            .map { it == null || it.analyticsStorage }
            .distinctUntilChanged()
            .onEach { analyticsAllowed ->
                val uid = if (analyticsAllowed) appsFlyerUid else null
                Timber.d("[user_id] analyticsAllowed=%b, uid=%s", analyticsAllowed, uid)
                setUserId(uid)
            }
            .launchIn(internalScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeConversionData(appsFlyerUid: String?): Job {
        Timber.d("[conversion_data] subscribing to AppsFlyer conversion data flow")
        return consentFlow
            .map { it == null || it.analyticsStorage }
            .distinctUntilChanged()
            .flatMapLatest { analyticsAllowed ->
                if (analyticsAllowed) {
                    appsFlyerAnalytics.conversionDataFlow.filterNotNull().distinctUntilChanged()
                } else {
                    Timber.d("[conversion_data] paused: analyticsStorage denied")
                    emptyFlow()
                }
            }
            .onEach { result ->
                when (result) {
                    is ConversionDataResult.Success -> {
                        Timber.i(
                            "[conversion_data] success, %d fields received",
                            result.data?.size ?: 0,
                        )
                        analytics.logEvent(
                            event = AF_CONVERSION_DATA_SUCCESS,
                            properties = result.data.orEmpty() + mapOf("appsflyer_uid" to appsFlyerUid)
                        )
                    }

                    is ConversionDataResult.Fail -> {
                        Timber.w("[conversion_data] failed: %s", result.errorMessage)
                        analytics.logEvent(
                            AF_CONVERSION_DATA_FAIL,
                            properties = mapOf(
                                "appsflyer_uid" to appsFlyerUid,
                                "error" to result.errorMessage
                            )
                        )
                    }
                }
            }
            .launchIn(internalScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeFirebaseAppInstanceId(): Job {
        return consentFlow
            .map { it == null || it.analyticsStorage }
            .distinctUntilChanged()
            .flatMapLatest { analyticsAllowed ->
                if (analyticsAllowed) {
                    flow<Unit> { attachFirebaseAppInstanceId() }
                } else {
                    Timber.d("[firebase_instance_id] paused: analyticsStorage denied")
                    emptyFlow()
                }
            }
            .launchIn(internalScope)
    }

    private suspend fun attachFirebaseAppInstanceId() {
        Timber.d("[firebase_instance_id] requesting app instance id")

        try {
            val appInstanceId = firebaseAnalytics.getAppInstanceId()

            if (appInstanceId == null) {
                Timber.w("[firebase_instance_id] received null, skipping AppsFlyer attach")
                return
            }

            Timber.d("[firebase_instance_id] received: %s, attaching to AppsFlyer", appInstanceId)
            appsFlyerAnalytics.setAdditionalData(mapOf("firebase_app_instance_id" to appInstanceId))
        } catch (e: Throwable) {
            Timber.e(e, "[firebase_instance_id] failed to obtain app instance id")
        }
    }

    private suspend fun fetchFeatureFlags(
        tag: String,
        storeCountry: String?,
        attribution: Attribution? = null,
    ): Boolean {
        val userId = amplitudeAnalytics.getUserId()
        val userProperties = mapOfNotNull(AnalyticsProperties.STORE_COUNTRY to storeCountry)
            .plus(attribution?.toMap().orEmpty())

        Timber.d("[%s] fetch: userId=%s, userProperties=%s", tag, userId, userProperties)
        return remoteConfig.fetch(userId = userId, userProperties = userProperties)
    }

    private fun sendAttributionStarted(
        appSetId: String?,
        advertisingId: AdvertisingId?,
    ) {
        val properties = mapOfNotNull(
            "android_framework_version" to BuildConfig.SDK_VERSION,
            "appsflyer_sdk_version" to BuildConfig.AF_SDK_VERSION,
            "appsflyer_uid" to appsFlyerAnalytics.appsFlyerUID,
            "app_set_id" to appSetId,
            "advertising_id" to advertisingId?.id,
            "is_limit_ad_tracking_enabled" to advertisingId?.isLimitAdTrackingEnabled,
            "device_theme" to if (configuration.context.isSystemInDarkTheme()) {
                "dark"
            } else {
                "light"
            }
        )

        Timber.i(
            "[attribution_started] uid=%s, app_set_id=%s, advertising_id=%s, limit_ad_tracking=%s",
            appsFlyerAnalytics.appsFlyerUID,
            appSetId,
            advertisingId?.id,
            advertisingId?.isLimitAdTrackingEnabled,
        )

        analytics.setUserProperties(properties)

        analytics.logEvent(AnalyticsEvents.ATTRIBUTION_STARTED, properties)
    }

    private fun onUserAttributed(attribution: Attribution) {
        Timber.i("[attribution] $attribution")

        val properties = attribution.toMap()
        analytics.setUserProperties(properties)
        analytics.logEvent(AnalyticsEvents.ATTRIBUTION, properties)
    }

    private fun onFrameworkFinished(result: BootstrapResult) {
        val properties = result.attribution.toMap()
            .plus(AnalyticsProperties.STORE_COUNTRY to (result.storeCountry ?: "unknown"))

        analytics.setUserProperties(properties)

        analytics.logEvent(
            event = AnalyticsEvents.FRAMEWORK_FINISHED,
            properties = properties
        )
    }

    private fun <T> async(name: String, block: suspend () -> T): Deferred<T?> {
        return internalScope.async {
            measureExecutionTime(name) {
                try {
                    val result = block()
                    Timber.d("[%s] loaded: %s", name, result)
                    result
                } catch (e: CancellationException) {
                    Timber.d("[%s] cancelled", name)
                    throw e
                } catch (e: Throwable) {
                    Timber.e(e, "[%s] load failed", name)
                    null
                }
            }
        }
    }

    internal companion object {
        const val MAX_TIMEOUT_MS = 6_500L
        const val INITIAL_ATTRIBUTION_TIMEOUT_MS = 3_000L
        const val PROVIDERS_DEADLINE_MARGIN_MS = 250L
    }
}