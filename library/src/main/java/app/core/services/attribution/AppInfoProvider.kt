package app.core.services.attribution

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import timber.log.Timber

/**
 * An interface for providing application-specific information.
 * This abstraction simplifies testing by allowing for mock implementations.
 */
internal interface AppInfoProvider {
    /**
     * @return The application's version name (e.g., "1.0.3"), or null if it cannot be retrieved.
     */
    fun getAppVersionName(): String?

    /**
     * @return The application's version code (`versionCode`, or `longVersionCode` on API 28+),
     * or null if it cannot be retrieved.
     */
    fun getAppVersionCode(): Long?

    companion object {

        fun create(applicationContext: Context): AppInfoProvider {
            return AppInfoProviderImpl(applicationContext)
        }
    }
}

/**
 * The default implementation of [AppInfoProvider] that retrieves information
 * from the Android system's [PackageManager].
 */
internal class AppInfoProviderImpl(
    private val applicationContext: Context
) : AppInfoProvider {
    override fun getAppVersionName(): String? {
        return getPackageInfo()?.versionName
    }

    override fun getAppVersionCode(): Long? {
        val packageInfo = getPackageInfo() ?: return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    private fun getPackageInfo(): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                applicationContext.packageManager.getPackageInfo(
                    applicationContext.packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.e(e, "Could not find package info for '${applicationContext.packageName}'")
            null
        } catch (e: Throwable) {
            Timber.e(
                e,
                "An unexpected error occurred while trying to retrieve the application package info."
            )
            null
        }
    }
}
