package com.privacy.camerawatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Starts monitoring after reboot if the user enabled "Start automatically on boot".
 * Note: on MIUI/Xiaomi you may also need to allow "Autostart" for CameraWatch in
 * Security settings for BOOT_COMPLETED to be delivered.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.autostart(context)) return
        // Android 14+ blocks starting a "specialUse" foreground service straight from boot unless
        // the app is battery-exempt; swallow the refusal so we never crash — the app re-arms
        // monitoring the next time it's opened.
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        }
    }
}
