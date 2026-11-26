package app.core.services.core.model

import app.core.services.attribution.model.AdvertisingId
import app.core.services.billing.model.Purchases
import app.core.services.deviceinfo.DeviceInfo
import kotlinx.coroutines.Deferred

internal data class LoadSources(
    val isFirstLaunch: Deferred<Boolean?>,
    val attribution: Deferred<Attribution?>,
    val featureFlagsInitialDeferred: Deferred<Boolean?>,
    val featureFlags: Deferred<Boolean?>,
    val deviceInfo: Deferred<DeviceInfo?>,
    val appSetId: Deferred<String?>,
    val advertisingId: Deferred<AdvertisingId?>,
    val purchases: Deferred<Purchases?>,
    val storeCountry: Deferred<String?>,
    val isAB: Deferred<Boolean?>,
)