package com.guardianlayer.app.firewall

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Session-local diagnostics for Tracker Shield. These counters explain packets
 * that do not fit Guardian's current IPv4/UDP DNS forwarding path. A small,
 * bounded set of destination metadata is retained in memory for the current
 * session only; packet payloads are never stored.
 */
object TrackerShieldDiagnostics {

    data class FailedDomain(
        val domain: String,
        val count: Long
    )

    data class UnsupportedSample(
        val kind: String,
        val protocol: String,
        val address: String,
        val port: Int?,
        val count: Long,
        val lastSeenAt: Long
    )

    data class Snapshot(
        val unsupportedIpv4TcpDns: Long,
        val unsupportedIpv4TcpOther: Long,
        val unsupportedIpv4UdpOther: Long,
        val unsupportedIpv4Icmp: Long,
        val unsupportedIpv4Fragments: Long,
        /** Aggregate of all non-DNS IPv4 traffic except UDP-other, kept for compatibility. */
        val unsupportedIpv4Other: Long,
        val unsupportedIpv6: Long,
        val unsupportedMalformed: Long,
        val retryRecoveries: Long,
        val resolverAttemptTimeouts: Long,
        val ignoredResponses: Long,
        val failedDomains: List<FailedDomain>,
        val unsupportedSamples: List<UnsupportedSample>
    ) {
        val unsupportedTotal: Long
            get() = unsupportedIpv4TcpDns + unsupportedIpv4UdpOther +
                unsupportedIpv4Other + unsupportedIpv6 + unsupportedMalformed
    }

    private val unsupportedIpv4TcpDns = AtomicLong(0)
    private val unsupportedIpv4TcpOther = AtomicLong(0)
    private val unsupportedIpv4UdpOther = AtomicLong(0)
    private val unsupportedIpv4Icmp = AtomicLong(0)
    private val unsupportedIpv4Fragments = AtomicLong(0)
    private val unsupportedIpv4Other = AtomicLong(0)
    private val unsupportedIpv6 = AtomicLong(0)
    private val unsupportedMalformed = AtomicLong(0)
    private val retryRecoveries = AtomicLong(0)
    private val resolverAttemptTimeouts = AtomicLong(0)
    private val ignoredResponses = AtomicLong(0)

    private val failedDomainLock = Any()
    private val failedDomainCounts = linkedMapOf<String, Long>()

    private val sampleLock = Any()
    private val unsupportedSampleCounts = linkedMapOf<String, UnsupportedSample>()
    private const val MAX_UNSUPPORTED_SAMPLE_KEYS = 16

    fun reset() {
        unsupportedIpv4TcpDns.set(0)
        unsupportedIpv4TcpOther.set(0)
        unsupportedIpv4UdpOther.set(0)
        unsupportedIpv4Icmp.set(0)
        unsupportedIpv4Fragments.set(0)
        unsupportedIpv4Other.set(0)
        unsupportedIpv6.set(0)
        unsupportedMalformed.set(0)
        retryRecoveries.set(0)
        resolverAttemptTimeouts.set(0)
        ignoredResponses.set(0)
        synchronized(failedDomainLock) { failedDomainCounts.clear() }
        synchronized(sampleLock) { unsupportedSampleCounts.clear() }
    }

    fun recordUnsupported(packet: ByteArray, length: Int) {
        val kind = classifyUnsupported(packet, length)
        when (kind) {
            UnsupportedKind.IPV4_TCP_DNS -> unsupportedIpv4TcpDns.incrementAndGet()
            UnsupportedKind.IPV4_TCP_OTHER -> {
                unsupportedIpv4TcpOther.incrementAndGet()
                unsupportedIpv4Other.incrementAndGet()
            }
            UnsupportedKind.IPV4_UDP_OTHER -> unsupportedIpv4UdpOther.incrementAndGet()
            UnsupportedKind.IPV4_ICMP -> {
                unsupportedIpv4Icmp.incrementAndGet()
                unsupportedIpv4Other.incrementAndGet()
            }
            UnsupportedKind.IPV4_FRAGMENT -> {
                unsupportedIpv4Fragments.incrementAndGet()
                unsupportedIpv4Other.incrementAndGet()
            }
            UnsupportedKind.IPV4_OTHER -> unsupportedIpv4Other.incrementAndGet()
            UnsupportedKind.IPV6 -> unsupportedIpv6.incrementAndGet()
            UnsupportedKind.MALFORMED -> unsupportedMalformed.incrementAndGet()
        }
        recordUnsupportedSample(kind, packet, length)
    }

    fun recordRetryRecovery() {
        retryRecoveries.incrementAndGet()
    }

    fun recordResolverAttemptTimeout() {
        resolverAttemptTimeouts.incrementAndGet()
    }

    fun recordIgnoredResponse() {
        ignoredResponses.incrementAndGet()
    }

    fun recordFinalFailure(domain: String) {
        val normalized = domain.trim().trimEnd('.').lowercase(Locale.US)
        if (normalized.isBlank()) return
        synchronized(failedDomainLock) {
            failedDomainCounts[normalized] = (failedDomainCounts[normalized] ?: 0L) + 1L
            if (failedDomainCounts.size > 20) {
                val smallest = failedDomainCounts.minByOrNull { it.value }?.key
                if (smallest != null) failedDomainCounts.remove(smallest)
            }
        }
    }

