package com.guardianlayer.app.firewall

import android.content.Context

object FirewallRuleStore {
    private const val PREFS = "guardian_firewall_rules"
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"

    fun blockedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_BLOCKED_PACKAGES, emptySet())
            ?.toSet()
            .orEmpty()

    fun isBlocked(context: Context, packageName: String): Boolean =
        blockedPackages(context).contains(packageName)

    fun setBlocked(context: Context, packageName: String, blocked: Boolean) {
        val updated = blockedPackages(context).toMutableSet().apply {
            if (blocked) add(packageName) else remove(packageName)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_BLOCKED_PACKAGES, updated)
            .apply()
    }

    fun blockedCount(context: Context): Int = blockedPackages(context).size
}
