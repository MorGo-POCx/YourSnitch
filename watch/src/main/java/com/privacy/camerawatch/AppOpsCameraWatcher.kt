package com.privacy.camerawatch

import android.app.AppOpsManager
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * Isolates the API-30 (Android 11) AppOps "active op" APIs in their own class.
 *
 * Because these types (OnOpActiveChangedListener, startWatchingActive) are referenced
 * ONLY here, the class is loaded/verified only when the caller first touches it — and
 * the caller does that solely behind a `Build.VERSION.SDK_INT >= R` guard. On Android
 * 8–10 this class is never loaded, so there is no risk of a VerifyError / NoClassDefFound
 * from the newer APIs. Below Android 11 the app falls back to the UsageStats heuristic.
 */
@RequiresApi(30)
class AppOpsCameraWatcher(
    private val context: Context,
    private val onCameraUser: (pkg: String) -> Unit,
) {
    private var listener: AppOpsManager.OnOpActiveChangedListener? = null

    fun start(executor: Executor) {
        val l = AppOpsManager.OnOpActiveChangedListener { op, _, packageName, active ->
            Log.i("CameraWatch", "AppOps callback: op=$op active=$active pkg=$packageName")
            if (op == AppOpsManager.OPSTR_CAMERA && active && packageName != context.packageName) {
                onCameraUser(packageName)
            }
        }
        try {
            context.getSystemService(AppOpsManager::class.java)
                .startWatchingActive(arrayOf(AppOpsManager.OPSTR_CAMERA), executor, l)
            listener = l
            Log.i("CameraWatch", "AppOps camera watcher registered OK")
        } catch (e: Exception) {
            Log.w("CameraWatch", "AppOps startWatchingActive FAILED: ${e.message}")
        }
    }

    fun stop() {
        val l = listener ?: return
        runCatching {
            context.getSystemService(AppOpsManager::class.java).stopWatchingActive(l)
        }
        listener = null
    }
}
