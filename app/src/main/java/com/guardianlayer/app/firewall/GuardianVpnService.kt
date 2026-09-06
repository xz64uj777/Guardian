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
import java.util.ArrayDeque
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicLong

class GuardianVpnService : VpnService() {

    enum class Mode { OFF, FIREWALL, LOCKDOWN }

    data class TrafficDrop(
        val timestamp: Long,
        val protocol: String,
        val destination: String,
        val port: Int?
    )

    data class TrafficSnapshot(
        val packets: Long,
        val bytes: Long,
        val tcpPackets: Long,
        val udpPackets: Long,
        val otherPackets: Long,
        val lastActivityAt: Long,
        val recent: List<TrafficDrop>
    )

    companion object {
        const val ACTION_LOCKDOWN = "com.guardianlayer.app.action.LOCKDOWN"
        const val ACTION_FIREWALL = "com.guardianlayer.app.action.FIREWALL"
        const val ACTION_STOP = "com.guardianlayer.app.action.STOP_VPN"
        private const val CHANNEL_ID = "guardian_lockdown"
        private const val NOTIFICATION_ID = 7701
        private const val MAX_RECENT_DROPS = 12

        @Volatile
        private var mode = Mode.OFF

        private val droppedPackets = AtomicLong(0)
        private val droppedBytes = AtomicLong(0)
        private val tcpPackets = AtomicLong(0)
        private val udpPackets = AtomicLong(0)
        private val otherPackets = AtomicLong(0)
        private val lastActivityAt = AtomicLong(0)
        private val trafficLock = Any()
        private val recentDrops = ArrayDeque<TrafficDrop>()

        fun isRunning(): Boolean = mode != Mode.OFF
        fun currentMode(): Mode = mode

        fun trafficSnapshot(): TrafficSnapshot {
            val recent = synchronized(trafficLock) {
                recentDrops.toList().asReversed()
            }
            return TrafficSnapshot(
                packets = droppedPackets.get(),
                bytes = droppedBytes.get(),
                tcpPackets = tcpPackets.get(),
                udpPackets = udpPackets.get(),
                otherPackets = otherPackets.get(),
                lastActivityAt = lastActivityAt.get(),
                recent = recent
            )
        }

        private fun resetTrafficStats() {
            droppedPackets.set(0)
            droppedBytes.set(0)
            tcpPackets.set(0)
            udpPackets.set(0)
            otherPackets.set(0)
            lastActivityAt.set(0)
            synchronized(trafficLock) { recentDrops.clear() }
        }

        private fun recordDroppedPacket(
            packet: ByteArray,
            length: Int
        ): DnsQueryInspector.Query? {
            if (length <= 0) return null
            droppedPackets.incrementAndGet()
            droppedBytes.addAndGet(length.toLong())
            val now = System.currentTimeMillis()
            lastActivityAt.set(now)

            val destination = PacketInspector.inspect(packet, length)
            when (destination?.protocol) {
                "TCP" -> tcpPackets.incrementAndGet()
                "UDP" -> udpPackets.incrementAndGet()
                else -> otherPackets.incrementAndGet()
            }

            if (destination != null) {
                val drop = TrafficDrop(
                    timestamp = now,
                    protocol = destination.protocol,
                    destination = destination.address,
                    port = destination.port
                )
                synchronized(trafficLock) {
                    val last = recentDrops.peekLast()
                    val sameEndpoint = last != null &&
                        last.protocol == drop.protocol &&
                        last.destination == drop.destination &&
                        last.port == drop.port &&
                        now - last.timestamp < 1200
                    if (!sameEndpoint) {
                        recentDrops.addLast(drop)
                        while (recentDrops.size > MAX_RECENT_DROPS) recentDrops.removeFirst()
                    }
                }
            }

            return DnsQueryInspector.inspect(packet, length)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetInput: FileInputStream? = null
    @Volatile private var draining = false
    private var drainThread: Thread? = null

    @Volatile
    private var activeBlockedPackages: List<String> = emptyList()

    private val domainLogLock = Any()
    private val sessionLoggedDomains = LinkedHashSet<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpn(logEvent = true)
                stopSelfResult(startId)
                START_NOT_STICKY
            }
            ACTION_LOCKDOWN -> {
                startLockdown()
                START_STICKY
            }
            ACTION_FIREWALL -> {
                startSelectiveFirewall()
                if (mode == Mode.FIREWALL) START_STICKY else START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        stopVpn(logEvent = true)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn(logEvent = false)
        super.onDestroy()
    }

    private fun startLockdown() {
        rebuildVpn(Mode.LOCKDOWN, emptySet())
    }

    private fun startSelectiveFirewall() {
        val blockedPackages = FirewallRuleStore.blockedPackages(this)
            .filterNot { it == packageName }
            .toSet()

        if (blockedPackages.isEmpty()) {
            stopVpn(logEvent = false)
            GuardianEventStore.append(
                this,
                "INFO",
                "Firewall idle",
                "No apps are marked Blocked, so Guardian did not start a VPN firewall."
            )
            stopSelf()
            return
        }

        rebuildVpn(Mode.FIREWALL, blockedPackages)
    }

    @Synchronized
    private fun rebuildVpn(targetMode: Mode, blockedPackages: Set<String>) {
        if (vpnInterface != null || mode != Mode.OFF) stopVpn(logEvent = false)

        createNotificationChannel()

        val builder = Builder()
            .setSession(if (targetMode == Mode.LOCKDOWN) "Guardian Lock Down" else "Guardian Firewall")
            .setMtu(1500)
            .addAddress("10.77.0.1", 32)
            .addRoute("0.0.0.0", 0)

        runCatching {
            builder.addAddress("fd00:77::1", 128)
            builder.addRoute("::", 0)
        }

        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        val effectiveBlockedPackages = mutableListOf<String>()
        if (targetMode == Mode.LOCKDOWN) {
            runCatching { builder.addDisallowedApplication(packageName) }
        } else {
            blockedPackages.forEach { blockedPackage ->
                if (runCatching { builder.addAllowedApplication(blockedPackage) }.isSuccess) {
                    effectiveBlockedPackages += blockedPackage
                }
            }
            if (effectiveBlockedPackages.isEmpty()) {
                GuardianEventStore.append(
                    this,
                    "ALERT",
                    "Firewall could not start",
                    "Guardian could not attach any selected apps to the local VPN."
                )
                mode = Mode.OFF
                GuardianStateStore.setLockdownActive(this, false)
                stopSelf()
                return
            }
        }

        startForegroundCompat(targetMode, effectiveBlockedPackages.size)

        vpnInterface = runCatching { builder.establish() }.getOrNull()
        if (vpnInterface == null) {
            mode = Mode.OFF
            GuardianStateStore.setLockdownActive(this, false)
            GuardianEventStore.append(
                this,
                "ALERT",
                if (targetMode == Mode.LOCKDOWN) "Lock Down could not start" else "Firewall could not start",
                "Android did not create the Guardian VPN interface."
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        resetTrafficStats()
        synchronized(domainLogLock) { sessionLoggedDomains.clear() }
        activeBlockedPackages = effectiveBlockedPackages.toList()
        mode = targetMode
        GuardianStateStore.setLockdownActive(this, targetMode == Mode.LOCKDOWN)

        if (targetMode == Mode.LOCKDOWN) {
            GuardianEventStore.append(
                this,
                "CRITICAL",
                "Lock Down activated",
                "Guardian is blocking network traffic for every app except Guardian itself."
            )
        } else {
            GuardianEventStore.append(
                this,
                "INFO",
                "Selective firewall activated",
                "Guardian is blocking network access for ${effectiveBlockedPackages.size} selected app(s). Other apps continue using Android's normal network route."
            )
        }

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
                    if (count > 0) {
                        val dnsQuery = recordDroppedPacket(buffer, count)
                        if (dnsQuery != null) recordDnsAttempt(dnsQuery)
                    }
                }
            } catch (_: Throwable) {
                // Closing the TUN descriptor during shutdown interrupts reads.
            } finally {
                if (packetInput === input) packetInput = null
                runCatching { input.close() }
            }
        }, "guardian-vpn-drain").apply {
            isDaemon = true
            start()
        }
    }

    private fun recordDnsAttempt(query: DnsQueryInspector.Query) {
        val shouldLog = synchronized(domainLogLock) {
            if (sessionLoggedDomains.contains(query.domain)) {
                false
            } else {
                if (sessionLoggedDomains.size >= 30) {
                    val oldest = sessionLoggedDomains.firstOrNull()
                    if (oldest != null) sessionLoggedDomains.remove(oldest)
                }
                sessionLoggedDomains.add(query.domain)
                true
            }
        }
        if (!shouldLog) return

        val source = blockedTrafficSourceDescription()
        GuardianEventStore.append(
            this,
            "INFO",
            "Blocked DNS request",
            "$source requested ${query.domain} over plaintext DNS/${query.transport}. Guardian blocked the packet. Cached lookups and encrypted DNS such as DoH/DoT may not be visible here."
        )
    }

    private fun blockedTrafficSourceDescription(): String {
        if (mode == Mode.LOCKDOWN) return "Blocked device traffic"

        val packages = activeBlockedPackages
        if (packages.size != 1) {
            return if (packages.isEmpty()) {
                "Blocked traffic"
            } else {
                "One of ${packages.size} blocked apps"
            }
        }

        val blockedPackage = packages.single()
        val label = runCatching {
            val info = packageManager.getApplicationInfo(blockedPackage, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(blockedPackage)
        return "$label ($blockedPackage)"
    }

    @Synchronized
    private fun stopVpn(logEvent: Boolean) {
        val previousMode = mode
        val wasActive = previousMode != Mode.OFF || vpnInterface != null

        mode = Mode.OFF
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
        activeBlockedPackages = emptyList()
        synchronized(domainLogLock) { sessionLoggedDomains.clear() }
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (logEvent && wasActive) {
            val snapshot = trafficSnapshot()
            GuardianEventStore.append(
                this,
                "INFO",
                if (previousMode == Mode.LOCKDOWN) "Lock Down stopped" else "Firewall stopped",
                "Guardian restored Android networking after dropping ${snapshot.packets} packet(s) / ${snapshot.bytes} byte(s) in this session."
            )
        }
    }

    private fun startForegroundCompat(targetMode: Mode, blockedCount: Int) {
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
            .setContentTitle(
                if (targetMode == Mode.LOCKDOWN) "Guardian Lock Down is active"
                else "Guardian Firewall is active"
            )
            .setContentText(
                if (targetMode == Mode.LOCKDOWN) "Device network traffic is blocked until you stop Lock Down."
                else "Blocking network access for $blockedCount selected app(s)."
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_guardian, "Stop", stopIntent)
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
