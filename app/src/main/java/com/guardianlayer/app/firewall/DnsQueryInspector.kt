package com.guardianlayer.app.firewall

import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Parses ordinary plaintext DNS questions from packets Guardian is already
 * receiving in its blocking VPN. This does not decrypt DNS-over-TLS/HTTPS and
 * deliberately returns null when the packet layout is not safe to parse.
 */
object DnsQueryInspector {

    data class Query(
        val domain: String,
        val transport: String
    )

    fun inspect(packet: ByteArray, length: Int): Query? {
        if (length <= 0 || packet.isEmpty()) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> inspectIpv4(packet, length)
            6 -> inspectIpv6(packet, length)
            else -> null
        }
    }

    private fun inspectIpv4(packet: ByteArray, length: Int): Query? {
        if (length < 20) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || length < headerLength) return null

        val fragmentOffset = ((packet[6].toInt() and 0x1F) shl 8) or
            (packet[7].toInt() and 0xFF)
        if (fragmentOffset != 0) return null

        val protocol = packet[9].toInt() and 0xFF
        return inspectTransport(packet, length, headerLength, protocol)
    }

    private fun inspectIpv6(packet: ByteArray, length: Int): Query? {
        if (length < 40) return null
        val nextHeader = packet[6].toInt() and 0xFF

        // Extension-header walking comes later. Refuse to guess when the first
        // next-header value is not directly TCP or UDP.
        return inspectTransport(packet, length, 40, nextHeader)
    }

    private fun inspectTransport(
        packet: ByteArray,
        length: Int,
        transportOffset: Int,
        protocol: Int
    ): Query? {
        if (length < transportOffset + 4) return null
        val destinationPort = ((packet[transportOffset + 2].toInt() and 0xFF) shl 8) or
            (packet[transportOffset + 3].toInt() and 0xFF)
        if (destinationPort != 53) return null

        return when (protocol) {
            17 -> {
                val dnsOffset = transportOffset + 8
                parseDnsQuestion(packet, length, dnsOffset)?.let { Query(it, "UDP") }
            }
            6 -> {
                if (length < transportOffset + 20) return null
                val tcpHeaderLength = ((packet[transportOffset + 12].toInt() ushr 4) and 0x0F) * 4
                if (tcpHeaderLength < 20) return null
                val lengthPrefixOffset = transportOffset + tcpHeaderLength
                if (length < lengthPrefixOffset + 2) return null
                val dnsLength = ((packet[lengthPrefixOffset].toInt() and 0xFF) shl 8) or
                    (packet[lengthPrefixOffset + 1].toInt() and 0xFF)
                val dnsOffset = lengthPrefixOffset + 2
                if (dnsLength < 12 || length < dnsOffset + dnsLength) return null
                parseDnsQuestion(packet, dnsOffset + dnsLength, dnsOffset)
                    ?.let { Query(it, "TCP") }
            }
            else -> null
        }
    }

    private fun parseDnsQuestion(packet: ByteArray, length: Int, dnsOffset: Int): String? {
        if (dnsOffset < 0 || length < dnsOffset + 12) return null

        val flagsHigh = packet[dnsOffset + 2].toInt() and 0xFF
        val isResponse = (flagsHigh and 0x80) != 0
        if (isResponse) return null

        val questionCount = ((packet[dnsOffset + 4].toInt() and 0xFF) shl 8) or
            (packet[dnsOffset + 5].toInt() and 0xFF)
        if (questionCount < 1) return null

        var cursor = dnsOffset + 12
        val labels = mutableListOf<String>()
        var totalNameLength = 0

        while (cursor < length) {
            val labelLength = packet[cursor].toInt() and 0xFF
            cursor++

            if (labelLength == 0) break
            if ((labelLength and 0xC0) != 0 || labelLength > 63) return null
            if (cursor + labelLength > length) return null

            val labelBytes = packet.copyOfRange(cursor, cursor + labelLength)
            if (labelBytes.any { byte ->
                    val value = byte.toInt() and 0xFF
                    value < 0x21 || value > 0x7E || value == '.'.code
                }) return null

            val label = String(labelBytes, StandardCharsets.US_ASCII)
                .lowercase(Locale.US)
            if (label.isBlank()) return null

            labels += label
            totalNameLength += labelLength + 1
            if (totalNameLength > 253) return null
            cursor += labelLength
        }

        if (labels.isEmpty()) return null
        if (cursor + 4 > length) return null // QTYPE + QCLASS
        return labels.joinToString(".")
    }
}
