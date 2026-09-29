package com.guardianlayer.app.firewall

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.guardianlayer.app.ProtectionAppsActivity
import com.guardianlayer.app.R
import com.guardianlayer.app.data.TrackerActivityStore
import java.util.concurrent.TimeUnit

/**
 * Conservative, opt-in notifications for substantial tracker bursts that
 * Guardian can attribute to exactly one app. Ordinary allowed traffic never
 * triggers an alert, and each app is rate-limited.
 */
object TrackerBurstAlertStore {
    private const val PREFS = "guardian_tracker_burst_alerts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_PREFIX = "last_alert_"
    private const val CHANNEL_ID = "guardian_tracker_bursts"
    private const val BLOCK_THRESHOLD = 12L
    private val WINDOW_MS = TimeUnit.MINUTES.toMillis(1)
    private val COOLDOWN_MS = TimeUnit.MINUTES.toMillis(15)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun maybeNotify(context: Context, packageName: String, appLabel: String) {
        if (!isEnabled(context)) return
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val now = System.currentTimeMillis()
        val recent = TrackerActivityStore.recentWindow(
            context,
            packageName,
            now - WINDOW_MS
        )
        if (recent.blockedDecisions < BLOCK_THRESHOLD) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastAt = prefs.getLong(KEY_LAST_PREFIX + packageName, 0L)
        if (now - lastAt < COOLDOWN_MS) return

        createChannel(context)
        val provider = recent.blockedProviders.firstOrNull()?.name
        val domain = recent.blockedDomains.firstOrNull()?.name
        val lead = when {
            provider != null && domain != null -> "$provider · $domain"
            provider != null -> provider
            domain != null -> domain
            else -> "classified tracker traffic"
        }

        val openActivity = PendingIntent.getActivity(
            context,
            packageName.hashCode() and Int.MAX_VALUE,
            Intent(context, ProtectionAppsActivity::class.java)
                .putExtra(ProtectionAppsActivity.EXTRA_MODE, ProtectionAppsActivity.MODE_SHIELD)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_guardian)
            .setContentTitle("Guardian blocked a tracker burst from $appLabel")
            .setContentText("${recent.blockedDecisions} blocked in the last minute · $lead")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Guardian blocked ${recent.blockedDecisions} classified tracker requests from $appLabel in the last minute. Leading signal: $lead. This is a privacy signal, not a malware finding."
                )
            )
            .setContentIntent(openActivity)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(
            7800 + ((packageName.hashCode() and Int.MAX_VALUE) % 500),
            notification
        )
        prefs.edit().putLong(KEY_LAST_PREFIX + packageName, now).apply()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Guardian tracker burst alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Rate-limited alerts for substantial tracker bursts Guardian can attribute to one app."
            }
        )
    }
}
