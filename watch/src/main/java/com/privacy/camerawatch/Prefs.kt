package com.privacy.camerawatch

import android.content.Context

/** User settings (stored alongside the enabled flag + history). */
object Prefs {
    private const val P = "camerawatch"
    private fun sp(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun autostart(c: Context) = sp(c).getBoolean("autostart", false)
    fun setAutostart(c: Context, v: Boolean) = sp(c).edit().putBoolean("autostart", v).apply()

    fun alertCamera(c: Context) = sp(c).getBoolean("alert_camera", true)
    fun setAlertCamera(c: Context, v: Boolean) = sp(c).edit().putBoolean("alert_camera", v).apply()

    fun alertMic(c: Context) = sp(c).getBoolean("alert_mic", true)
    fun setAlertMic(c: Context, v: Boolean) = sp(c).edit().putBoolean("alert_mic", v).apply()

    fun connEnabled(c: Context) = sp(c).getBoolean("conn_enabled", false)
    fun setConnEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("conn_enabled", v).apply()

    // Whether we've already nudged the user about background/battery once (MIUI's grant doesn't
    // always flip isIgnoringBatteryOptimizations, so don't re-ask every launch).
    fun batteryAsked(c: Context) = sp(c).getBoolean("battery_asked", false)
    fun setBatteryAsked(c: Context, v: Boolean) = sp(c).edit().putBoolean("battery_asked", v).apply()

    // Has the user acknowledged the first-run "what this app does / stays on-device" disclosure?
    // (Play prominent-disclosure best practice: show it before requesting any permission.)
    fun disclosureAccepted(c: Context) = sp(c).getBoolean("disclosure_ok", false)
    fun setDisclosureAccepted(c: Context, v: Boolean) = sp(c).edit().putBoolean("disclosure_ok", v).apply()

    fun watchedCountries(c: Context): Set<String> =
        sp(c).getStringSet("watched_cc", emptySet())?.toSet() ?: emptySet()

    fun setWatchedCountries(c: Context, set: Set<String>) =
        sp(c).edit().putStringSet("watched_cc", set).apply()
}
