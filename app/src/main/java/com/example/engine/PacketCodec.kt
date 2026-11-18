package com.example.engine

/**
 * Zero-allocation-oriented IPv4 + TCP/UDP packet parser, builder, and RFC 1071 checksum engine.
 * Operates directly on pooled ByteArrays without intermediate ByteBuffer wrappers on the hot path.
 */
object PacketCodec {
    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    data class ParsedPacket(
        val version: Int,
        val ipHeaderLength: Int,
        val totalLength: Int,
        val protocol: Int,
        val srcIpInt: Int,
        val dstIpInt: Int,
        val srcPort: Int,
        val dstPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
        val tcpSeq: Long = 0L,
        val tcpAck: Long = 0L,
        val tcpFlags: Int = 0,
        val tcpWindow: Int = 0
    ) {
        val srcIpString: String get() = intToIpString(srcIpInt)
        val dstIpString: String get() = intToIpString(dstIpInt)
    }

    fun parseIpv4(packet: ByteArray, length: Int): ParsedPacket? {
        if (length < 20 || packet.size < length) return null
        val versionAndIhl = packet[0].toInt() and 0xFF
        val version = versionAndIhl ushr 4
        if (version != 4) return null

        val ipHeaderLength = (versionAndIhl and 0x0F) * 4
        if (ipHeaderLength < 20 || length < ipHeaderLength) return null

        val totalLength = readUInt16(packet, 2)
        val effectiveLen = minOf(totalLength, length)
        if (effectiveLen < ipHeaderLength) return null

        val protocol = packet[9].toInt() and 0xFF
        val srcIpInt = readInt32(packet, 12)
        val dstIpInt = readInt32(packet, 16)

        return when (protocol) {
            PROTO_TCP -> {
                if (effectiveLen < ipHeaderLength + 20) return null
                val srcPort = readUInt16(packet, ipHeaderLength)
                val dstPort = readUInt16(packet, ipHeaderLength + 2)
                val seq = readUInt32(packet, ipHeaderLength + 4)
                val ack = readUInt32(packet, ipHeaderLength + 8)
                val dataOffset = ((packet[ipHeaderLength + 12].toInt() and 0xF0) ushr 4) * 4
                if (dataOffset < 20 || effectiveLen < ipHeaderLength + dataOffset) return null
                val flags = packet[ipHeaderLength + 13].toInt() and 0x3F
                val window = readUInt16(packet, ipHeaderLength + 14)
                val payloadOffset = ipHeaderLength + dataOffset
                val payloadLength = (effectiveLen - payloadOffset).coerceAtLeast(0)
                ParsedPacket(
                    version = 4,
                    ipHeaderLength = ipHeaderLength,
                    totalLength = effectiveLen,
                    protocol = PROTO_TCP,
                    srcIpInt = srcIpInt,
                    dstIpInt = dstIpInt,
                    srcPort = srcPort,
                    dstPort = dstPort,
                    payloadOffset = payloadOffset,
                    payloadLength = payloadLength,
                    tcpSeq = seq,
                    tcpAck = ack,
                    tcpFlags = flags,
                    tcpWindow = window
                )
            }

            PROTO_UDP -> {
                if (effectiveLen < ipHeaderLength + 8) return null
                val srcPort = readUInt16(packet, ipHeaderLength)
                val dstPort = readUInt16(packet, ipHeaderLength + 2)
                val udpLength = readUInt16(packet, ipHeaderLength + 4)
                val payloadOffset = ipHeaderLength + 8
                val maxPayload = (effectiveLen - payloadOffset).coerceAtLeast(0)
                val payloadLength = (udpLength - 8).coerceIn(0, maxPayload)
                ParsedPacket(
                    version = 4,
                    ipHeaderLength = ipHeaderLength,
                    totalLength = effectiveLen,
                    protocol = PROTO_UDP,
                    srcIpInt = srcIpInt,
                    dstIpInt = dstIpInt,
                    srcPort = srcPort,
                    dstPort = dstPort,
                    payloadOffset = payloadOffset,
                    payloadLength = payloadLength
                )
            }

            else -> {
                ParsedPacket(
                    version = 4,
                    ipHeaderLength = ipHeaderLength,
                    totalLength = effectiveLen,
                    protocol = protocol,
                    srcIpInt = srcIpInt,
                    dstIpInt = dstIpInt,
                    srcPort = 0,
                    dstPort = 0,
                    payloadOffset = ipHeaderLength,
                    payloadLength = (effectiveLen - ipHeaderLength).coerceAtLeast(0)
                )
            }
        }
    }

