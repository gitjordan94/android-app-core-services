package app.core.services.core.model

import app.core.services.billing.model.Purchases

data class BootstrapResult(
    val attribution: Attribution,
    val storeCountry: String? = null,
    val purchases: Purchases? = null,
    val isFirstLaunch: Boolean,
    val isAB: Boolean? = null,
)