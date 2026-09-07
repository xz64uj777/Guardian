package com.guardianlayer.app.firewall

/**
 * Minimal IPv4/UDP DNS packet codec used by Tracker Shield.
 *
 * Guardian deliberately keeps this narrow for the first forwarding milestone:
 * it only handles ordinary IPv4 UDP DNS packets sent to port 53. TCP DNS,
 * IPv6 DNS, encrypted DNS, and arbitrary app payload forwarding are not
 * interpreted here.
 */
object DnsPacketCodec {

    data class Request(
        val sourceAddress: ByteArray,
        val destinationAddress: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
        val dnsPayload: ByteArray,
        val domain: String
    )

    fun parseIpv4UdpRequest(packet: ByteArray, length: Int): Request? {
        if (length < 28 || packet.isEmpty()) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || length < headerLength + 8) return null

        val fragmentOffset = ((packet[6].toInt() and 0x1F) shl 8) or
            (packet[7].toInt() and 0xFF)
        if (fragmentOffset != 0) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null

        val sourcePort = u16(packet, headerLength)
        val destinationPort = u16(packet, headerLength + 2)
        if (destinationPort != 53) return null

        val udpLength = u16(packet, headerLength + 4)
        if (udpLength < 8) return null
        val availableUdpLength = length - headerLength
        if (availableUdpLength < udpLength) return null

        val dnsOffset = headerLength + 8
        val dnsLength = udpLength - 8
        if (dnsLength < 12 || dnsOffset + dnsLength > length) return null

        val dns = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        val domain = parseQuestionDomain(dns) ?: return null

        return Request(
            sourceAddress = packet.copyOfRange(12, 16),
            destinationAddress = packet.copyOfRange(16, 20),
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            dnsPayload = dns,
            domain = domain
        )
    }

    fun buildNxDomainPayload(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val response = query.copyOf()

        // QR=1. Preserve opcode/RD and other request-side flags we understand.
        response[2] = (response[2].toInt() or 0x80).toByte()
        // RA=1, RCODE=3 (NXDOMAIN), preserve upper response flag bits.
        response[3] = ((response[3].toInt() and 0x70) or 0x80 or 0x03).toByte()
        // No answers, authority records, or additional records.
        for (index in 6..11) response[index] = 0
        return response
    }

    fun buildIpv4UdpResponse(request: Request, dnsResponse: ByteArray): ByteArray {
        val udpLength = 8 + dnsResponse.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)

        packet[0] = 0x45
        packet[1] = 0
        putU16(packet, 2, totalLength)
        putU16(packet, 4, 0)
        putU16(packet, 6, 0)
        packet[8] = 64
        packet[9] = 17
        packet[10] = 0
        packet[11] = 0

        // The response comes from the virtual DNS address the app queried.
        request.destinationAddress.copyInto(packet, 12)
        request.sourceAddress.copyInto(packet, 16)

        val udpOffset = 20
        putU16(packet, udpOffset, request.destinationPort)
        putU16(packet, udpOffset + 2, request.sourcePort)
        putU16(packet, udpOffset + 4, udpLength)
        putU16(packet, udpOffset + 6, 0)
        dnsResponse.copyInto(packet, udpOffset + 8)

        putU16(packet, 10, checksum(packet, 0, 20))
        val udpChecksum = udpChecksum(
            sourceAddress = request.destinationAddress,
            destinationAddress = request.sourceAddress,
            udpPacket = packet,
            udpOffset = udpOffset,
            udpLength = udpLength
        )
        putU16(packet, udpOffset + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return packet
    }

    private fun parseQuestionDomain(dns: ByteArray): String? {
        if (dns.size < 12) return null
        val isResponse = (dns[2].toInt() and 0x80) != 0
        if (isResponse) return null
        val questions = u16(dns, 4)
        if (questions < 1) return null

        var cursor = 12
        val labels = mutableListOf<String>()
        var totalLength = 0

        while (cursor < dns.size) {
            val labelLength = dns[cursor].toInt() and 0xFF
            cursor++
            if (labelLength == 0) break
            if ((labelLength and 0xC0) != 0 || labelLength > 63) return null
            if (cursor + labelLength > dns.size) return null

            val label = buildString(labelLength) {
                repeat(labelLength) {
                    val value = dns[cursor + it].toInt() and 0xFF
                    if (value !in 0x21..0x7E || value == '.'.code) return null
                    append(value.toChar().lowercaseChar())
                }
            }
            labels += label
            totalLength += labelLength + 1
            if (totalLength > 253) return null
            cursor += labelLength
        }

        if (labels.isEmpty() || cursor + 4 > dns.size) return null
        return labels.joinToString(".")
    }

    private fun udpChecksum(
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
        udpPacket: ByteArray,
        udpOffset: Int,
        udpLength: Int
    ): Int {
        var sum = 0L
        sum += word(sourceAddress, 0)
        sum += word(sourceAddress, 2)
        sum += word(destinationAddress, 0)
        sum += word(destinationAddress, 2)
        sum += 17
        sum += udpLength

        var index = 0
        while (index + 1 < udpLength) {
            sum += ((udpPacket[udpOffset + index].toInt() and 0xFF) shl 8) or
                (udpPacket[udpOffset + index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < udpLength) {
            sum += (udpPacket[udpOffset + index].toInt() and 0xFF) shl 8
        }
        return foldChecksum(sum)
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = 0
        while (index + 1 < length) {
            sum += ((bytes[offset + index].toInt() and 0xFF) shl 8) or
                (bytes[offset + index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < length) sum += (bytes[offset + index].toInt() and 0xFF) shl 8
        return foldChecksum(sum)
    }

    private fun foldChecksum(initial: Long): Int {
        var sum = initial
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun word(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }
}
