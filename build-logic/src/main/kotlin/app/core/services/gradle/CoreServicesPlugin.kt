package app.core.services.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.Properties

/**
 * Build-time setup the SDK needs in the consumer's **application** module.
 *
 * - Applies the Amplitude Session Replay Gradle plugin. Session Replay 1.x records Compose only through
 *   bytecode instrumentation, and that instrumentation runs only in the module that builds the APK —
 *   applying it from the library module has no effect.
 * - Aligns every Session Replay runtime artifact to the version the plugin was built for, so the
 *   artifact the plugin adds and the one the SDK depends on can never diverge.
 */
class CoreServicesPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.library") {
            throw GradleException(
                "Plugin '$PLUGIN_ID' must be applied to the application module, not to library '${project.path}'."
            )
        }
        project.pluginManager.withPlugin("com.android.application") {
            project.pluginManager.apply(AMPLITUDE_SESSION_REPLAY_PLUGIN_ID)
        }

        val sessionReplayVersion = generatedProperty("sessionReplayVersion")
        project.configurations.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == AMPLITUDE_GROUP && requested.name in SESSION_REPLAY_ARTIFACTS) {
                    useVersion(sessionReplayVersion)
                    because("$PLUGIN_ID pins Session Replay to the version of its Gradle plugin")
                }
            }
        }
    }

    /** Reads a value generated into this plugin's jar (`generateProperties` in build.gradle.kts). */
    private fun generatedProperty(key: String): String {
        val stream = CoreServicesPlugin::class.java.getResourceAsStream(PROPERTIES_RESOURCE)
            ?: throw GradleException("$PLUGIN_ID: resource $PROPERTIES_RESOURCE is missing")
        return Properties().apply { stream.use(::load) }.getProperty(key)
            ?: throw GradleException("$PLUGIN_ID: $key is missing in $PROPERTIES_RESOURCE")
    }

    private companion object {
        const val PROPERTIES_RESOURCE = "/app-core-services-app.properties"
        const val PLUGIN_ID = "io.github.gitjordan94.core-services"
        const val AMPLITUDE_SESSION_REPLAY_PLUGIN_ID = "com.amplitude.session-replay"
        const val AMPLITUDE_GROUP = "com.amplitude"

        val SESSION_REPLAY_ARTIFACTS = setOf(
            "session-replay-android",
            "plugin-session-replay-android",
            "middleware-session-replay-android",
            "plugin-segment-session-replay-android",
        )
    }
}
