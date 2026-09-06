package com.guardianlayer.app.firewall

import java.net.InetAddress

/**
 * Lightweight metadata parser for packets Guardian is already dropping.
 * It never decrypts payloads and never attempts to infer domains or app UIDs.
 */
object PacketInspector {

    data class Destination(
        val protocol: String,
        val address: String,
        val port: Int?
    )

    fun inspect(packet: ByteArray, length: Int): Destination? {
        if (length <= 0 || packet.isEmpty()) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> inspectIpv4(packet, length)
            6 -> inspectIpv6(packet, length)
            else -> null
        }
    }

    private fun inspectIpv4(packet: ByteArray, length: Int): Destination? {
        if (length < 20) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || length < headerLength) return null

        val protocolNumber = packet[9].toInt() and 0xFF
        val address = runCatching {
            InetAddress.getByAddress(packet.copyOfRange(16, 20)).hostAddress
        }.getOrNull() ?: return null

        return Destination(
            protocol = protocolName(protocolNumber),
            address = address,
            port = destinationPort(packet, length, headerLength, protocolNumber)
        )
    }

    private fun inspectIpv6(packet: ByteArray, length: Int): Destination? {
        if (length < 40) return null
        val nextHeader = packet[6].toInt() and 0xFF
        val address = runCatching {
            InetAddress.getByAddress(packet.copyOfRange(24, 40)).hostAddress
        }.getOrNull() ?: return null

        // TCP/UDP without IPv6 extension headers can be read directly at 40.
        // Extension headers are deliberately not guessed at in this milestone.
        return Destination(
            protocol = protocolName(nextHeader),
            address = address,
            port = destinationPort(packet, length, 40, nextHeader)
        )
    }

    private fun destinationPort(
        packet: ByteArray,
        length: Int,
        transportOffset: Int,
        protocolNumber: Int
    ): Int? {
        if (protocolNumber != 6 && protocolNumber != 17) return null
        if (transportOffset < 0 || length < transportOffset + 4) return null
        return ((packet[transportOffset + 2].toInt() and 0xFF) shl 8) or
            (packet[transportOffset + 3].toInt() and 0xFF)
    }

    private fun protocolName(protocolNumber: Int): String = when (protocolNumber) {
        6 -> "TCP"
        17 -> "UDP"
        else -> "OTHER"
    }
}
