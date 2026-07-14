package com.privacy.camerawatch

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

/**
 * Foreground service that watches camera + microphone use. It never opens either.
 *
 * - Camera on/off: CameraManager.AvailabilityCallback (no permission).
 * - Mic on/off: AudioManager.AudioRecordingCallback (no permission).
 * - Which app: foreground app via UsageStatsManager (Usage access, user/adb-grantable);
 *   for the camera, the exact AppOps user is preferred when that's available.
 */
class WatchService : Service() {

    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager
    private lateinit var usage: UsageStatsManager
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }

    private var micActive = false
    private var cameraOnLogged = false // did we record the current camera session as a real ON?
    private var lastCameraUserPkg: String? = null
    private var lastCameraUserAt: Long = 0L
    private val camerasOn = HashSet<String>()
    private val pendingCam = HashSet<String>() // seen unavailable, still inside the debounce window

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            pendingCam.add(cameraId)
            handler.postDelayed({
                // Commit only if the camera is STILL in use (not released during the debounce).
                // Otherwise a quick on/off flicker could leave a stale "on" that hides the next
                // real camera use and makes mic events read "with camera" forever.
                if (pendingCam.remove(cameraId) && camerasOn.add(cameraId)) resolveCameraUser(0)
            }, 700)
        }
        override fun onCameraAvailable(cameraId: String) {
            pendingCam.remove(cameraId)
            // Only emit an OFF if we actually recorded an ON (we skip system/face-unlock opens).
            if (camerasOn.remove(cameraId) && camerasOn.isEmpty() && cameraOnLogged) {
                reportCamera(inUse = false, pkg = null)
            }
        }
    }

    /**
     * A foreground app's usage-stats entry lags a second or two behind it actually coming to the
     * front, so an immediate lookup often returns the launcher. Retry a few times until we get a
     * real app (or give up after ~3.5s and report "Unknown").
     */
    private fun resolveCameraUser(attempt: Int) {
        if (camerasOn.isEmpty()) return // camera turned back off before we resolved
        // Attribute only to an app that BOTH is a real app AND actually holds the CAMERA permission.
        // An app without the permission cannot be the camera user, so it's never blamed — fixes
        // Clock/Moovit/TrueCaller appearing when face-unlock or a system client opened the camera.
        val pkg = whoPkg()?.takeIf { isRealApp(it) && holdsPermission(it, Manifest.permission.CAMERA) }
        when {
            pkg != null -> reportCamera(inUse = true, pkg = pkg)
            attempt < 4 -> handler.postDelayed({ resolveCameraUser(attempt + 1) }, 800)
            else -> Log.i(TAG, "camera user not a permitted app (system/face-unlock) — skipped")
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val active = configs.isNotEmpty()
            if (active != micActive) {
                micActive = active
                reportMic(active)
            }
        }
    }

    // Typed as Any? so WatchService never references the API-30 AppOps classes at class
    // level; the real type (AppOpsCameraWatcher) is only touched behind an SDK_INT >= R guard.
    private var opWatcher: Any? = null

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        createChannels()
    }

    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setEnabled(this, true)
        try {
            startForeground(MonitorNotif.ID, MonitorNotif.build(this))
        } catch (e: Exception) {
            // Android 14+ can refuse a background FGS start (e.g. from boot / task-swipe restart).
            // Bail out cleanly; enabled flag stays true so onResume re-arms it in the foreground.
            Log.w(TAG, "startForeground refused: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            cameraManager.registerAvailabilityCallback(availabilityCallback, handler)
            audioManager.registerAudioRecordingCallback(recordingCallback, handler)
            startAppOpsWatching()
        }
        broadcastUpdate()
        return START_STICKY
    }

    private fun startAppOpsWatching() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // API-30 AppOps code is isolated in AppOpsCameraWatcher so it loads only here, on 11+.
        opWatcher = AppOpsCameraWatcher(this) { pkg ->
            lastCameraUserPkg = pkg
            lastCameraUserAt = System.currentTimeMillis()
        }.also { it.start(executor) }
    }

    private fun reportCamera(inUse: Boolean, pkg: String?) {
        cameraOnLogged = inUse
        val label = pkg?.let { appLabel(it) } ?: if (inUse) "Unknown app" else lastLabel(History.CAMERA)
        if (inUse) Log.i(TAG, "CAMERA ON -> pkg=$pkg label=$label")
        History.add(this, History.CAMERA, if (inUse) History.ON else History.OFF, label, pkg ?: "", now())
        MonitorNotif.cameraApp = if (inUse && Prefs.alertCamera(this)) label else null
        updateOngoing()
        broadcastUpdate()
        if (!inUse) lastCameraUserPkg = null
    }

    private fun reportMic(active: Boolean) {
        if (active) {
            // Attribute only to an app that holds RECORD_AUDIO. If the recorder isn't such an app
            // (system hotword / voice service), reset and skip so we don't blame the on-screen app.
            val pkg = whoPkg()?.takeIf { isRealApp(it) && holdsPermission(it, Manifest.permission.RECORD_AUDIO) }
            if (pkg == null) { micActive = false; return }
            val label = appLabel(pkg)
            val state = if (camerasOn.isNotEmpty()) History.ON_WITH_CAMERA else History.ON
            History.add(this, History.MIC, state, label, pkg, now())
            MonitorNotif.micApp = if (Prefs.alertMic(this)) label else null
        } else {
            History.add(this, History.MIC, History.OFF, lastLabel(History.MIC), "", now())
            MonitorNotif.micApp = null
        }
        updateOngoing()
        broadcastUpdate()
    }

    /** Packages that are never the "user" of the camera/mic: launcher, system UI, system consent
     *  dialogs (VPN request / permission prompts), ourselves. */
    private val ignoredPkgs: Set<String> by lazy {
        val out = HashSet<String>()
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, 0).forEach { out.add(it.activityInfo.packageName) }
        }
        out.add("com.android.systemui")
        out.add("com.android.vpndialogs")                 // the VPN-consent dialog we ourselves trigger
        out.add("com.android.permissioncontroller")       // runtime-permission prompts
        out.add("com.google.android.permissioncontroller")
        out.add(packageName)
        Log.i(TAG, "ignored (launcher/systemui/dialogs/self): $out")
        out
    }

    private fun isRealApp(pkg: String?): Boolean =
        !pkg.isNullOrBlank() && pkg !in ignoredPkgs && !pkg.startsWith("com.android.systemui")

    /** True only if [pkg] currently holds [perm] (granted). An app that doesn't hold CAMERA /
     *  RECORD_AUDIO cannot be the sensor's user, so it is never attributed the event. */
    private fun holdsPermission(pkg: String, perm: String): Boolean =
        runCatching { packageManager.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    /** Prefer the exact AppOps camera user if fresh; else the current foreground app. */
    private fun whoPkg(): String? {
        val fresh = System.currentTimeMillis() - lastCameraUserAt < 4000
        val pkg = (if (fresh) lastCameraUserPkg else null) ?: foregroundApp()
        Log.i(TAG, "whoPkg -> $pkg (appOpsFresh=$fresh)")
        return pkg
    }

    private fun foregroundApp(): String? {
        val n = System.currentTimeMillis()
        // 1) Most recent foreground (RESUMED) event for a *real* app.
        val fromEvents = runCatching {
            val events = usage.queryEvents(n - 10 * 60_000L, n + 5_000L)
            var best: String? = null
            var bestTime = 0L
            var seen = 0
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                @Suppress("DEPRECATION")
                val fg = e.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                if (fg && isRealApp(e.packageName)) {
                    seen++
                    if (e.timeStamp >= bestTime) { bestTime = e.timeStamp; best = e.packageName }
                }
            }
            Log.i(TAG, "queryEvents: realResumed=$seen best=$best")
            best
        }.getOrElse { Log.w(TAG, "queryEvents failed: ${it.message}"); null }
        if (fromEvents != null) return fromEvents
        // 2) Fallback: most-recently-used real app across usage-stats intervals (INTERVAL_BEST,
        //    like the old v3.0 build, plus DAILY), but only if used very recently so we never
        //    name a stale app just because it's the newest non-launcher.
        val best = listOf(UsageStatsManager.INTERVAL_BEST, UsageStatsManager.INTERVAL_DAILY)
            .flatMap { interval ->
                runCatching { usage.queryUsageStats(interval, n - 2 * 3600_000L, n + 5_000L).orEmpty() }
                    .getOrDefault(emptyList())
            }
            .filter { isRealApp(it.packageName) && it.lastTimeUsed > 0 }
            .maxByOrNull { it.lastTimeUsed }
        val ago = best?.let { n - it.lastTimeUsed } ?: -1L
        Log.i(TAG, "queryUsageStats best=${best?.packageName} ago=${ago}ms")
        return if (best != null && ago in 0..120_000L) best.packageName else null
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        Log.w(TAG, "appLabel failed for $pkg: ${e.message}")
        pkg
    }

    private fun lastLabel(type: String): String =
        History.events(this, type).firstOrNull { it.isOn }?.label ?: ""

    private fun now() = System.currentTimeMillis()

    private fun broadcastUpdate() {
        sendBroadcast(Intent(History.ACTION_UPDATE).setPackage(getPackageName()))
    }

    // ---- notifications ----
    // The ongoing notification is shared via MonitorNotif so the camera/mic monitor and the
    // connection monitor collapse into ONE notification when both run.

    private fun updateOngoing() = MonitorNotif.update(this)

    private fun createChannels() {
        MonitorNotif.ensureChannel(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_ALERT, "Sensor alerts", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // MIUI/Xiaomi (and some other OEMs) kill this service when the app is swiped out of
        // recents. If the user hasn't tapped Stop, schedule a quick restart so monitoring keeps
        // running in the background. (Battery-optimization exemption still helps it stick.)
        if (isEnabled(this)) {
            runCatching {
                val pi = PendingIntent.getForegroundService(
                    this, 7, Intent(this, WatchService::class.java),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                getSystemService(AlarmManager::class.java)
                    .set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1500, pi)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Do NOT clear the enabled flag here — onDestroy also fires when the OS kills us,
        // and we want to auto-recover on next app open. Explicit stop clears it in the UI.
        started = false
        runCatching { cameraManager.unregisterAvailabilityCallback(availabilityCallback) }
        runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (opWatcher as? AppOpsCameraWatcher)?.stop()
        }
        // Leave the shared notification up if the connection monitor is still running; else drop it.
        MonitorNotif.cameraApp = null; MonitorNotif.micApp = null
        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
        MonitorNotif.refreshOrCancel(this)
        broadcastUpdate()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "CameraWatch"
        const val CH_ONGOING = "watch_ongoing"
        const val CH_ALERT = "watch_alert"
        const val NOTIF_ONGOING = 1
        const val NOTIF_ALERT = 2

        fun isEnabled(ctx: Context) =
            ctx.getSharedPreferences("camerawatch", Context.MODE_PRIVATE).getBoolean("enabled", false)

        fun setEnabled(ctx: Context, v: Boolean) =
            ctx.getSharedPreferences("camerawatch", Context.MODE_PRIVATE).edit().putBoolean("enabled", v).apply()
    }
}
