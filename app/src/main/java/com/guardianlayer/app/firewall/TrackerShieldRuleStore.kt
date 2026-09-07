package com.guardianlayer.app.firewall

import android.content.Context

/** Stores the apps the user wants Guardian to protect with DNS-level Tracker Shield. */
object TrackerShieldRuleStore {
    private const val PREFS = "guardian_tracker_shield_rules"
    private const val KEY_PACKAGES = "protected_packages"

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

    fun protectedCount(context: Context): Int = protectedPackages(context).size
}
