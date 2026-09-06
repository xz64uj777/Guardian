package com.guardianlayer.app.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsQueryInspectorTest {

    @Test
    fun parsesIpv4UdpDnsQuestion() {
        val packet = ByteArray(64)
        packet[0] = 0x45
        packet[9] = 17
        packet[16] = 8
        packet[17] = 8
        packet[18] = 8
        packet[19] = 8

        // UDP destination port 53.
        packet[22] = 0x00
        packet[23] = 0x35

        val dns = 28
        packet[dns + 4] = 0x00
        packet[dns + 5] = 0x01

        var cursor = dns + 12
        cursor = writeLabel(packet, cursor, "graph")
        cursor = writeLabel(packet, cursor, "facebook")
        cursor = writeLabel(packet, cursor, "com")
        packet[cursor++] = 0x00
        packet[cursor++] = 0x00
        packet[cursor++] = 0x01 // A
        packet[cursor++] = 0x00
        packet[cursor] = 0x01 // IN

        val query = DnsQueryInspector.inspect(packet, packet.size)

        assertEquals("graph.facebook.com", query?.domain)
        assertEquals("UDP", query?.transport)
    }

    @Test
    fun ignoresEncryptedOrNonDnsPort() {
        val packet = ByteArray(48)
        packet[0] = 0x45
        packet[9] = 6
        packet[22] = 0x01
        packet[23] = 0xBB.toByte() // 443 / HTTPS, which may include DoH but is encrypted.

        assertNull(DnsQueryInspector.inspect(packet, packet.size))
    }

    private fun writeLabel(packet: ByteArray, start: Int, value: String): Int {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        packet[start] = bytes.size.toByte()
        bytes.copyInto(packet, destinationOffset = start + 1)
        return start + 1 + bytes.size
    }
}
