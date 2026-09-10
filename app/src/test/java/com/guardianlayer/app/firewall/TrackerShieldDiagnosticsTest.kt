package com.guardianlayer.app.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerShieldDiagnosticsTest {

    @Test
    fun classifiesIpv4TcpDns() {
        val packet = ipv4Packet(protocol = 6, destinationPort = 53)
        assertEquals(
            TrackerShieldDiagnostics.UnsupportedKind.IPV4_TCP_DNS,
            TrackerShieldDiagnostics.classifyUnsupported(packet, packet.size)
        )
    }

    @Test
    fun classifiesIpv4TcpOther() {
        val packet = ipv4Packet(protocol = 6, destinationPort = 443)
        assertEquals(
            TrackerShieldDiagnostics.UnsupportedKind.IPV4_TCP_OTHER,
            TrackerShieldDiagnostics.classifyUnsupported(packet, packet.size)
        )
    }

    @Test
    fun classifiesIpv4UdpOther() {
        val packet = ipv4Packet(protocol = 17, destinationPort = 443)
        assertEquals(
            TrackerShieldDiagnostics.UnsupportedKind.IPV4_UDP_OTHER,
            TrackerShieldDiagnostics.classifyUnsupported(packet, packet.size)
        )
    }

    @Test
    fun classifiesIpv4Icmp() {
        val packet = ipv4Packet(protocol = 1, destinationPort = 0)
        assertEquals(
            TrackerShieldDiagnostics.UnsupportedKind.IPV4_ICMP,
            TrackerShieldDiagnostics.classifyUnsupported(packet, packet.size)
        )
    }

    @Test
    fun classifiesIpv4Fragment() {
        val packet = ipv4Packet(protocol = 17, destinationPort = 53)
        packet[6] = 0x20
        assertEquals(
            TrackerShieldDiagnostics.UnsupportedKind.IPV4_FRAGMENT,
            TrackerShieldDiagnostics.classifyUnsupported(packet, packet.size)
        )
    }

    @Test
    fun classifiesIpv4OtherProtocol() {
        val packet = ipv4Packet(protocol = 47, destinationPort = 0)
        assertEquals(
            TrackerShieldDiagnostics.UnsupportedKind.IPV4_OTHER,
            TrackerShieldDiagnostics.classifyUnsupported(packet, packet.size)
        )
    }

    @Test
    fun classifiesIpv6() {
        val packet = ByteArray(40)
        packet[0] = 0x60
        assertEquals(
            TrackerShieldDiagnostics.UnsupportedKind.IPV6,
            TrackerShieldDiagnostics.classifyUnsupported(packet, packet.size)
        )
    }

    @Test
    fun classifiesMalformed() {
        val packet = byteArrayOf(0x45)
        assertEquals(
            TrackerShieldDiagnostics.UnsupportedKind.MALFORMED,
            TrackerShieldDiagnostics.classifyUnsupported(packet, packet.size)
        )
    }

    @Test
    fun recordsBoundedEndpointMetadataWithoutPayload() {
        TrackerShieldDiagnostics.reset()
        val packet = ipv4Packet(protocol = 6, destinationPort = 443).apply {
            this[16] = 1
            this[17] = 2
            this[18] = 3
            this[19] = 4
        }

        TrackerShieldDiagnostics.recordUnsupported(packet, packet.size)
        TrackerShieldDiagnostics.recordUnsupported(packet, packet.size)

        val sample = TrackerShieldDiagnostics.snapshot().unsupportedSamples.single()
        assertEquals("TCP other", sample.kind)
        assertEquals("TCP", sample.protocol)
        assertEquals("1.2.3.4", sample.address)
        assertEquals(443, sample.port)
        assertEquals(2L, sample.count)
        assertTrue(sample.lastSeenAt > 0L)
    }

    @Test
    fun explainsVirtualDnsPort853AsPrivateDnsDotAttempt() {
        assertEquals(
            "Private DNS/DoT attempt to Guardian",
            TrackerShieldDiagnostics.sampleKindLabel(
                TrackerShieldDiagnostics.UnsupportedKind.IPV4_TCP_OTHER,
                "10.77.0.2",
                853
            )
        )
    }

    @Test
    fun explainsFf02Ipv6AsLinkLocalControl() {
        assertEquals(
            "IPv6 link-local control",
            TrackerShieldDiagnostics.sampleKindLabel(
                TrackerShieldDiagnostics.UnsupportedKind.IPV6,
                "ff02:0:0:0:0:0:0:2",
                null
            )
        )
    }

    private fun ipv4Packet(protocol: Int, destinationPort: Int): ByteArray {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[9] = protocol.toByte()
        packet[22] = ((destinationPort ushr 8) and 0xFF).toByte()
        packet[23] = (destinationPort and 0xFF).toByte()
        return packet
    }
}
