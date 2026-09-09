package com.guardianlayer.app.firewall

import org.junit.Assert.assertEquals
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
    fun classifiesIpv4UdpOther() {
        val packet = ipv4Packet(protocol = 17, destinationPort = 443)
        assertEquals(
            TrackerShieldDiagnostics.UnsupportedKind.IPV4_UDP_OTHER,
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

    private fun ipv4Packet(protocol: Int, destinationPort: Int): ByteArray {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[9] = protocol.toByte()
        packet[22] = ((destinationPort ushr 8) and 0xFF).toByte()
        packet[23] = (destinationPort and 0xFF).toByte()
        return packet
    }
}
