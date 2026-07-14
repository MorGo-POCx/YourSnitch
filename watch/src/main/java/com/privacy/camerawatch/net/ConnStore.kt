package com.privacy.camerawatch.net

import android.content.Context

/** Persisted, capped log of captured connections (newest first). */
object ConnStore {
    private const val PREF = "camerawatch_conn"
    private const val KEY = "conns"
    private const val MAX = 500
    private const val SEP = "\t"
    const val ACTION_UPDATE = "com.privacy.camerawatch.CONN_UPDATE"

    data class Conn(
        val millis: Long,
        val domain: String,
        val ip: String,
        val cc: String
    )

    fun add(ctx: Context, domain: String, ip: String, cc: String, millis: Long) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val line = listOf(millis.toString(), domain, ip, cc).joinToString(SEP)
        val existing = sp.getString(KEY, "").orEmpty()
        val merged = (listOf(line) + existing.split("\n"))
            .filter { it.isNotBlank() }
            .take(MAX)
            .joinToString("\n")
        sp.edit().putString(KEY, merged).apply()
    }

    fun events(ctx: Context): List<Conn> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        return raw.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size >= 4) Conn(p[0].toLongOrNull() ?: 0L, p[1], p[2], p[3]) else null
        }
    }

    fun clear(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
}
