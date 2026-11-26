package app.core.services.attribution.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class AttributionInstallResponse(
    @SerialName("userId") val userId: String,
    @SerialName("userIp") val userIp: String?,
    @SerialName("isAB") val isAB: Boolean?,
)