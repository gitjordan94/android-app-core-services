package app.core.services.attribution.model

import app.core.services.consent.Consent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The user's consent as the attribution server takes it, in the install request and in
 * `POST /attribute/consent`. A `null` [gdprApplies] is left out of the JSON.
 */
@Serializable
data class AttributionConsent(
    @SerialName("gdprApplies") val gdprApplies: Boolean? = null,
    @SerialName("adUserData") val adUserData: Boolean,
    @SerialName("adPersonalization") val adPersonalization: Boolean,
    @SerialName("adStorage") val adStorage: Boolean,
    @SerialName("analyticsStorage") val analyticsStorage: Boolean,
)

internal fun Consent.toAttributionConsent() = AttributionConsent(
    gdprApplies = isUserSubjectToGDPR,
    adUserData = adUserData,
    adPersonalization = adPersonalization,
    adStorage = adStorage,
    analyticsStorage = analyticsStorage,
)
