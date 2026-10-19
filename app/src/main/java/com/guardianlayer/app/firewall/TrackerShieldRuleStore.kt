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
            .putBoolean(KEY_EXACT_WATCH_ACTIVE, true)
            .putString(KEY_EXACT_WATCH_PACKAGE, packageName)
        // A new watch must not snapshot or replace the saved Shielded set.
        // A backup left by an already-active older build is kept so Restore can
        // recover that selection once. A stale backup from a finished watch is dropped.
        if (!prefs.getBoolean(KEY_EXACT_WATCH_ACTIVE, false)) {
            editor.remove(KEY_EXACT_WATCH_BACKUP)
        }
        editor.commit()
    }

    fun exactWatchPackage(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_EXACT_WATCH_ACTIVE, false)) return null
        return prefs.getString(KEY_EXACT_WATCH_PACKAGE, null)?.takeIf { it.isNotBlank() }
    }

    fun hasExactWatchBackup(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EXACT_WATCH_ACTIVE, false)

    fun restoreAfterExactWatch(context: Context): Set<String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_EXACT_WATCH_ACTIVE, false)) return null
        val legacyBackup = prefs.getStringSet(KEY_EXACT_WATCH_BACKUP, null)
            ?.filter { it.isNotBlank() }
            ?.toSet()
        val editor = prefs.edit()
            .remove(KEY_EXACT_WATCH_ACTIVE)
            .remove(KEY_EXACT_WATCH_PACKAGE)
            .remove(KEY_EXACT_WATCH_BACKUP)
        // Older builds replaced the saved set with the watched app and kept the
        // real selection in this backup. Apply that once, then never again.
        if (legacyBackup != null) {
            editor.putStringSet(KEY_PACKAGES, legacyBackup)
        }
        editor.commit()
        return protectedPackages(context)
    }

    fun protectedCount(context: Context): Int = protectedPackages(context).size
}
