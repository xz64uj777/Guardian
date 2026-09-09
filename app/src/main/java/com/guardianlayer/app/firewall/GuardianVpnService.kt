package com.guardianlayer.app.firewall

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.guardianlayer.app.MainActivity
import com.guardianlayer.app.R
import com.guardianlayer.app.data.GuardianEventStore
import com.guardianlayer.app.data.GuardianStateStore
import com.guardianlayer.app.data.TrackerActivityStore
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicLong

class GuardianVpnService : VpnService() {

    enum class Mode { OFF, FIREWALL, TRACKER_SHIELD, LOCKDOWN }

    data class TrafficDrop(
        val timestamp: Long,
        val protocol: String,
        val destination: String,
        val port: Int?
    )

    data class DnsActivity(
        val timestamp: Long,
        val sourceLabel: String,
        val sourcePackage: String?,
        val domain: String,
        val category: String?,
        val provider: String?,
        val blockedByTrackerShield: Boolean
    )

    data class TrackerDomainStat(
        val domain: String,
        val category: String,
        val provider: String,
        val count: Long
    )

    data class TrafficSnapshot(
        val sessionMode: Mode,
        val packets: Long,
        val bytes: Long,
        val tcpPackets: Long,
        val udpPackets: Long,
        val otherPackets: Long,
        val lastActivityAt: Long,
        val recent: List<TrafficDrop>,
        val dnsQueries: Long,
        val trackerQueriesBlocked: Long,
        val dnsQueriesForwarded: Long,
        val dnsFailures: Long,
        val dnsUnsupportedPackets: Long,
        val uniqueTrackerDomainsBlocked: Int,
        val topTrackerBlocks: List<TrackerDomainStat>,
        val recentDns: List<DnsActivity>
    )

