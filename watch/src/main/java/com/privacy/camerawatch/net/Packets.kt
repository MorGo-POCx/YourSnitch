package com.privacy.camerawatch.net

/** Minimal IPv4 / UDP / DNS parsing + response building for the DNS-capture VPN. */
object Packets {

    const val PROTO_UDP = 17

    fun ipVersion(p: ByteArray) = (p[0].toInt() and 0xF0) ushr 4
    fun ihl(p: ByteArray) = (p[0].toInt() and 0x0F) * 4
    fun protocol(p: ByteArray) = p[9].toInt() and 0xFF
    fun srcIp(p: ByteArray) = readInt(p, 12)
    fun dstIp(p: ByteArray) = readInt(p, 16)

    fun udpSrcPort(p: ByteArray, ipHdr: Int) = readShort(p, ipHdr)
    fun udpDstPort(p: ByteArray, ipHdr: Int) = readShort(p, ipHdr + 2)
    fun udpPayloadOffset(ipHdr: Int) = ipHdr + 8

    fun ipToString(ip: Int) =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    // ---- DNS ----

    /** First question's domain name (lowercased), or null. */
    fun dnsQueryName(msg: ByteArray, off: Int, len: Int): String? {
        if (len < 12) return null
        return try {
            readName(msg, off + 12, off).first?.lowercase()
        } catch (e: Exception) { null }
    }

    /** All A-record IPv4 addresses (as ints) from a DNS response. */
    fun dnsAnswerIps(msg: ByteArray, off: Int, len: Int): List<Int> {
        val out = ArrayList<Int>()
        try {
            if (len < 12) return out
            val qd = readShort(msg, off + 4)
            val an = readShort(msg, off + 6)
            var pos = off + 12
            repeat(qd) {
                pos = skipName(msg, pos, off)
                pos += 4 // qtype + qclass
            }
            repeat(an) {
                pos = skipName(msg, pos, off)
                val type = readShort(msg, pos)
                val rdlen = readShort(msg, pos + 8)
                val rdata = pos + 10
                if (type == 1 && rdlen == 4) out.add(readInt(msg, rdata))
                pos = rdata + rdlen
            }
        } catch (e: Exception) {
            // best effort
        }
        return out
    }

    private fun readName(msg: ByteArray, start: Int, msgStart: Int): Pair<String?, Int> {
        val sb = StringBuilder()
        var pos = start
        var jumped = false
        var afterPtr = start
        var guard = 0
        while (guard++ < 128) {
            val b = msg[pos].toInt() and 0xFF
            if (b == 0) { pos++; break }
            if ((b and 0xC0) == 0xC0) {
                val ptr = ((b and 0x3F) shl 8) or (msg[pos + 1].toInt() and 0xFF)
                if (!jumped) afterPtr = pos + 2
                jumped = true
                pos = msgStart + ptr
                continue
            }
            pos++
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until b) sb.append((msg[pos + i].toInt() and 0xFF).toChar())
            pos += b
        }
        return Pair(if (sb.isEmpty()) null else sb.toString(), if (jumped) afterPtr else pos)
    }

    private fun skipName(msg: ByteArray, start: Int, msgStart: Int): Int {
        var pos = start
        var guard = 0
        while (guard++ < 128) {
            val b = msg[pos].toInt() and 0xFF
            if (b == 0) return pos + 1
            if ((b and 0xC0) == 0xC0) return pos + 2
            pos += 1 + b
        }
        return pos
    }

    // ---- build an IPv4/UDP response packet ----

    fun buildUdp(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val pkt = ByteArray(total)
        // IPv4 header
        pkt[0] = 0x45
        pkt[1] = 0
        writeShort(pkt, 2, total)
        writeShort(pkt, 4, 0)      // id
        writeShort(pkt, 6, 0x4000) // don't fragment
        pkt[8] = 64                // ttl
        pkt[9] = PROTO_UDP.toByte()
        writeShort(pkt, 10, 0)     // checksum placeholder
        writeInt(pkt, 12, srcIp)
        writeInt(pkt, 16, dstIp)
        writeShort(pkt, 10, checksum(pkt, 0, 20, 0))
        // UDP header
        val u = 20
        writeShort(pkt, u, srcPort)
        writeShort(pkt, u + 2, dstPort)
        writeShort(pkt, u + 4, 8 + payload.size)
        writeShort(pkt, u + 6, 0)  // checksum (optional in IPv4)
        System.arraycopy(payload, 0, pkt, u + 8, payload.size)
        // UDP checksum (with pseudo-header)
        var sum = 0
        sum += (srcIp ushr 16) and 0xFFFF; sum += srcIp and 0xFFFF
        sum += (dstIp ushr 16) and 0xFFFF; sum += dstIp and 0xFFFF
        sum += PROTO_UDP
        sum += 8 + payload.size
        val udpCk = checksum(pkt, u, 8 + payload.size, sum)
        writeShort(pkt, u + 6, if (udpCk == 0) 0xFFFF else udpCk)
        return pkt
    }

    private fun checksum(data: ByteArray, off: Int, len: Int, initial: Int): Int {
        var sum = initial
        var i = off
        var rem = len
        while (rem > 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2; rem -= 2
        }
        if (rem == 1) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun readInt(p: ByteArray, o: Int) =
        ((p[o].toInt() and 0xFF) shl 24) or ((p[o + 1].toInt() and 0xFF) shl 16) or
            ((p[o + 2].toInt() and 0xFF) shl 8) or (p[o + 3].toInt() and 0xFF)

    private fun readShort(p: ByteArray, o: Int) =
        ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)

    private fun writeInt(p: ByteArray, o: Int, v: Int) {
        p[o] = (v ushr 24).toByte(); p[o + 1] = (v ushr 16).toByte()
        p[o + 2] = (v ushr 8).toByte(); p[o + 3] = v.toByte()
    }

    private fun writeShort(p: ByteArray, o: Int, v: Int) {
        p[o] = (v ushr 8).toByte(); p[o + 1] = v.toByte()
    }
}
