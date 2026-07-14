package com.privacy.camerawatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * ONE ongoing notification shared by both foreground services — the camera/mic monitor
 * (WatchService) and the connection/DNS monitor (SniffVpnService). Both post to the same [ID]
 * so the user sees a single notification when both run; whichever service is still alive keeps
 * it. Separate one-shot alerts (sensor / country) use their own IDs and channels.
 */
object MonitorNotif {
    const val ID = 1
    const val CHANNEL = "ys_monitor"

    // Live camera/mic user, set by WatchService.
    @Volatile var cameraApp: String? = null
    @Volatile var micApp: String? = null

    fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Monitoring", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun build(ctx: Context): Notification {
        val cam = cameraApp
        val mic = micApp
        val watching = WatchService.isEnabled(ctx)
        val conn = Prefs.connEnabled(ctx)
        val inUse = cam != null || mic != null
        val title: String
        val text: String
        when {
            cam != null && mic != null -> { title = "In use now"; text = "📷 $cam   ·   🎙 $mic" }
            mic != null -> { title = "🎙 Microphone in use"; text = "$mic is using the microphone" }
            cam != null -> { title = "📷 Camera in use"; text = "$cam is using the camera" }
            else -> {
                title = "YourSnitch — keep me running"
                text = when {
                    watching && conn -> "Monitoring your camera, mic & connections"
                    conn -> "Monitoring your connections"
                    else -> "Monitoring your camera & mic"
                }
            }
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setColor(if (inUse) 0xFF5B7CFA.toInt() else 0xFF8B94A8.toInt())
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun update(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx))

    /** After a service tears down: keep the shared notification if the OTHER monitor is still
     *  running, otherwise remove it. */
    fun refreshOrCancel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (WatchService.isEnabled(ctx) || Prefs.connEnabled(ctx)) nm.notify(ID, build(ctx))
        else nm.cancel(ID)
    }
}