    companion object {
        const val ACTION_LOCKDOWN = "com.guardianlayer.app.action.LOCKDOWN"
        const val ACTION_FIREWALL = "com.guardianlayer.app.action.FIREWALL"
        const val ACTION_TRACKER_SHIELD = "com.guardianlayer.app.action.TRACKER_SHIELD"
        const val ACTION_STOP = "com.guardianlayer.app.action.STOP_VPN"

        private const val CHANNEL_ID = "guardian_lockdown"
        private const val NOTIFICATION_ID = 7701
        private const val MAX_RECENT_DROPS = 12
        private const val MAX_RECENT_DNS = 20
        private const val VIRTUAL_DNS = "10.77.0.2"
        private const val DNS_ATTEMPT_TIMEOUT_MS = 850
        private const val DNS_RESPONSE_BUFFER_SIZE = 8192
        private const val MAX_DNS_UPSTREAMS = 3

        @Volatile
        private var mode = Mode.OFF

        @Volatile
        private var lastSessionMode = Mode.OFF

        @Volatile
        private var trafficSourcePrefix = "Blocked traffic"

        private val droppedPackets = AtomicLong(0)
        private val droppedBytes = AtomicLong(0)
        private val tcpPackets = AtomicLong(0)
        private val udpPackets = AtomicLong(0)
        private val otherPackets = AtomicLong(0)
        private val lastActivityAt = AtomicLong(0)
        private val dnsQueries = AtomicLong(0)
        private val trackerQueriesBlocked = AtomicLong(0)
        private val dnsQueriesForwarded = AtomicLong(0)
        private val dnsFailures = AtomicLong(0)
        private val dnsUnsupportedPackets = AtomicLong(0)
        private val dnsRetryRecoveries = AtomicLong(0)
        private val dnsAttemptTimeouts = AtomicLong(0)
        private val dnsIgnoredResponses = AtomicLong(0)

        private val trafficLock = Any()
        private val recentDrops = ArrayDeque<TrafficDrop>()
        private val recentDns = ArrayDeque<DnsActivity>()
        private val trackerBlockCounts = linkedMapOf<String, TrackerDomainStat>()

        fun isRunning(): Boolean = mode != Mode.OFF
        fun currentMode(): Mode = mode

        fun trafficSnapshot(): TrafficSnapshot {
            val snapshotData = synchronized(trafficLock) {
                Triple(
                    recentDrops.toList().asReversed(),
                    recentDns.toList().asReversed(),
                    trackerBlockCounts.values.sortedByDescending { it.count }.take(8)
                )
            }
            return TrafficSnapshot(
                sessionMode = if (mode == Mode.OFF) lastSessionMode else mode,
                packets = droppedPackets.get(),
                bytes = droppedBytes.get(),
                tcpPackets = tcpPackets.get(),
                udpPackets = udpPackets.get(),
                otherPackets = otherPackets.get(),
                lastActivityAt = lastActivityAt.get(),
                recent = snapshotData.first,
                dnsQueries = dnsQueries.get(),
                trackerQueriesBlocked = trackerQueriesBlocked.get(),
                dnsQueriesForwarded = dnsQueriesForwarded.get(),
                dnsFailures = dnsFailures.get(),
                dnsUnsupportedPackets = dnsUnsupportedPackets.get(),
                uniqueTrackerDomainsBlocked = snapshotData.third.size.takeIf { trackerBlockCounts.size <= 8 }
                    ?: synchronized(trafficLock) { trackerBlockCounts.size },
                topTrackerBlocks = snapshotData.third,
                recentDns = snapshotData.second
            )
        }

        private fun resetTrafficStats(targetMode: Mode) {
            lastSessionMode = targetMode
            droppedPackets.set(0)
            droppedBytes.set(0)
            tcpPackets.set(0)
            udpPackets.set(0)
            otherPackets.set(0)
            lastActivityAt.set(0)
            dnsQueries.set(0)
            trackerQueriesBlocked.set(0)
            dnsQueriesForwarded.set(0)
            dnsFailures.set(0)
            dnsUnsupportedPackets.set(0)
            dnsRetryRecoveries.set(0)
            dnsAttemptTimeouts.set(0)
            dnsIgnoredResponses.set(0)
            TrackerShieldDiagnostics.reset()
            synchronized(trafficLock) {
                recentDrops.clear()
                recentDns.clear()
                trackerBlockCounts.clear()
            }
        }

        private fun recordDroppedPacket(packet: ByteArray, length: Int): DnsQueryInspector.Query? {
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
                    protocol = "$trafficSourcePrefix → ${destination.protocol}",
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

        private fun recordDnsActivity(activity: DnsActivity) {
            lastActivityAt.set(activity.timestamp)
            synchronized(trafficLock) {
                val last = recentDns.peekLast()
                val duplicate = last != null &&
                    last.domain == activity.domain &&
                    last.sourcePackage == activity.sourcePackage &&
                    last.blockedByTrackerShield == activity.blockedByTrackerShield &&
                    activity.timestamp - last.timestamp < 1200
                if (!duplicate) {
                    recentDns.addLast(activity)
                    while (recentDns.size > MAX_RECENT_DNS) recentDns.removeFirst()
                }
            }
        }

        private fun recordTrackerBlockCount(
            domain: String,
            classification: TrackerDomainClassifier.Classification
        ) {
            synchronized(trafficLock) {
                val previous = trackerBlockCounts[domain]
                trackerBlockCounts[domain] = TrackerDomainStat(
                    domain = domain,
                    category = classification.category,
                    provider = classification.provider,
                    count = (previous?.count ?: 0L) + 1L
                )
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetInput: FileInputStream? = null
    private var packetOutput: FileOutputStream? = null
    private var dnsSocket: DatagramSocket? = null
    @Volatile private var draining = false
    private var drainThread: Thread? = null

    @Volatile
    private var activeVpnPackages: List<String> = emptyList()

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
            ACTION_TRACKER_SHIELD -> {
                startTrackerShield()
                if (mode == Mode.TRACKER_SHIELD) START_STICKY else START_NOT_STICKY
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
        rebuildBlockingVpn(Mode.LOCKDOWN, emptySet())
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

        rebuildBlockingVpn(Mode.FIREWALL, blockedPackages)
    }

    private fun startTrackerShield() {
        val protectedPackages = TrackerShieldRuleStore.protectedPackages(this)
            .filterNot { it == packageName }
            .toSet()

        if (protectedPackages.isEmpty()) {
            stopVpn(logEvent = false)
            GuardianEventStore.append(
                this,
                "INFO",
                "Tracker Shield idle",
                "No apps are marked Shielded, so Guardian did not start Tracker Shield."
            )
            stopSelf()
            return
        }

        val upstream = resolveUpstreamDns()
        rebuildTrackerShield(protectedPackages, upstream)
    }

    @Synchronized
    private fun rebuildBlockingVpn(targetMode: Mode, blockedPackages: Set<String>) {
        if (vpnInterface != null || mode != Mode.OFF) stopVpn(logEvent = false)
        createNotificationChannel()

        val builder = Builder()
            .setSession(if (targetMode == Mode.LOCKDOWN) "Guardian Lock Down" else "Guardian Firewall")
            .setMtu(1500)
            .addAddress("10.77.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)

        runCatching {
            builder.addAddress("fd00:77::1", 128)
            builder.addRoute("::", 0)
        }

        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        val effectivePackages = mutableListOf<String>()
        if (targetMode == Mode.LOCKDOWN) {
            runCatching { builder.addDisallowedApplication(packageName) }
        } else {
            blockedPackages.forEach { blockedPackage ->
                if (runCatching { builder.addAllowedApplication(blockedPackage) }.isSuccess) {
                    effectivePackages += blockedPackage
                }
            }
            if (effectivePackages.isEmpty()) {
                failToStart(
                    "Firewall could not start",
                    "Guardian could not attach any selected apps to the local VPN."
                )
                return
            }
        }

        startForegroundCompat(targetMode, effectivePackages.size)
        vpnInterface = runCatching { builder.establish() }.getOrNull()
        if (vpnInterface == null) {
            failToStart(
                if (targetMode == Mode.LOCKDOWN) "Lock Down could not start" else "Firewall could not start",
                "Android did not create the Guardian VPN interface."
            )
            return
        }

        resetTrafficStats(targetMode)
        synchronized(domainLogLock) { sessionLoggedDomains.clear() }
        activeVpnPackages = effectivePackages.toList()
        trafficSourcePrefix = trafficSourceLabel(targetMode, effectivePackages)
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
                "Guardian is blocking network access for ${effectivePackages.size} selected app(s). Other apps continue using Android's normal network route."
            )
        }

        startBlockingPacketDrain()
    }

    @Synchronized
    private fun rebuildTrackerShield(protectedPackages: Set<String>, upstreamDns: InetAddress) {
        if (vpnInterface != null || mode != Mode.OFF) stopVpn(logEvent = false)
        createNotificationChannel()

        val builder = Builder()
            .setSession("Guardian Tracker Shield")
            .setMtu(1500)
            .addAddress("10.77.0.1", 32)
            .addDnsServer(VIRTUAL_DNS)
            .addRoute(VIRTUAL_DNS, 32)
            .setBlocking(true)

        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        val effectivePackages = mutableListOf<String>()
        protectedPackages.forEach { protectedPackage ->
            if (runCatching { builder.addAllowedApplication(protectedPackage) }.isSuccess) {
                effectivePackages += protectedPackage
            }
        }

        if (effectivePackages.isEmpty()) {
            failToStart(
                "Tracker Shield could not start",
                "Guardian could not attach any shielded apps to its DNS filtering VPN."
            )
            return
        }

        startForegroundCompat(Mode.TRACKER_SHIELD, effectivePackages.size)
        vpnInterface = runCatching { builder.establish() }.getOrNull()
        if (vpnInterface == null) {
            failToStart(
                "Tracker Shield could not start",
                "Android did not create the Guardian DNS filtering VPN interface."
            )
            return
        }

        resetTrafficStats(Mode.TRACKER_SHIELD)
        synchronized(domainLogLock) { sessionLoggedDomains.clear() }
        activeVpnPackages = effectivePackages.toList()
        trafficSourcePrefix = trafficSourceLabel(Mode.TRACKER_SHIELD, effectivePackages)
        mode = Mode.TRACKER_SHIELD
        GuardianStateStore.setLockdownActive(this, false)

        GuardianEventStore.append(
            this,
            "INFO",
            "Tracker Shield activated",
            "Guardian is filtering ordinary DNS for ${effectivePackages.size} selected app(s). Normal app traffic bypasses the VPN so the apps can stay online. Encrypted DNS, cached destinations, and direct-IP traffic may bypass this first DNS-only shield."
        )

        startTrackerDnsLoop(upstreamDns)
    }

    private fun failToStart(title: String, detail: String) {
        mode = Mode.OFF
        GuardianStateStore.setLockdownActive(this, false)
        GuardianEventStore.append(this, "ALERT", title, detail)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun trafficSourceLabel(targetMode: Mode, packages: List<String>): String {
        if (targetMode == Mode.LOCKDOWN) return "Device"
        if (packages.size != 1) {
            val noun = if (targetMode == Mode.TRACKER_SHIELD) "shielded apps" else "blocked apps"
            return "One of ${packages.size} $noun"
        }
        return appLabel(packages.single())
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun startBlockingPacketDrain() {
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
                        if (dnsQuery != null) recordBlockingDnsAttempt(dnsQuery)
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

    private fun startTrackerDnsLoop(upstreamDns: InetAddress) {
        val descriptor = vpnInterface?.fileDescriptor ?: return
        val input = FileInputStream(descriptor)
        val output = FileOutputStream(descriptor)
        val socket = DatagramSocket().apply { soTimeout = DNS_ATTEMPT_TIMEOUT_MS }
        if (!protect(socket)) {
            runCatching { socket.close() }
            GuardianEventStore.append(
                this,
                "ALERT",
                "Tracker Shield upstream protection failed",
                "Guardian could not exempt its DNS forwarding socket from the VPN. Tracker Shield was stopped to avoid a DNS loop."
            )
            stopVpn(logEvent = false)
            stopSelf()
            return
        }

        packetInput = input
        packetOutput = output
        dnsSocket = socket
        draining = true

        drainThread = Thread({
            val buffer = ByteArray(32767)
            try {
                while (draining) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue

                    val request = DnsPacketCodec.parseIpv4UdpRequest(buffer, count)
                    if (request == null) {
                        dnsUnsupportedPackets.incrementAndGet()
                        TrackerShieldDiagnostics.recordUnsupported(buffer, count)
                        continue
                    }

                    dnsQueries.incrementAndGet()
                    lastActivityAt.set(System.currentTimeMillis())
                    val classification = TrackerDomainClassifier.classify(request.domain)
                    val sourcePackage = activeVpnPackages.singleOrNull()
                    val sourceLabel = sourcePackage?.let(::appLabel)
                        ?: "One of ${activeVpnPackages.size} shielded apps"

                    val dnsResponse = if (classification != null) {
                        trackerQueriesBlocked.incrementAndGet()
                        recordTrackerBlockCount(request.domain, classification)
                        if (sourcePackage != null) {
                            TrackerActivityStore.recordExact(
                                context = this,
                                packageName = sourcePackage,
                                appLabel = sourceLabel,
                                domain = request.domain,
                                category = classification.category,
                                provider = classification.provider,
                                blocked = true
                            )
                        }
                        recordTrackerShieldDns(
                            request.domain,
                            classification,
                            sourcePackage,
                            sourceLabel
                        )
                        DnsPacketCodec.buildNxDomainPayload(request.dnsPayload)
                    } else {
                        val forwarded = forwardDns(socket, upstreamDns, request.dnsPayload)
                        if (forwarded != null) {
                            dnsQueriesForwarded.incrementAndGet()
                            if (sourcePackage != null) {
                                TrackerActivityStore.recordExact(
                                    context = this,
                                    packageName = sourcePackage,
                                    appLabel = sourceLabel,
                                    domain = request.domain,
                                    category = null,
                                    provider = null,
                                    blocked = false
                                )
                            }
                            recordDnsActivity(
                                DnsActivity(
                                    timestamp = System.currentTimeMillis(),
                                    sourceLabel = sourceLabel,
                                    sourcePackage = sourcePackage,
                                    domain = request.domain,
                                    category = null,
                                    provider = null,
                                    blockedByTrackerShield = false
                                )
                            )
                        } else {
                            dnsFailures.incrementAndGet()
                            TrackerShieldDiagnostics.recordFinalFailure(request.domain)
                        }
                        forwarded
                    }

                    if (dnsResponse != null) {
                        val responsePacket = DnsPacketCodec.buildIpv4UdpResponse(request, dnsResponse)
                        synchronized(output) {
                            output.write(responsePacket)
                            output.flush()
                        }
                    }
                }
            } catch (_: Throwable) {
                // Shutdown closes the TUN and DNS socket, interrupting reads/receives.
            } finally {
                if (packetInput === input) packetInput = null
                if (packetOutput === output) packetOutput = null
                if (dnsSocket === socket) dnsSocket = null
                runCatching { input.close() }
                runCatching { output.close() }
                runCatching { socket.close() }
            }
        }, "guardian-tracker-dns").apply {
            isDaemon = true
            start()
        }
    }

    private fun forwardDns(
        socket: DatagramSocket,
        upstreamDns: InetAddress,
        query: ByteArray
    ): ByteArray? {
        if (query.size < 2) return null

        val candidates = resolveUpstreamDnsCandidates(upstreamDns)
        candidates.forEachIndexed { index, resolver ->
            val response = queryDnsResolver(socket, resolver, query)
            if (response != null) {
                if (index > 0) {
                    dnsRetryRecoveries.incrementAndGet()
                    TrackerShieldDiagnostics.recordRetryRecovery()
                }
                return response
            }
        }
        return null
    }

    private fun queryDnsResolver(
        socket: DatagramSocket,
        resolver: InetAddress,
        query: ByteArray
    ): ByteArray? {
        return try {
            socket.send(DatagramPacket(query, query.size, resolver, 53))
            val deadline = System.currentTimeMillis() + DNS_ATTEMPT_TIMEOUT_MS

            while (draining) {
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                if (remaining <= 0) {
                    dnsAttemptTimeouts.incrementAndGet()
                    TrackerShieldDiagnostics.recordResolverAttemptTimeout()
                    return null
                }

                socket.soTimeout = remaining.coerceAtLeast(1)
                val buffer = ByteArray(DNS_RESPONSE_BUFFER_SIZE)
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    dnsAttemptTimeouts.incrementAndGet()
                    TrackerShieldDiagnostics.recordResolverAttemptTimeout()
                    return null
                }

                val matchingSource = response.address == resolver && response.port == 53
                val validHeader = response.length >= 12 && (buffer[2].toInt() and 0x80) != 0
                val matchingTransaction =
                    response.length >= 2 && buffer[0] == query[0] && buffer[1] == query[1]

                if (!matchingSource || !validHeader || !matchingTransaction) {
                    dnsIgnoredResponses.incrementAndGet()
                    TrackerShieldDiagnostics.recordIgnoredResponse()
                    continue
                }

                return buffer.copyOf(response.length)
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun recordBlockingDnsAttempt(query: DnsQueryInspector.Query) {
        val sourcePackage = activeVpnPackages.singleOrNull()
        val sourceLabel = when {
            mode == Mode.LOCKDOWN -> "Blocked device traffic"
            sourcePackage != null -> "${appLabel(sourcePackage)} ($sourcePackage)"
            activeVpnPackages.isNotEmpty() -> "One of ${activeVpnPackages.size} blocked apps"
            else -> "Blocked traffic"
        }
        val classification = TrackerDomainClassifier.classify(query.domain)

        recordDnsActivity(
            DnsActivity(
                timestamp = System.currentTimeMillis(),
                sourceLabel = sourceLabel,
                sourcePackage = sourcePackage,
                domain = query.domain,
                category = classification?.category,
                provider = classification?.provider,
                blockedByTrackerShield = false
            )
        )

        if (!rememberDomainForTimeline(query.domain)) return

        if (classification != null) {
            GuardianEventStore.append(
                this,
                "REVIEW",
                "${classification.category} service observed",
                "$sourceLabel requested ${query.domain} over plaintext DNS/${query.transport}. Guardian's local intelligence matches ${classification.provider} (${classification.matchedDomain}), ${classification.explanation}. The packet was already blocked by your app rule. This is a privacy/telemetry signal, not a malware verdict."
            )
        } else {
            GuardianEventStore.append(
                this,
                "INFO",
                "Blocked DNS request",
                "$sourceLabel requested ${query.domain} over plaintext DNS/${query.transport}. Guardian blocked the packet. Cached lookups and encrypted DNS such as DoH/DoT may not be visible here."
            )
        }
    }

    private fun recordTrackerShieldDns(
        domain: String,
        classification: TrackerDomainClassifier.Classification,
        sourcePackage: String?,
        sourceLabel: String
    ) {
        recordDnsActivity(
            DnsActivity(
                timestamp = System.currentTimeMillis(),
                sourceLabel = sourceLabel,
                sourcePackage = sourcePackage,
                domain = domain,
                category = classification.category,
                provider = classification.provider,
                blockedByTrackerShield = true
            )
        )

        if (!rememberDomainForTimeline(domain)) return
        val source = sourcePackage?.let { "$sourceLabel ($it)" } ?: sourceLabel
        GuardianEventStore.append(
            this,
            "REVIEW",
            "Tracker request blocked",
            "$source requested $domain. Guardian matched ${classification.provider} / ${classification.category} and returned a local DNS block response while leaving the app's other traffic online. This is a privacy classification, not a malware verdict."
        )
    }

    private fun rememberDomainForTimeline(domain: String): Boolean = synchronized(domainLogLock) {
        if (sessionLoggedDomains.contains(domain)) {
            false
        } else {
            if (sessionLoggedDomains.size >= 30) {
                sessionLoggedDomains.firstOrNull()?.let { sessionLoggedDomains.remove(it) }
            }
            sessionLoggedDomains.add(domain)
            true
        }
    }

    private fun resolveUpstreamDns(): InetAddress {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val fromNetwork = runCatching {
            val network = connectivity.activeNetwork ?: return@runCatching null
            connectivity.getLinkProperties(network)
                ?.dnsServers
                ?.firstOrNull { it is Inet4Address && it.hostAddress != VIRTUAL_DNS }
        }.getOrNull()
        return fromNetwork ?: InetAddress.getByName("1.1.1.1")
    }

    private fun resolveUpstreamDnsCandidates(initial: InetAddress): List<InetAddress> {
        val candidates = LinkedHashSet<InetAddress>()
        val connectivity = getSystemService(ConnectivityManager::class.java)

        runCatching {
            val network = connectivity.activeNetwork ?: return@runCatching
            connectivity.getLinkProperties(network)
                ?.dnsServers
                ?.filter { it is Inet4Address && it.hostAddress != VIRTUAL_DNS }
                ?.forEach { candidates.add(it) }
        }

        candidates.add(initial)
        runCatching { candidates.add(InetAddress.getByName("1.1.1.1")) }
        runCatching { candidates.add(InetAddress.getByName("8.8.8.8")) }
        return candidates.take(MAX_DNS_UPSTREAMS)
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

        val output = packetOutput
        packetOutput = null
        runCatching { output?.close() }

        val socket = dnsSocket
        dnsSocket = null
        runCatching { socket?.close() }

        val tun = vpnInterface
        vpnInterface = null
        runCatching { tun?.close() }

        drainThread?.interrupt()
        drainThread = null
        activeVpnPackages = emptyList()
        synchronized(domainLogLock) { sessionLoggedDomains.clear() }
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (logEvent && wasActive) {
            val snapshot = trafficSnapshot()
            when (previousMode) {
                Mode.TRACKER_SHIELD -> {
                    val top = if (snapshot.topTrackerBlocks.isEmpty()) {
                        "No classified tracker domains were blocked."
                    } else {
                        snapshot.topTrackerBlocks.take(5).joinToString("; ") {
                            "${it.domain} (${it.provider}) ×${it.count}"
                        }
                    }
                    val diagnostics = TrackerShieldDiagnostics.snapshot()
                    val unsupportedBreakdown =
                        "TCP-DNS ${diagnostics.unsupportedIpv4TcpDns}, IPv6 ${diagnostics.unsupportedIpv6}, " +
                            "UDP-other ${diagnostics.unsupportedIpv4UdpOther}, IPv4-other ${diagnostics.unsupportedIpv4Other}, malformed ${diagnostics.unsupportedMalformed}"
                    val failedDomains = if (diagnostics.failedDomains.isEmpty()) {
                        "none"
                    } else {
                        diagnostics.failedDomains.joinToString("; ") { "${it.domain} ×${it.count}" }
                    }
                    GuardianEventStore.append(
                        this,
                        "INFO",
                        "Tracker Shield stopped",
                        "Guardian restored normal DNS handling after blocking ${snapshot.trackerQueriesBlocked} tracker request(s) across ${snapshot.uniqueTrackerDomainsBlocked} unique tracker domain(s), forwarding ${snapshot.dnsQueriesForwarded}, recording ${snapshot.dnsFailures} final upstream DNS failure(s), and ignoring ${snapshot.dnsUnsupportedPackets} unsupported packet(s). Unsupported breakdown: $unsupportedBreakdown. DNS retry diagnostics: ${diagnostics.retryRecoveries} query(s) recovered by a fallback resolver, ${diagnostics.resolverAttemptTimeouts} resolver attempt timeout(s), and ${diagnostics.ignoredResponses} stale/malformed response(s) ignored. Final failed domains: $failedDomains. Top blocked: $top"
                    )
                }
                Mode.LOCKDOWN, Mode.FIREWALL -> GuardianEventStore.append(
                    this,
                    "INFO",
                    if (previousMode == Mode.LOCKDOWN) "Lock Down stopped" else "Firewall stopped",
                    "Guardian restored Android networking after dropping ${snapshot.packets} packet(s) / ${snapshot.bytes} byte(s) in this session."
                )
                Mode.OFF -> Unit
            }
        }
    }

    private fun startForegroundCompat(targetMode: Mode, appCount: Int) {
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

        val title = when (targetMode) {
            Mode.LOCKDOWN -> "Guardian Lock Down is active"
            Mode.FIREWALL -> "Guardian Firewall is active"
            Mode.TRACKER_SHIELD -> "Guardian Tracker Shield is active"
            Mode.OFF -> "Guardian"
        }
        val body = when (targetMode) {
            Mode.LOCKDOWN -> "Device network traffic is blocked until you stop Lock Down."
            Mode.FIREWALL -> "Blocking network access for $appCount selected app(s)."
            Mode.TRACKER_SHIELD -> "Filtering visible DNS trackers for $appCount selected app(s) while normal traffic stays online."
            Mode.OFF -> "Guardian network protection."
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_guardian)
            .setContentTitle(title)
            .setContentText(body)
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
