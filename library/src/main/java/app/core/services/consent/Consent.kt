package app.core.services.consent

data class Consent(
    val analyticsStorage: Boolean,
    val adPersonalization: Boolean,
    val adStorage: Boolean,
    val adUserData: Boolean,
    val isUserSubjectToGDPR: Boolean? = null,
) {
    companion object {
        /**
         * All consent signals denied. Use as the initial state for apps that
         * integrate a CMP and have not yet collected user consent.
         */
        @Deprecated(
            message = "Build Consent with explicit flags and isUserSubjectToGDPR.",
            replaceWith = ReplaceWith(
                "Consent(analyticsStorage = false, adPersonalization = false, " +
                        "adStorage = false, adUserData = false, isUserSubjectToGDPR = null)",
                "app.core.services.consent.Consent",
            ),
        )
        val DENIED_ALL = Consent(
            analyticsStorage = false,
            adPersonalization = false,
            adStorage = false,
            adUserData = false
        )

        /** All consent signals granted. */
        @Deprecated(
            message = "Build Consent with explicit flags and isUserSubjectToGDPR.",
            replaceWith = ReplaceWith(
                "Consent(analyticsStorage = true, adPersonalization = true, " +
                        "adStorage = true, adUserData = true, isUserSubjectToGDPR = null)",
                "app.core.services.consent.Consent",
            ),
        )
        val GRANTED_ALL = Consent(
            analyticsStorage = true,
            adPersonalization = true,
            adStorage = true,
            adUserData = true
        )
    }
}