package com.guardianlayer.app.firewall

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.guardianlayer.app.MainActivity
import com.guardianlayer.app.R
import com.guardianlayer.app.data.GuardianEventStore
import com.guardianlayer.app.data.GuardianStateStore
import java.io.FileInputStream

class GuardianVpnService : VpnService() {

    companion object {
        const val ACTION_LOCKDOWN = "com.guardianlayer.app.action.LOCKDOWN"
        const val ACTION_STOP = "com.guardianlayer.app.action.STOP_LOCKDOWN"
        private const val CHANNEL_ID = "guardian_lockdown"
        private const val NOTIFICATION_ID = 7701

        @Volatile
        private var running = false

        fun isRunning(): Boolean = running
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetInput: FileInputStream? = null
    @Volatile private var draining = false
    private var drainThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopLockdown(logEvent = true)
                stopSelfResult(startId)
                START_NOT_STICKY
            }
            ACTION_LOCKDOWN -> {
                startLockdown()
                START_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        stopLockdown(logEvent = true)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopLockdown(logEvent = GuardianStateStore.isLockdownActive(this) || running)
        super.onDestroy()
    }

    private fun startLockdown() {
        if (vpnInterface != null) return

        createNotificationChannel()
        startForegroundCompat()

        val builder = Builder()
            .setSession("Guardian Lock Down")
            .setMtu(1500)
            .addAddress("10.77.0.1", 32)
            .addRoute("0.0.0.0", 0)

        runCatching {
            builder.addAddress("fd00:77::1", 128)
            builder.addRoute("::", 0)
        }

        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false)
        }

        runCatching { builder.addDisallowedApplication(packageName) }

        vpnInterface = runCatching { builder.establish() }.getOrNull()
        if (vpnInterface == null) {
            running = false
            GuardianStateStore.setLockdownActive(this, false)
            GuardianEventStore.append(
                this,
                "ALERT",
                "Lock Down could not start",
                "Android did not create the Guardian VPN interface."
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        running = true
        GuardianStateStore.setLockdownActive(this, true)
        GuardianEventStore.append(
            this,
            "CRITICAL",
            "Lock Down activated",
            "Guardian is routing device traffic into its local VPN interface and discarding it."
        )
        startPacketDrain()
    }

    private fun startPacketDrain() {
        val descriptor = vpnInterface?.fileDescriptor ?: return
        val input = FileInputStream(descriptor)
        packetInput = input
        draining = true

        drainThread = Thread({
            val buffer = ByteArray(32767)
            try {
                while (draining) {
                    val count = input.read(buffer)
                    if (count < 0) break
                }
            } catch (_: Throwable) {
                // Closing the VPN descriptor during shutdown is expected to
                // interrupt a blocking read. Nothing needs to be surfaced.
            } finally {
                if (packetInput === input) packetInput = null
                runCatching { input.close() }
            }
        }, "guardian-lockdown-drain").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    private fun stopLockdown(logEvent: Boolean) {
        val wasActive = GuardianStateStore.isLockdownActive(this) || vpnInterface != null || running

        // Clear both runtime and persisted state before touching the blocking
        // packet reader. The UI can never remain latched merely because Android
        // is still finishing TUN teardown.
        running = false
        GuardianStateStore.setLockdownActive(this, false)
        draining = false

        val input = packetInput
        packetInput = null
        runCatching { input?.close() }

        val tun = vpnInterface
        vpnInterface = null
        runCatching { tun?.close() }

        drainThread?.interrupt()
        drainThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (logEvent && wasActive) {
            GuardianEventStore.append(
                this,
                "INFO",
                "Lock Down stopped",
                "Guardian closed its local VPN interface and returned network routing to Android."
            )
        }
    }

    private fun startForegroundCompat() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GuardianVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_guardian)
            .setContentTitle("Guardian Lock Down is active")
            .setContentText("Device network traffic is blocked until you stop Lock Down.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_guardian, "Stop Lock Down", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.lockdown_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.lockdown_channel_description)
        }
        manager.createNotificationChannel(channel)
    }
}
