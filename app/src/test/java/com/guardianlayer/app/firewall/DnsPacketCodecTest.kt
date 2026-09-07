package com.guardianlayer.app.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketCodecTest {

    @Test
    fun parsesIpv4UdpDnsQuestion() {
        val packet = dnsQueryPacket("doubleclick.net")
        val request = DnsPacketCodec.parseIpv4UdpRequest(packet, packet.size)

        assertNotNull(request)
        assertEquals("doubleclick.net", request!!.domain)
        assertEquals(53000, request.sourcePort)
        assertEquals(53, request.destinationPort)
        assertEquals(listOf(10, 77, 0, 1), request.sourceAddress.map { it.toInt() and 0xFF })
        assertEquals(listOf(10, 77, 0, 2), request.destinationAddress.map { it.toInt() and 0xFF })
    }

    @Test
    fun buildsNxdomainResponseAndSwapsEndpoints() {
        val packet = dnsQueryPacket("adsrvr.org")
        val request = DnsPacketCodec.parseIpv4UdpRequest(packet, packet.size)!!
        val nxdomain = DnsPacketCodec.buildNxDomainPayload(request.dnsPayload)!!
        val response = DnsPacketCodec.buildIpv4UdpResponse(request, nxdomain)

        assertTrue((nxdomain[2].toInt() and 0x80) != 0)
        assertEquals(3, nxdomain[3].toInt() and 0x0F)
        assertEquals(53, u16(response, 20))
        assertEquals(53000, u16(response, 22))
        assertEquals(listOf(10, 77, 0, 2), response.copyOfRange(12, 16).map { it.toInt() and 0xFF })
        assertEquals(listOf(10, 77, 0, 1), response.copyOfRange(16, 20).map { it.toInt() and 0xFF })
        assertTrue(u16(response, 10) != 0)
        assertTrue(u16(response, 26) != 0)
    }

    @Test
    fun ignoresNonDnsUdpTraffic() {
        val packet = dnsQueryPacket("example.com").copyOf()
        packet[22] = 0x01
        packet[23] = 0xBB.toByte() // destination port 443
        assertEquals(null, DnsPacketCodec.parseIpv4UdpRequest(packet, packet.size))
    }

    private fun dnsQueryPacket(domain: String): ByteArray {
        val dns = buildDnsQuery(domain)
        val udpLength = 8 + dns.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        packet[8] = 64
        packet[9] = 17
        putU16(packet, 2, totalLength)
        byteArrayOf(10, 77, 0, 1).copyInto(packet, 12)
        byteArrayOf(10, 77, 0, 2).copyInto(packet, 16)
        putU16(packet, 20, 53000)
        putU16(packet, 22, 53)
        putU16(packet, 24, udpLength)
        dns.copyInto(packet, 28)
        return packet
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val bytes = ArrayList<Byte>()
        bytes += 0x12.toByte()
        bytes += 0x34.toByte()
        bytes += 0x01.toByte()
        bytes += 0x00.toByte()
        bytes += 0x00.toByte()
        bytes += 0x01.toByte()
        repeat(6) { bytes += 0x00.toByte() }
        domain.split('.').forEach { label ->
            bytes += label.length.toByte()
            label.forEach { bytes += it.code.toByte() }
        }
        bytes += 0x00.toByte()
        bytes += 0x00.toByte()
        bytes += 0x01.toByte() // A
        bytes += 0x00.toByte()
        bytes += 0x01.toByte() // IN
        return bytes.toByteArray()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }
}
