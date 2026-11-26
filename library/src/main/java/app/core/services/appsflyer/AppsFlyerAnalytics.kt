package app.core.services.appsflyer

import android.app.Activity
import android.content.Context
import app.core.services.BuildConfig
import app.core.services.analytics.Analytics
import app.core.services.analytics.AnalyticsEvent
import app.core.services.analytics.PurchaseEventLogger
import app.core.services.appsflyer.error.AppsFlyerConversionFailureException
import app.core.services.billing.model.Purchase
import app.core.services.consent.Consent
import com.appsflyer.AppsFlyerLib
import com.appsflyer.share.AFInAppEventType
import com.appsflyer.share.AppsFlyerConsent
import com.appsflyer.share.AppsFlyerConversionListener
import com.appsflyer.share.attribution.AppsFlyerRequestListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

internal class AppsFlyerAnalytics(
    devKey: String,
    private val applicationContext: Context,
    private val appsFlyer: AppsFlyerLib,
) : Analytics, PurchaseEventLogger {
    private val _conversionDataFlow = MutableStateFlow<ConversionDataResult?>(null)
    internal val conversionDataFlow = _conversionDataFlow.asStateFlow()

    internal var appsFlyerUID: String? = null
        private set
        get() {
            if (field == null) {
                field = appsFlyer.getAppsFlyerUID(applicationContext)
            }

            return field
        }

    init {
        appsFlyer.setDebugLog(BuildConfig.DEBUG)

        appsFlyer.init(
            devKey,
            object : AppsFlyerConversionListener {
                override fun onConversionDataSuccess(conversionData: MutableMap<String, Any>?) {
                    Timber.d("onConversionDataSuccess: $conversionData.")
                    _conversionDataFlow.value = ConversionDataResult.Success(conversionData)
                }

                override fun onConversionDataFail(errorMessage: String?) {
                    Timber.e(
                        AppsFlyerConversionFailureException(errorMessage ?: "Unknown error"),
                        "AppsFlyer conversion data fail: $errorMessage."
                    )

                    _conversionDataFlow.value = ConversionDataResult.Fail(errorMessage)
                }
            },
            applicationContext
        )

        appsFlyer.enableTCFDataCollection(true)
    }

    internal fun setConsent(consent: Consent) {
        Timber.d("AppsFlyer consent: $consent")

        appsFlyer.setConsentData(
            AppsFlyerConsent(
                isUserSubjectToGDPR = consent.isUserSubjectToGDPR,
                hasConsentForDataUsage = consent.adUserData,
                hasConsentForAdsPersonalization = consent.adPersonalization,
                hasConsentForAdStorage = consent.adStorage
            )
        )
    }

    internal fun start() {
        Timber.d("Starting AppsFlyer.")

        appsFlyer.registerSessionReadyListener {
            Timber.d("AppsFlyer session is ready!")

            appsFlyer.start(
                object : AppsFlyerRequestListener {
                    override fun onSuccess() {
                        Timber.d("AppsFlyer start success.")
                    }

                    override fun onError(code: Int, error: String) {
                        Timber.e("AppsFlyer start error: $error.")
                    }
                }
            )
        }
    }

    override fun setUserProperties(properties: Map<String, Any?>?) {
        // do nothing
    }

    override fun logEvent(event: String, properties: Map<String, Any?>?) {
        appsFlyer.logEvent(applicationContext, event, properties)
    }

    override fun logEvent(event: AnalyticsEvent) {
        logEvent(event.type, event.properties)
    }

    override fun logPurchase(purchase: Purchase) {
        if (purchase.price.amountMicros == 0L) {
            logEvent(AFInAppEventType.START_TRIAL)
        } else {
            logEvent(AFInAppEventType.PURCHASE, mapOf("productId" to purchase.product.id))
        }
    }

    internal fun setAdditionalData(data: Map<String, Any>) {
        appsFlyer.setAdditionalData(data)
    }

    internal fun collectLauncherActivityData(activity: Activity) {
        Timber.d("Collecting AppsFlyer launcher activity data.")
        appsFlyer.collectDataFromLauncherActivity(activity)
    }
}