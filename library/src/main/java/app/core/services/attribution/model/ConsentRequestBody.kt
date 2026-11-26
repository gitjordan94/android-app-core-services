package app.core.services.attribution.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ConsentRequestBody(
    @SerialName("appsflyerId") val appsflyerId: String,
    @SerialName("consent") val consent: AttributionConsent,
)