    /**
     * Builds an IPv4 + TCP packet directly into [out] buffer and returns total packet byte length.
     */
    fun buildIpv4TcpPacket(
        out: ByteArray,
        srcIpInt: Int,
        dstIpInt: Int,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        windowSize: Int = 65535,
        payload: ByteArray? = null,
        payloadOffset: Int = 0,
        payloadLength: Int = 0,
        ipIdentification: Int = 0
    ): Int {
        val ipHeaderLen = 20
        val tcpHeaderLen = 20
        val totalLength = ipHeaderLen + tcpHeaderLen + payloadLength
        require(out.size >= totalLength) { "Output buffer too small: ${out.size} < $totalLength" }

        // IPv4 Header
        out[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
        out[1] = 0x00 // DSCP/ECN
        writeUInt16(out, 2, totalLength)
        writeUInt16(out, 4, ipIdentification and 0xFFFF)
        out[6] = 0x40.toByte() // Don't Fragment
        out[7] = 0x00
        out[8] = 64.toByte() // TTL
        out[9] = PROTO_TCP.toByte()
        out[10] = 0x00 // Header checksum placeholder
        out[11] = 0x00
        writeInt32(out, 12, srcIpInt)
        writeInt32(out, 16, dstIpInt)

        val ipChecksum = computeChecksum(out, 0, ipHeaderLen)
        writeUInt16(out, 10, ipChecksum)

        // TCP Header
        val t = ipHeaderLen
        writeUInt16(out, t, srcPort)
        writeUInt16(out, t + 2, dstPort)
        writeInt32(out, t + 4, (seqNum and 0xFFFFFFFFL).toInt())
        writeInt32(out, t + 8, (ackNum and 0xFFFFFFFFL).toInt())
        out[t + 12] = 0x50.toByte() // Data offset = 5 (20 bytes)
        out[t + 13] = (flags and 0x3F).toByte()
        writeUInt16(out, t + 14, windowSize.coerceIn(0, 65535))
        out[t + 16] = 0x00 // TCP checksum placeholder
        out[t + 17] = 0x00
        out[t + 18] = 0x00 // Urgent pointer
        out[t + 19] = 0x00

        if (payload != null && payloadLength > 0) {
            System.arraycopy(payload, payloadOffset, out, t + tcpHeaderLen, payloadLength)
        }

        val tcpLength = tcpHeaderLen + payloadLength
        val tcpChecksum = computeTransportChecksum(
            srcIpInt = srcIpInt,
            dstIpInt = dstIpInt,
            protocol = PROTO_TCP,
            packet = out,
            transportOffset = t,
            transportLength = tcpLength
        )
        writeUInt16(out, t + 16, tcpChecksum)

        return totalLength
    }

    /**
     * Builds an IPv4 + UDP packet directly into [out] buffer and returns total packet byte length.
     */
    fun buildIpv4UdpPacket(
        out: ByteArray,
        srcIpInt: Int,
        dstIpInt: Int,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
        ipIdentification: Int = 0
    ): Int {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val udpLength = udpHeaderLen + payloadLength
        val totalLength = ipHeaderLen + udpLength
        require(out.size >= totalLength) { "Output buffer too small: ${out.size} < $totalLength" }

        out[0] = 0x45.toByte()
        out[1] = 0x00
        writeUInt16(out, 2, totalLength)
        writeUInt16(out, 4, ipIdentification and 0xFFFF)
        out[6] = 0x40.toByte()
        out[7] = 0x00
        out[8] = 64.toByte()
        out[9] = PROTO_UDP.toByte()
        out[10] = 0x00
        out[11] = 0x00
        writeInt32(out, 12, srcIpInt)
        writeInt32(out, 16, dstIpInt)

        val ipChecksum = computeChecksum(out, 0, ipHeaderLen)
        writeUInt16(out, 10, ipChecksum)

        val u = ipHeaderLen
        writeUInt16(out, u, srcPort)
        writeUInt16(out, u + 2, dstPort)
        writeUInt16(out, u + 4, udpLength)
        out[u + 6] = 0x00
        out[u + 7] = 0x00

        if (payloadLength > 0) {
            System.arraycopy(payload, payloadOffset, out, u + udpHeaderLen, payloadLength)
        }

        val udpChecksum = computeTransportChecksum(
            srcIpInt = srcIpInt,
            dstIpInt = dstIpInt,
            protocol = PROTO_UDP,
            packet = out,
            transportOffset = u,
            transportLength = udpLength
        )
        writeUInt16(out, u + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)

        return totalLength
    }

    /**
     * Standard RFC 1071 one's complement 16-bit Internet checksum.
     */
    fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    fun computeTransportChecksum(
        srcIpInt: Int,
        dstIpInt: Int,
        protocol: Int,
        packet: ByteArray,
        transportOffset: Int,
        transportLength: Int
    ): Int {
        var sum = 0L
        // IPv4 pseudo-header
        sum += (srcIpInt ushr 16) and 0xFFFF
        sum += srcIpInt and 0xFFFF
        sum += (dstIpInt ushr 16) and 0xFFFF
        sum += dstIpInt and 0xFFFF
        sum += protocol and 0xFF
        sum += transportLength and 0xFFFF

        var i = transportOffset
        val end = transportOffset + transportLength
        while (i + 1 < end) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    fun verifyIpv4HeaderChecksum(packet: ByteArray, ipHeaderLength: Int): Boolean {
        if (ipHeaderLength < 20 || packet.size < ipHeaderLength) return false
        return computeChecksum(packet, 0, ipHeaderLength) == 0
    }

    fun ipStringToInt(ip: String): Int {
        val parts = ip.split('.')
        require(parts.size == 4) { "Invalid IPv4 address: $ip" }
        return ((parts[0].toInt() and 0xFF) shl 24) or
            ((parts[1].toInt() and 0xFF) shl 16) or
            ((parts[2].toInt() and 0xFF) shl 8) or
            (parts[3].toInt() and 0xFF)
    }

    fun intToIpString(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    private fun readUInt16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun readInt32(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)

    private fun readUInt32(buf: ByteArray, offset: Int): Long =
        readInt32(buf, offset).toLong() and 0xFFFFFFFFL

    private fun writeUInt16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeInt32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }
}
