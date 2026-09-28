package com.guardianlayer.app.firewall

import android.content.Context

/** Stores the apps the user wants Guardian to protect with DNS-level Tracker Shield. */
object TrackerShieldRuleStore {
    private const val PREFS = "guardian_tracker_shield_rules"
    private const val KEY_PACKAGES = "protected_packages"
    private const val KEY_EXACT_WATCH_ACTIVE = "exact_watch_active"
    private const val KEY_EXACT_WATCH_PACKAGE = "exact_watch_package"
    private const val KEY_EXACT_WATCH_BACKUP = "exact_watch_backup_packages"

    fun protectedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_PACKAGES, emptySet())
            ?.toSet()
            ?: emptySet()

    fun isProtected(context: Context, packageName: String): Boolean =
        protectedPackages(context).contains(packageName)

    fun setProtected(context: Context, packageName: String, protected: Boolean) {
        val next = protectedPackages(context).toMutableSet()
        if (protected) next.add(packageName) else next.remove(packageName)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PACKAGES, next)
            .apply()
    }

    fun replaceProtected(context: Context, packageNames: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PACKAGES, packageNames.filter { it.isNotBlank() }.toSet())
            .apply()
    }


    fun beginExactWatch(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (!prefs.getBoolean(KEY_EXACT_WATCH_ACTIVE, false)) {
            editor.putStringSet(KEY_EXACT_WATCH_BACKUP, protectedPackages(context))
        }
        editor
            .putBoolean(KEY_EXACT_WATCH_ACTIVE, true)
            .putString(KEY_EXACT_WATCH_PACKAGE, packageName)
            .putStringSet(KEY_PACKAGES, setOf(packageName))
            .apply()
    }

    fun exactWatchPackage(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_EXACT_WATCH_ACTIVE, false)) return null
        return prefs.getString(KEY_EXACT_WATCH_PACKAGE, null)
    }

    fun hasExactWatchBackup(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EXACT_WATCH_ACTIVE, false)

    fun restoreAfterExactWatch(context: Context): Set<String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_EXACT_WATCH_ACTIVE, false)) return null
        val restored = prefs.getStringSet(KEY_EXACT_WATCH_BACKUP, emptySet())
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
        prefs.edit()
            .putStringSet(KEY_PACKAGES, restored)
            .remove(KEY_EXACT_WATCH_ACTIVE)
            .remove(KEY_EXACT_WATCH_PACKAGE)
            .remove(KEY_EXACT_WATCH_BACKUP)
            .apply()
        return restored
    }

    fun protectedCount(context: Context): Int = protectedPackages(context).size
}
