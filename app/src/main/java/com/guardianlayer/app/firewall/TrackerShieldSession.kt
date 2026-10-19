package com.guardianlayer.app.firewall

/**
 * Decides which packages enter the Tracker Shield VPN.
 *
 * The saved Shielded set is the user's configuration. An Exact Watch is only a
 * temporary session: the tunnel carries that one app so DNS can be attributed,
 * and the saved set is left alone.
 */
object TrackerShieldSession {
    fun vpnPackages(saved: Set<String>, exactWatchPackage: String?, selfPackage: String): Set<String> {
        val watch = exactWatchPackage?.trim()?.takeIf { it.isNotEmpty() && it != selfPackage }
        if (watch != null) return setOf(watch)
        return saved.filter { it.isNotBlank() && it != selfPackage }.toSet()
    }
}
