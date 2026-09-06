package com.guardianlayer.app.firewall

import android.content.Context
import android.content.Intent

 data class FirewallApp(
    val label: String,
    val packageName: String
)

class LauncherAppCatalog(private val context: Context) {
    fun load(): List<FirewallApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager
            .queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                val label = runCatching { resolveInfo.loadLabel(context.packageManager).toString() }
                    .getOrDefault(packageName)
                FirewallApp(label = label, packageName = packageName)
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }
}
