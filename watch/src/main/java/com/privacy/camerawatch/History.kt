package com.privacy.camerawatch

import android.content.Context

/** Structured, persisted event log (newest first), capped. */
object History {
    private const val PREF = "camerawatch"
    private const val KEY = "events2"
    private const val MAX = 300
    private const val SEP = "\t"
    const val ACTION_UPDATE = "com.privacy.camerawatch.UPDATE"

    const val CAMERA = "cam"
    const val MIC = "mic"

    const val ON = "on"
    const val ON_WITH_CAMERA = "on+cam"
    const val OFF = "off"

    data class Event(
        val millis: Long,
        val type: String,     // CAMERA | MIC
        val state: String,    // ON | ON_WITH_CAMERA | OFF
        val label: String,    // app display name
        val pkg: String
    ) {
        val isOn get() = state.startsWith("on")
        val withCamera get() = state.contains("cam")
    }

    fun add(ctx: Context, type: String, state: String, label: String, pkg: String, millis: Long) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val line = listOf(millis.toString(), type, state, label, pkg).joinToString(SEP)
        val existing = sp.getString(KEY, "").orEmpty()
        val merged = (listOf(line) + existing.split("\n"))
            .filter { it.isNotBlank() }
            .take(MAX)
            .joinToString("\n")
        sp.edit().putString(KEY, merged).apply()
    }

    fun events(ctx: Context, type: String? = null): List<Event> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        return raw.split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val p = line.split(SEP)
                if (p.size >= 5) Event(p[0].toLongOrNull() ?: 0L, p[1], p[2], p[3], p[4]) else null
            }
            .let { list -> if (type == null) list else list.filter { it.type == type } }
    }

    fun clear(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
}
