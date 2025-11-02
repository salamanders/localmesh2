package info.benjaminhill.localmesh2

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import timber.log.Timber

/**
 * A utility object for working with Android permissions.
 * This object provides a simple way to get all dangerous permissions declared in the manifest.
 * This is unusual because it is a utility object, but it is not a class.
 * It does not do anything other than get the dangerous permissions.
 * It is surprising that this is an object instead of a class with static methods.
 */
object PermissionUtils {

    /**
     * Reads the AndroidManifest.xml and returns an array of all permissions declared
     * that are considered "dangerous" and require a runtime user prompt.
     *
     * @param context The application context.
     * @return An array of permission strings.
     */
    fun getDangerousPermissions(context: Context): Array<String> {
        val packageInfo: PackageInfo = try {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to getDangerousPermissions")
            return emptyArray()
        }
        val requestedPermissions = packageInfo.requestedPermissions ?: return emptyArray()
        return requestedPermissions.filter { permissionName ->
            try {
                val permissionInfo = context.packageManager.getPermissionInfo(
                    permissionName,
                    0
                )
                permissionInfo.protection == PermissionInfo.PROTECTION_DANGEROUS
            } catch (e: Exception) {
                Timber.e(e, "Failed to get permission info for $permissionName")
                false
            }
        }.toTypedArray()
    }
}