package com.guardianlayer.app.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketInspectorTest {

    @Test
    fun parsesIpv4TcpDestination() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[9] = 6
        packet[16] = 8
        packet[17] = 8
        packet[18] = 8
        packet[19] = 8
        packet[22] = 0x01
        packet[23] = 0xBB.toByte()

        val destination = PacketInspector.inspect(packet, packet.size)

        assertNotNull(destination)
        assertEquals("TCP", destination!!.protocol)
        assertEquals("8.8.8.8", destination.address)
        assertEquals(443, destination.port)
    }

    @Test
    fun parsesIpv6UdpDestinationPort() {
        val packet = ByteArray(48)
        packet[0] = 0x60
        packet[6] = 17
        val address = byteArrayOf(
            0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x88.toByte(), 0x88.toByte()
        )
        address.copyInto(packet, destinationOffset = 24)
        packet[42] = 0x00
        packet[43] = 0x35

        val destination = PacketInspector.inspect(packet, packet.size)

        assertNotNull(destination)
        assertEquals("UDP", destination!!.protocol)
        assertEquals(53, destination.port)
        assertTrue(destination.address.contains("2001:4860:4860"))
    }
}
