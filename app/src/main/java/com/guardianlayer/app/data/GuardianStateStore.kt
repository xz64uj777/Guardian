package com.guardianlayer.app.data

import android.content.Context

object GuardianStateStore {
    private const val PREFS = "guardian_state"
    private const val KEY_LOCKDOWN = "lockdown_active"

    fun isLockdownActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCKDOWN, false)

    fun setLockdownActive(context: Context, active: Boolean) {
        // Lock Down is safety-critical UI state. Commit synchronously so the
        // app cannot briefly report the old state after the VPN is torn down.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOCKDOWN, active)
            .commit()
    }
}
