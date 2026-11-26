package app.core.services.amplitude.sessionreplay

/**
 * Configuration class for controlling Amplitude Session Replay behavior.
 *
 * This is used when creating a [com.amplitude.android.plugins.SessionReplayPlugin] and allows you to
 * define sampling, default enablement, and privacy settings in one place.
 *
 * Compose screens are recorded only when the app module applies the `app.core.services` Gradle plugin
 * (see README, Session Replay).
 *
 * @property autoStart
 *  Whether Session Replay should be enabled immediately after initialization.
 *
 * @property sampleRate
 *  The fraction of user sessions that should be recorded (`0.0` to `1.0`).
 *  - `0.0` = no sessions recorded.
 *  - `1.0` = all sessions recorded.
 *  In production it’s recommended to start with a low rate (e.g., 0.01–0.05)
 *  to control event volume and cost.
 *
 * @property maskLevel
 *  Global masking level for on-screen data.
 *  Defines how the SDK handles text, input fields, and sensitive elements.
 *  - [MaskLevel.LIGHT] — minimal masking, hides only input fields.
 *  - [MaskLevel.MEDIUM] — hides input fields and most text (recommended default).
 *  - [MaskLevel.CONSERVATIVE] — hides all text, suitable for high privacy requirements.
 *
 * @property enableRemoteConfig
 *  Whether the SDK should pull Session Replay settings from Amplitude Remote Config.
 *  Useful for dynamic control without releasing a new app version.
 *  For local testing, it’s usually better to set this to `false`.
 */
data class SessionReplayConfig(
    val autoStart: Boolean = false,
    val sampleRate: Double = 0.01,
    val maskLevel: MaskLevel = MaskLevel.MEDIUM,
    val enableRemoteConfig: Boolean = true,
) {
    /**
     * Available global masking levels for Session Replay.
     */
    enum class MaskLevel {
        /** Minimal masking — hides only text input fields. */
        LIGHT,

        /** Medium masking — hides input fields and most text elements. */
        MEDIUM,

        /** Strictest masking — hides all text on screen. */
        CONSERVATIVE
    }

    companion object {

        /**
         * Config for local testing:
         * - sampleRate = 100% (all sessions recorded)
         * - remoteConfig disabled (values taken only from code)
         */
        fun testingConfig(): SessionReplayConfig {
            return SessionReplayConfig(
                sampleRate = 1.0,
                enableRemoteConfig = false
            )
        }
    }
}