package app.core.services.attribution.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AttributionInstallRequestBody(
    @SerialName("sdkVersion") val sdkVersion: String?,
    @SerialName("osVersion") val osVersion: String?,
    @SerialName("appVersion") val appVersion: String?,
    @SerialName("appVersionCode") val appVersionCode: Long?,
    @SerialName("limitAdTracking") val limitAdTracking: Boolean?,
    @SerialName("advertisingId") val advertisingId: String?,
    @SerialName("appsflyerId") val appsflyerId: String?,
    @SerialName("storeCountry") val storeCountry: String?,
    @SerialName("environment") val environment: String?,
    @SerialName("deviceId") val deviceId: String?,
    @SerialName("externalAuthorization") val externalAuthorization: Boolean?,
    @SerialName("installParams") val installParams: InstallParams?,
    @SerialName("firebaseAppInstanceId") val firebaseAppInstanceId: String?,
    // Default null, so that with encodeDefaults = false an install without an answer has no "consent" key.
    @SerialName("consent") val consent: AttributionConsent? = null,
)

@Serializable
data class InstallParams(
    @SerialName("installReferrer") val installReferrer: String?,
    @SerialName("referrerClickTimestampSeconds") val referrerClickTimestampSeconds: Long?,
    @SerialName("installBeginTimestampSeconds") val installBeginTimestampSeconds: Long?,
    @SerialName("googlePlayInstant") val googlePlayInstant: Boolean?,
    @SerialName("referrerClickTimestampServerSeconds") val referrerClickTimestampServerSeconds: Long?,
    @SerialName("installBeginTimestampServerSeconds") val installBeginTimestampServerSeconds: Long?,
    @SerialName("installVersion") val installVersion: String?,
)