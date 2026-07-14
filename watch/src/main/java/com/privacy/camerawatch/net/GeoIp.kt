package com.privacy.camerawatch.net

import android.content.Context
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Offline IPv4 -> country lookup over a compact binary asset (geoip.bin):
 * sorted records of [4-byte big-endian start IP][2-byte ISO country code].
 * Floor binary search returns the country whose range starts at/below the IP.
 */
object GeoIp {
    private var buf: ByteBuffer? = null
    private var count = 0

    @Synchronized
    fun init(ctx: Context) {
        if (buf != null) return
        val bytes = ctx.applicationContext.assets.open("geoip.bin").use { it.readBytes() }
        buf = ByteBuffer.wrap(bytes) // big-endian by default
        count = bytes.size / 6
    }

    /** Returns ISO2 code, "LOCAL" for private/loopback, or null if unknown/not loaded. */
    fun country(ipInt: Int): String? {
        if (isPrivate(ipInt)) return "LOCAL"
        val b = buf ?: return null
        val ip = ipInt.toLong() and 0xFFFFFFFFL
        var lo = 0
        var hi = count - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = b.getInt(mid * 6).toLong() and 0xFFFFFFFFL
            if (start <= ip) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (ans < 0) return null
        val c0 = b.get(ans * 6 + 4).toInt().toChar()
        val c1 = b.get(ans * 6 + 5).toInt().toChar()
        return "$c0$c1"
    }

    private fun isPrivate(ip: Int): Boolean {
        val a = (ip ushr 24) and 0xFF
        val b = (ip ushr 16) and 0xFF
        return a == 10 ||
            a == 127 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) ||
            (a == 169 && b == 254) ||
            a == 0
    }

    /** ISO2 -> display name ("US" -> "United States", "LOCAL" -> "Local"). */
    fun name(code: String): String = when (code) {
        "LOCAL" -> "Local"
        "ZZ", "" -> "Unknown"
        else -> Locale("", code).displayCountry.ifBlank { code }
    }

    /** ISO2 -> flag emoji ("US" -> 🇺🇸). "LOCAL"/unknown get a fallback glyph. */
    fun flag(code: String): String {
        if (code.length != 2 || code == "ZZ" || code == "LOCAL") return if (code == "LOCAL") "🏠" else "🌐"
        val a = Character.codePointAt(code, 0) - 'A'.code + 0x1F1E6
        val b = Character.codePointAt(code, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(a)) + String(Character.toChars(b))
    }
}
