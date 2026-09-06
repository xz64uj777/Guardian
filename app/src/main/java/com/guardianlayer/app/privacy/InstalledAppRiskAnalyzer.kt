package com.guardianlayer.app.privacy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

class InstalledAppRiskAnalyzer(private val context: Context) {

    data class AppExposure(
        val label: String,
        val packageName: String,
        val score: Int,
        val grantedSensitivePermissions: List<String>
    )

    data class PrivacySnapshot(
        val appsReviewed: Int,
        val reviewCount: Int,
        val topApps: List<AppExposure>
    )

    private val weights = mapOf(
        Manifest.permission.ACCESS_BACKGROUND_LOCATION to 35,
        Manifest.permission.READ_CALL_LOG to 25,
        Manifest.permission.WRITE_CALL_LOG to 25,
        Manifest.permission.READ_SMS to 22,
        Manifest.permission.RECEIVE_SMS to 18,
        Manifest.permission.SEND_SMS to 22,
        Manifest.permission.RECORD_AUDIO to 15,
        Manifest.permission.CAMERA to 12,
        Manifest.permission.ACCESS_FINE_LOCATION to 12,
        Manifest.permission.READ_CONTACTS to 10
    )

    fun analyze(): PrivacySnapshot {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }

        val packages = activities.map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .distinct()

        val exposures = packages.mapNotNull { packageName ->
            runCatching { analyzePackage(pm, packageName) }.getOrNull()
        }.sortedByDescending { it.score }

        return PrivacySnapshot(
            appsReviewed = exposures.size,
            reviewCount = exposures.count { it.score >= 30 },
            topApps = exposures.take(5)
        )
    }

    private fun analyzePackage(pm: PackageManager, packageName: String): AppExposure {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }

        val granted = grantedSensitivePermissions(info)
        val score = granted.sumOf { weights[it] ?: 0 }.coerceAtMost(100)
        val label = pm.getApplicationLabel(info.applicationInfo!!).toString()

        return AppExposure(
            label = label,
            packageName = packageName,
            score = score,
            grantedSensitivePermissions = granted.map { shortPermission(it) }
        )
    }

    private fun grantedSensitivePermissions(info: PackageInfo): List<String> {
        val permissions = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags ?: IntArray(permissions.size)

        return permissions.mapIndexedNotNull { index, permission ->
            val isSensitive = weights.containsKey(permission)
            val isGranted = index < flags.size &&
                flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            if (isSensitive && isGranted) permission else null
        }
    }

    private fun shortPermission(permission: String): String = permission.substringAfterLast('.')
}
