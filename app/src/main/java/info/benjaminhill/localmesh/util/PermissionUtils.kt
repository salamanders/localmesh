package info.benjaminhill.localmesh.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log

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
            Log.e("PermissionUtils", "Failed to getDangerousPermissions: ${e.message}")
            return emptyArray()
        }

        val requestedPermissions = packageInfo.requestedPermissions ?: return emptyArray()

        return requestedPermissions.filter { permissionName ->
            try {
                val permissionInfo = context.packageManager.getPermissionInfo(
                    permissionName,
                    0
                )
                permissionInfo.protection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
            } catch (e: Exception) {
                Log.e(
                    "PermissionUtils",
                    "Failed to get permission info for $permissionName: ${e.message}"
                )
                false
            }
        }.toTypedArray()
    }
}