    fun snapshot(): Snapshot {
        val failures = synchronized(failedDomainLock) {
            failedDomainCounts.entries
                .sortedByDescending { it.value }
                .take(6)
                .map { FailedDomain(it.key, it.value) }
        }
        val samples = synchronized(sampleLock) {
            unsupportedSampleCounts.values
                .sortedWith(
                    compareByDescending<UnsupportedSample> { it.count }
                        .thenByDescending { it.lastSeenAt }
                )
                .take(8)
        }
        return Snapshot(
            unsupportedIpv4TcpDns = unsupportedIpv4TcpDns.get(),
            unsupportedIpv4TcpOther = unsupportedIpv4TcpOther.get(),
            unsupportedIpv4UdpOther = unsupportedIpv4UdpOther.get(),
            unsupportedIpv4Icmp = unsupportedIpv4Icmp.get(),
            unsupportedIpv4Fragments = unsupportedIpv4Fragments.get(),
            unsupportedIpv4Other = unsupportedIpv4Other.get(),
            unsupportedIpv6 = unsupportedIpv6.get(),
            unsupportedMalformed = unsupportedMalformed.get(),
            retryRecoveries = retryRecoveries.get(),
            resolverAttemptTimeouts = resolverAttemptTimeouts.get(),
            ignoredResponses = ignoredResponses.get(),
            failedDomains = failures,
            unsupportedSamples = samples
        )
    }

    private fun recordUnsupportedSample(kind: UnsupportedKind, packet: ByteArray, length: Int) {
        val destination = PacketInspector.inspect(packet, length)
        val address = destination?.address ?: "unparsed"
        val port = destination?.port
        val kindLabel = sampleKindLabel(kind, address, port)
        val protocol = destination?.protocol ?: when (kind) {
            UnsupportedKind.IPV4_TCP_DNS, UnsupportedKind.IPV4_TCP_OTHER -> "TCP"
            UnsupportedKind.IPV4_UDP_OTHER -> "UDP"
            UnsupportedKind.IPV4_ICMP -> "ICMP"
            else -> "OTHER"
        }
        val key = "$kindLabel|$protocol|$address|${port ?: -1}"
        val now = System.currentTimeMillis()

        synchronized(sampleLock) {
            val previous = unsupportedSampleCounts[key]
            unsupportedSampleCounts[key] = UnsupportedSample(
                kind = kindLabel,
                protocol = protocol,
                address = address,
                port = port,
                count = (previous?.count ?: 0L) + 1L,
                lastSeenAt = now
            )
            while (unsupportedSampleCounts.size > MAX_UNSUPPORTED_SAMPLE_KEYS) {
                val oldest = unsupportedSampleCounts.minByOrNull { it.value.lastSeenAt }?.key ?: break
                unsupportedSampleCounts.remove(oldest)
            }
        }
    }

    internal fun sampleKindLabel(kind: UnsupportedKind, address: String?, port: Int?): String {
        val normalizedAddress = address?.trim()?.lowercase(Locale.US).orEmpty()
        return when {
            kind == UnsupportedKind.IPV4_TCP_OTHER &&
                normalizedAddress == "10.77.0.2" && port == 853 ->
                "Private DNS/DoT attempt to Guardian"
            kind == UnsupportedKind.IPV6 && normalizedAddress.startsWith("ff02:") ->
                "IPv6 link-local control"
            kind == UnsupportedKind.IPV4_TCP_DNS -> "TCP DNS"
            kind == UnsupportedKind.IPV4_TCP_OTHER -> "TCP other"
            kind == UnsupportedKind.IPV4_UDP_OTHER -> "UDP other"
            kind == UnsupportedKind.IPV4_ICMP -> "ICMP"
            kind == UnsupportedKind.IPV4_FRAGMENT -> "IPv4 fragment"
            kind == UnsupportedKind.IPV4_OTHER -> "Other IPv4"
            kind == UnsupportedKind.IPV6 -> "IPv6"
            else -> "Malformed"
        }
    }

    internal fun classifyUnsupported(packet: ByteArray, length: Int): UnsupportedKind {
        if (length <= 0 || packet.isEmpty()) return UnsupportedKind.MALFORMED
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version == 6) return UnsupportedKind.IPV6
        if (version != 4 || length < 20) return UnsupportedKind.MALFORMED

        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || length < headerLength) return UnsupportedKind.MALFORMED

        // Any non-zero fragment offset or the More Fragments bit means the
        // transport header may not be safely parseable as a complete DNS packet.
        val fragmentBits = u16(packet, 6) and 0x3FFF
        if (fragmentBits != 0) return UnsupportedKind.IPV4_FRAGMENT

        return when (packet[9].toInt() and 0xFF) {
            6 -> {
                if (length < headerLength + 4) UnsupportedKind.MALFORMED
                else {
                    val destinationPort = u16(packet, headerLength + 2)
                    if (destinationPort == 53) UnsupportedKind.IPV4_TCP_DNS
                    else UnsupportedKind.IPV4_TCP_OTHER
                }
            }
            17 -> {
                if (length < headerLength + 8) UnsupportedKind.MALFORMED
                else {
                    val destinationPort = u16(packet, headerLength + 2)
                    if (destinationPort == 53) UnsupportedKind.MALFORMED
                    else UnsupportedKind.IPV4_UDP_OTHER
                }
            }
            1 -> UnsupportedKind.IPV4_ICMP
            else -> UnsupportedKind.IPV4_OTHER
        }
    }

    internal enum class UnsupportedKind {
        IPV4_TCP_DNS,
        IPV4_TCP_OTHER,
        IPV4_UDP_OTHER,
        IPV4_ICMP,
        IPV4_FRAGMENT,
        IPV4_OTHER,
        IPV6,
        MALFORMED
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
}
