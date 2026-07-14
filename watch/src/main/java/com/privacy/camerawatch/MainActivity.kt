package com.privacy.camerawatch

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.net.VpnService
import androidx.appcompat.app.AlertDialog
import com.privacy.camerawatch.net.ConnStore
import com.privacy.camerawatch.net.GeoIp
import com.privacy.camerawatch.net.SniffVpnService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dayFmt = SimpleDateFormat("MMM d", Locale.US)
    private var currentTab = 0
    private var connSub = 0
    private val reqVpn = 42

    private val accent2 = Color.parseColor("#8ea6ff")
    private val amber = Color.parseColor("#f59e0b")

    private data class Nav(val root: LinearLayout, val pill: View, val icon: ImageView, val label: TextView)
    private val navItems = ArrayList<Nav>()

    private data class AppUsage(
        val pkg: String, val label: String, val count: Int,
        val last: Long, val cam: Boolean, val mic: Boolean
    )

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    private var usagePrompted = false
    private var sentToUsage = false
    private var awaitingRestrictedReturn = false
    private var sentToBattery = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gradientText(findViewById(R.id.homeWordmark))

        findViewById<View>(R.id.wordmarkRow).setOnClickListener {
            startActivity(Intent(this, SplashActivity::class.java))
        }
        findViewById<View>(R.id.toggleBtn).setOnClickListener {
            if (WatchService.isEnabled(this)) stopMonitoring() else startMonitoring()
        }
        findViewById<View>(R.id.usageBtn).setOnClickListener { openUsageAccess() }
        findViewById<View>(R.id.clearBtn).setOnClickListener { confirmClearHistory() }
        findViewById<View>(R.id.keepBtn).setOnClickListener { requestKeepAlive() }
        findViewById<View>(R.id.connToggle).setOnClickListener { toggleSniff() }
        findViewById<View>(R.id.homeConnBtn).setOnClickListener { setTab(4) }
        findViewById<View>(R.id.subFlow).setOnClickListener { setConnSub(0) }
        findViewById<View>(R.id.subCountries).setOnClickListener { setConnSub(1) }
        findViewById<View>(R.id.subServers).setOnClickListener { setConnSub(2) }
        findViewById<View>(R.id.countryAlertBtn).setOnClickListener { pickCountries() }
        setConnSub(0)

        findViewById<View>(R.id.bootTrack).setOnClickListener {
            Prefs.setAutostart(this, !Prefs.autostart(this)); refresh()
        }
        findViewById<View>(R.id.camTrack).setOnClickListener {
            Prefs.setAlertCamera(this, !Prefs.alertCamera(this)); refresh()
        }
        findViewById<View>(R.id.micTrack).setOnClickListener {
            Prefs.setAlertMic(this, !Prefs.alertMic(this)); refresh()
        }

        (findViewById<TextView>(R.id.marquee)).isSelected = true

        val shimmer = findViewById<View>(R.id.shimmer)
        shimmer.post {
            val parentW = (shimmer.parent as View).width.toFloat()
            android.animation.ObjectAnimator.ofFloat(
                shimmer, View.TRANSLATION_X, -shimmer.width.toFloat(), parentW + shimmer.width
            ).apply {
                duration = 2600
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        }

        buildNav()

        if (intent?.getBooleanExtra("autostart", false) == true) startMonitoring()
        if (intent?.getBooleanExtra("startsniff", false) == true) startSniff()
        setTab(0)
        selectTabFromIntent()

        // First launch: show the one-time prominent disclosure BEFORE requesting any permission.
        if (!Prefs.disclosureAccepted(this)) showWelcomeDisclosure()
        else requestNotifPermission()
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /**
     * One-time first-run disclosure: plainly states what the app does, what it accesses, and that
     * everything stays on-device. Shown before any permission request (Google Play prominent-
     * disclosure best practice for sensitive-permission / VPN apps). Non-cancelable so the user
     * must actively acknowledge it.
     */
    private fun showWelcomeDisclosure() {
        dialog()
            .setTitle("Welcome to YourSnitch")
            .setMessage(
                "YourSnitch watches YOUR device and shows you:\n\n" +
                    "📷  When your camera or microphone turn on — and which app did it.\n" +
                    "🌍  Which countries your apps connect to, using a local on-device VPN.\n\n" +
                    "Your privacy:\n" +
                    "•  Everything stays on THIS phone.\n" +
                    "•  Nothing is uploaded, sold, or shared — no servers, no ads, no tracking.\n" +
                    "•  The VPN is local: it only inspects DNS to find countries. Your traffic is " +
                    "not routed through any outside server.\n\n" +
                    "YourSnitch never opens your camera or mic itself — it only tells you what other " +
                    "apps are doing."
            )
            .setCancelable(false)
            .setPositiveButton("Got it — continue") { _, _ ->
                Prefs.setDisclosureAccepted(this, true)
                requestNotifPermission()
            }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectTabFromIntent()
    }

    private fun selectTabFromIntent() {
        val t = intent?.getIntExtra("tab", -1) ?: -1
        if (t in 0..5) setTab(t)
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter().apply {
            addAction(History.ACTION_UPDATE)
            addAction(ConnStore.ACTION_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(updateReceiver, f)
        // Recover monitoring if it was enabled but the OS killed the service.
        if (WatchService.isEnabled(this)) {
            ContextCompat.startForegroundService(this, Intent(this, WatchService::class.java))
        }
        when {
            // Back from the battery/background screen → continue to Usage Access.
            sentToBattery -> { sentToBattery = false; runSetupFlow() }
            // Back from allowing restricted settings → move on to Usage Access.
            awaitingRestrictedReturn -> {
                awaitingRestrictedReturn = false
                if (!hasUsageAccess()) promptUsageGrant()
            }
            // Back from the Usage Access screen.
            sentToUsage -> {
                sentToUsage = false
                if (hasUsageAccess())
                    android.widget.Toast.makeText(this, "App-name detection on ✓", android.widget.Toast.LENGTH_SHORT).show()
                else showRestrictedHelp() // still blocked → re-guide to Allow restricted settings
            }
            WatchService.isEnabled(this) -> runSetupFlow()
        }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(updateReceiver) }
    }

    // ---- nav ----

    private fun buildNav() {
        val bar = findViewById<LinearLayout>(R.id.bottomNav)
        val defs = listOf(
            Triple("Home", R.drawable.ic_home, 0),
            Triple("Camera", R.drawable.ic_tab_camera, 1),
            Triple("Mic", R.drawable.ic_tab_mic, 2),
            Triple("Apps", R.drawable.ic_tab_advanced, 3),
            Triple("Net", R.drawable.ic_tab_conn, 4),
            Triple("Settings", R.drawable.ic_tab_settings, 5),
        )
        for ((label, iconRes, idx) in defs) {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, dp(6), 0, 0)
                isClickable = true
                setOnClickListener { setTab(idx) }
            }
            val pill = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(30))
                setBackgroundResource(R.drawable.nav_pill)
            }
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            }
            pill.addView(icon)
            val lab = TextView(this).apply {
                text = label
                textSize = 10.5f
                setTextColor(accent2)
                typeface = ResourcesCompatFont()
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            root.addView(pill)
            root.addView(lab)
            bar.addView(root)
            navItems.add(Nav(root, pill, icon, lab))
        }
    }

    private fun ResourcesCompatFont() =
        androidx.core.content.res.ResourcesCompat.getFont(this, R.font.space_grotesk)

    private fun setTab(tab: Int) {
        currentTab = tab
        findViewById<View>(R.id.homeView).visibility = if (tab == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cameraView).visibility = if (tab == 1) View.VISIBLE else View.GONE
        findViewById<View>(R.id.micView).visibility = if (tab == 2) View.VISIBLE else View.GONE
        findViewById<View>(R.id.advancedView).visibility = if (tab == 3) View.VISIBLE else View.GONE
        findViewById<View>(R.id.connectionsView).visibility = if (tab == 4) View.VISIBLE else View.GONE
        findViewById<View>(R.id.settingsView).visibility = if (tab == 5) View.VISIBLE else View.GONE
        navItems.forEachIndexed { i, n ->
            val active = i == tab
            n.pill.setBackgroundResource(if (active) R.drawable.nav_pill else 0)
            n.icon.setColorFilter(if (active) accent2 else Color.parseColor("#7c869c"))
            n.label.visibility = if (active) View.VISIBLE else View.GONE
        }
        refresh()
    }

    // ---- monitoring ----

    private fun startMonitoring() {
        ContextCompat.startForegroundService(this, Intent(this, WatchService::class.java))
        WatchService.setEnabled(this, true); refresh()
        // First-run setup prompts, in priority order: background/battery first, then Usage Access.
        runSetupFlow()
    }

    private fun stopMonitoring() {
        stopService(Intent(this, WatchService::class.java))
        WatchService.setEnabled(this, false); refresh()
    }

    private fun openUsageAccess() {
        // Flag so onResume can detect if the OS silently blocked the toggle (restricted settings).
        sentToUsage = true
        // Try to land directly on YourSnitch's own Usage Access page; fall back to the list.
        val direct = runCatching {
            startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null))
            )
        }.isSuccess
        if (!direct) runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }

    // Step 1 (Xiaomi only): allow restricted settings so the Usage Access switch becomes tappable.
    private fun promptRestrictedFirst() {
        dialog()
            .setTitle("Allow restricted settings")
            .setMessage("So YourSnitch can name the apps using your camera & mic, first allow restricted settings:\n\nApp info → ⋮ → Allow restricted settings.")
            .setPositiveButton("Allow restricted settings") { _, _ -> awaitingRestrictedReturn = true; openAppInfo() }
            .setNegativeButton("Later", null)
            .show()
    }

    // Step 2: grant Usage Access.
    private fun promptUsageGrant() {
        dialog()
            .setTitle("Turn on Usage Access")
            .setMessage("Tap “Open Usage Access”, find YourSnitch and switch it on.\n\nWithout it, apps show as “Unknown”.")
            .setPositiveButton("Open Usage Access") { _, _ -> openUsageAccess() }
            .setNegativeButton("Later", null)
            .show()
    }

    /** A dialog builder pre-styled with the app's dark Nebula theme. */
    private fun dialog() = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.YsDialog)

    private fun openAppInfo() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)
                )
            )
        }
    }

    /**
     * First-run setup prompts in priority order — background/battery first (most important, it's
     * what keeps monitoring alive), then Usage Access. One dialog at a time; each step chains to
     * the next as it's handled.
     */
    private fun runSetupFlow() {
        when {
            // Ask about background/battery once, ever (persisted) — MIUI's grant doesn't reliably
            // flip isIgnoringBatteryOptimizations, so re-asking would nag every launch.
            !isBatteryExempt() && !Prefs.batteryAsked(this) -> {
                Prefs.setBatteryAsked(this, true); promptBattery()
            }
            !hasUsageAccess() && !usagePrompted -> {
                usagePrompted = true
                if (isXiaomiRom()) promptRestrictedFirst() else promptUsageGrant()
            }
        }
    }

    private fun isXiaomiRom(): Boolean {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return "xiaomi" in m || "redmi" in m || "poco" in m
    }

    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        return Build.VERSION.SDK_INT < 23 || pm?.isIgnoringBatteryOptimizations(packageName) == true
    }

    private fun promptBattery() {
        dialog()
            .setTitle("Keep monitoring alive")
            .setMessage(
                "Most important: let YourSnitch run in the background (ignore battery optimization) " +
                    "so it keeps watching after you close it.\n\nOn Xiaomi, also turn on Autostart for " +
                    "YourSnitch in the app's settings page."
            )
            .setPositiveButton("Allow") { _, _ -> sentToBattery = true; requestKeepAlive() }
            .setNegativeButton("Later") { _, _ -> runSetupFlow() } // move on to Usage Access
            .show()
    }

    /**
     * Android 13+ hides Usage Access / Accessibility behind "restricted settings" for apps
     * installed outside an app store. No app can toggle that itself, so we hand the user the
     * exact steps + a one-tap shortcut to App info. Play Store installs never hit this.
     */
    private fun showRestrictedHelp() {
        dialog()
            .setTitle("Android blocked the switch")
            .setMessage(
                "Because YourSnitch was installed from a file (not an app store), Android hides the " +
                    "Usage Access switch behind an extra confirmation:\n\n" +
                    "1.  Tap “Open App info” below\n" +
                    "2.  Tap the ⋮ menu (top-right)\n" +
                    "3.  Choose “Allow restricted settings”\n" +
                    "4.  Come back and turn on Usage Access\n\n" +
                    "Installs from the Play Store skip this step entirely."
            )
            .setPositiveButton("Open App info") { _, _ -> openAppInfo() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestKeepAlive() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 23 && pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            val ok = runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }.isSuccess
            if (ok) return
        }
        // Already exempt, or the dialog isn't available — open app details so the user
        // can reach Autostart / battery settings (needed on MIUI).
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)
                )
            )
        }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        // unsafeCheckOpNoThrow was added in API 29; checkOpNoThrow works back to API 26.
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun confirmClearHistory() {
        dialog()
            .setTitle("Clear history?")
            .setMessage("This permanently removes all recorded camera & mic events. This can't be undone.")
            .setPositiveButton("Clear") { _, _ ->
                History.clear(this); refresh()
                android.widget.Toast.makeText(this, "History cleared", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- render ----

    private fun refresh() {
        val on = WatchService.isEnabled(this)
        val green = ContextCompat.getColor(this, R.color.ys_green)
        val red = ContextCompat.getColor(this, R.color.ys_red)
        val col = if (on) green else red

        findViewById<View>(R.id.statusDot).backgroundTintList = android.content.res.ColorStateList.valueOf(col)
        findViewById<TextView>(R.id.statusLabel).apply {
            text = if (on) "PROTECTED" else "UNPROTECTED"; setTextColor(col)
        }
        findViewById<TextView>(R.id.statusTitle).text = if (on) "Monitoring active" else "Monitoring stopped"
        findViewById<TextView>(R.id.statusSub).text =
            if (on) "Watching camera & mic in real time" else "Camera & mic are not being watched"
        findViewById<TextView>(R.id.toggleBtn).apply {
            text = if (on) "Stop monitoring" else "Start monitoring"
            setBackgroundResource(if (on) R.drawable.btn_accent else R.drawable.btn_green)
        }

        findViewById<TextView>(R.id.marquee).text = marqueeText(on)

        // stats
        val cam = History.events(this, History.CAMERA)
        val mic = History.events(this, History.MIC)
        findViewById<TextView>(R.id.statCam).text = todayOnCount(cam).toString()
        findViewById<TextView>(R.id.statMic).text = todayOnCount(mic).toString()
        findViewById<TextView>(R.id.statApps).text =
            (cam + mic).map { it.pkg }.filter { it.isNotBlank() }.toSet().size.toString()

        // recent (home)
        val recentBox = findViewById<LinearLayout>(R.id.recentList)
        recentBox.removeAllViews()
        val recent = (cam + mic).filter { it.isOn }.sortedByDescending { it.millis }.take(4)
        findViewById<View>(R.id.recentEmpty).visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        for (e in recent) addCard(recentBox, e, e.type, live = false, compact = true)

        if (currentTab == 1) renderCamera(cam)
        if (currentTab == 2) renderMic(mic)
        if (currentTab == 3) renderAdvanced(cam + mic)
        if (currentTab == 4) renderConnections()

        // settings toggles
        applyToggle(R.id.bootTrack, R.id.bootKnob, Prefs.autostart(this))
        applyToggle(R.id.camTrack, R.id.camKnob, Prefs.alertCamera(this))
        applyToggle(R.id.micTrack, R.id.micKnob, Prefs.alertMic(this))
        findViewById<View>(R.id.usageBtn).visibility = if (hasUsageAccess()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.keepBtn).apply {
            if (isBatteryExempt()) {
                text = "Background run: allowed ✓"
                setBackgroundResource(R.drawable.glass_card_sm)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ys_green))
            } else {
                text = "⚠ Allow background running"
                setBackgroundResource(R.drawable.btn_amber)
                setTextColor(Color.parseColor("#1a1205"))
            }
        }
        val watched = Prefs.watchedCountries(this)
        findViewById<TextView>(R.id.countryAlertBtn).text =
            if (watched.isEmpty()) "Country alerts: off"
            else "Country alerts: " + watched.joinToString(", ") { GeoIp.name(it) }
    }

    private fun renderCamera(cam: List<History.Event>) {
        val activeNow = cam.firstOrNull()?.isOn == true
        findViewById<View>(R.id.camBanner).visibility = if (activeNow) View.VISIBLE else View.GONE
        if (activeNow) {
            findViewById<TextView>(R.id.camBannerText).text =
                "${cam.first().label.ifBlank { "An app" }} is using your camera now"
        }
        val box = findViewById<LinearLayout>(R.id.cameraList)
        box.removeAllViews()
        findViewById<View>(R.id.cameraEmpty).visibility = if (cam.isEmpty()) View.VISIBLE else View.GONE
        cam.forEachIndexed { i, e -> addCard(box, e, History.CAMERA, live = (i == 0 && e.isOn), compact = false) }
    }

    private fun renderMic(mic: List<History.Event>) {
        val activeNow = mic.firstOrNull()?.isOn == true
        findViewById<View>(R.id.micActiveCard).visibility = if (activeNow) View.VISIBLE else View.GONE
        if (activeNow) {
            findViewById<TextView>(R.id.micActiveText).text =
                "${mic.first().label.ifBlank { "An app" }} is listening"
        }
        val box = findViewById<LinearLayout>(R.id.micList)
        box.removeAllViews()
        findViewById<View>(R.id.micEmpty).visibility = if (mic.isEmpty()) View.VISIBLE else View.GONE
        mic.forEachIndexed { i, e -> addCard(box, e, History.MIC, live = (i == 0 && e.isOn), compact = false) }
    }

    // ---- connections ----

    private fun toggleSniff() {
        if (Prefs.connEnabled(this)) {
            startService(Intent(this, SniffVpnService::class.java).setAction(SniffVpnService.ACTION_STOP))
            Prefs.setConnEnabled(this, false)
            renderConnections()
        } else {
            val prep = VpnService.prepare(this)
            if (prep != null) startActivityForResult(prep, reqVpn) else startSniff()
        }
    }

    private fun startSniff() {
        ContextCompat.startForegroundService(this, Intent(this, SniffVpnService::class.java))
        renderConnections()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqVpn && resultCode == RESULT_OK) startSniff()
    }

    private fun setConnSub(i: Int) {
        connSub = i
        findViewById<View>(R.id.connFlowView).visibility = if (i == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.connCountriesView).visibility = if (i == 1) View.VISIBLE else View.GONE
        findViewById<View>(R.id.connServersView).visibility = if (i == 2) View.VISIBLE else View.GONE
        styleSeg(R.id.subFlow, i == 0)
        styleSeg(R.id.subCountries, i == 1)
        styleSeg(R.id.subServers, i == 2)
        renderConnections()
    }

    private fun styleSeg(id: Int, active: Boolean) {
        findViewById<TextView>(id).apply {
            setBackgroundResource(if (active) R.drawable.nav_pill else 0)
            setTextColor(ContextCompat.getColor(this@MainActivity, if (active) R.color.ys_accent2 else R.color.ys_text2))
        }
    }

    private fun renderConnections() {
        val on = Prefs.connEnabled(this)
        findViewById<TextView>(R.id.connToggle).apply {
            text = if (on) "Stop connection monitor" else "Start connection monitor"
            setBackgroundResource(if (on) R.drawable.btn_accent else R.drawable.btn_green)
            setTextColor(Color.WHITE)
        }
        val conns = ConnStore.events(this)
        when (connSub) {
            0 -> renderFlow(conns)
            1 -> renderConnAgg(conns, byCountry = true)
            else -> renderConnAgg(conns, byCountry = false)
        }
    }

    private fun renderFlow(conns: List<ConnStore.Conn>) {
        val box = findViewById<LinearLayout>(R.id.connFlowList)
        box.removeAllViews()
        val recent = conns.take(15)
        findViewById<View>(R.id.connFlowEmpty).visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        recent.forEachIndexed { i, c ->
            val card = layoutInflater.inflate(R.layout.item_flow, box, false)
            card.findViewById<TextView>(R.id.flowFlag).text = GeoIp.flag(c.cc)
            card.findViewById<TextView>(R.id.flowDomain).text = c.domain
            card.findViewById<TextView>(R.id.flowSub).text = "→ ${GeoIp.name(c.cc)} · ${relTime(c.millis)}"
            card.findViewById<View>(R.id.flowDot).visibility = if (i == 0) View.VISIBLE else View.GONE
            box.addView(card)
            if (i < recent.size - 1) {
                val conn = View(this)
                conn.setBackgroundColor(ContextCompat.getColor(this, R.color.ys_accent))
                conn.alpha = 0.5f
                val lp = LinearLayout.LayoutParams(dp(2), dp(14))
                lp.gravity = Gravity.CENTER_HORIZONTAL
                lp.topMargin = dp(3); lp.bottomMargin = dp(3)
                box.addView(conn, lp)
            }
        }
    }

    private fun renderConnAgg(conns: List<ConnStore.Conn>, byCountry: Boolean) {
        val listId = if (byCountry) R.id.connCountriesList else R.id.connServersList
        val emptyId = if (byCountry) R.id.connCountriesEmpty else R.id.connServersEmpty
        val box = findViewById<LinearLayout>(listId)
        box.removeAllViews()
        val groups = conns.groupBy { if (byCountry) it.cc else it.domain }
            .map { (k, list) -> Triple(k, list.size, list.maxOf { it.millis }) }
            .sortedByDescending { it.third }
        findViewById<View>(emptyId).visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        for ((key, count, last) in groups) {
            val row = layoutInflater.inflate(R.layout.item_conn, box, false)
            if (byCountry) {
                row.findViewById<TextView>(R.id.connIcon).text = GeoIp.flag(key)
                row.findViewById<TextView>(R.id.connName).text = GeoIp.name(key)
            } else {
                row.findViewById<TextView>(R.id.connIcon).text = "🌐"
                row.findViewById<TextView>(R.id.connName).text = key
            }
            row.findViewById<TextView>(R.id.connSub).text = relTime(last)
            row.findViewById<TextView>(R.id.connCount).text = count.toString()
            box.addView(row)
        }
    }

    private fun pickCountries() {
        val codes = java.util.Locale.getISOCountries()
            .sortedBy { java.util.Locale("", it).displayCountry }
        val names = codes.map { "${GeoIp.flag(it)}  ${java.util.Locale("", it).displayCountry}" }.toTypedArray()
        val watched = Prefs.watchedCountries(this).toMutableSet()
        val checked = BooleanArray(codes.size) { codes[it] in watched }
        dialog()
            .setTitle("Alert on connections to…")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) watched.add(codes[which]) else watched.remove(codes[which])
            }
            .setPositiveButton("Save") { _, _ -> Prefs.setWatchedCountries(this, watched); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderAdvanced(all: List<History.Event>) {
        val box = findViewById<LinearLayout>(R.id.advancedList)
        box.removeAllViews()
        val usages = all.filter { it.pkg.isNotBlank() }
            .groupBy { it.pkg }
            .map { (pkg, evs) ->
                AppUsage(
                    pkg = pkg,
                    label = evs.firstOrNull { it.label.isNotBlank() }?.label ?: pkg,
                    count = evs.count { it.isOn },
                    last = evs.maxOf { it.millis },
                    cam = evs.any { it.type == History.CAMERA },
                    mic = evs.any { it.type == History.MIC }
                )
            }
            .filter { it.count > 0 }
            .sortedByDescending { it.last }
        findViewById<View>(R.id.advancedEmpty).visibility = if (usages.isEmpty()) View.VISIBLE else View.GONE
        for (u in usages) {
            val row = layoutInflater.inflate(R.layout.item_appusage, box, false)
            val iv = row.findViewById<ImageView>(R.id.appIcon)
            val ic = runCatching { packageManager.getApplicationIcon(u.pkg) }.getOrNull()
            if (ic != null) { iv.setImageDrawable(ic); iv.clearColorFilter() }
            else { iv.setImageResource(R.drawable.ic_tab_advanced); iv.setColorFilter(accent2) }
            row.findViewById<TextView>(R.id.appName).text = u.label
            val sensors = when {
                u.cam && u.mic -> "Camera & Mic"
                u.mic -> "Microphone"
                else -> "Camera"
            }
            row.findViewById<TextView>(R.id.subtitle).text = "${relTime(u.last)} · $sensors"
            row.findViewById<TextView>(R.id.count).text = u.count.toString()
            box.addView(row)
        }
    }

    private fun relTime(millis: Long): String {
        val d = System.currentTimeMillis() - millis
        return when {
            d < 60_000 -> "${(d / 1000).coerceAtLeast(1)} sec ago"
            d < 3_600_000 -> "${d / 60_000} min ago"
            d < 86_400_000 -> "${d / 3_600_000} hr ago"
            else -> "${d / 86_400_000} days ago"
        }
    }

    private fun addCard(container: LinearLayout, e: History.Event, type: String, live: Boolean, compact: Boolean) {
        val card = layoutInflater.inflate(R.layout.item_event, container, false)
        card.findViewById<ImageView>(R.id.iconImg).apply {
            setImageResource(if (type == History.MIC) R.drawable.ic_tab_mic else R.drawable.ic_tab_camera)
            setColorFilter(accent2)
        }
        card.findViewById<TextView>(R.id.appName).text = e.label.ifBlank { "Unknown app" }
        card.findViewById<TextView>(R.id.action).text = actionText(type, e)
        card.findViewById<TextView>(R.id.time).text = timeLabel(e.millis)
        card.findViewById<View>(R.id.liveDot).visibility = if (live) View.VISIBLE else View.GONE
        card.findViewById<View>(R.id.accent).visibility = if (live) View.VISIBLE else View.INVISIBLE
        container.addView(card)
    }

    private fun applyToggle(trackId: Int, knobId: Int, on: Boolean) {
        val track = findViewById<View>(trackId)
        val knob = findViewById<View>(knobId)
        track.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (on) ContextCompat.getColor(this, R.color.ys_accent) else Color.parseColor("#26FFFFFF")
        )
        knob.animate().translationX(if (on) dp(20).toFloat() else 0f).setDuration(180).start()
    }

    // ---- helpers ----

    private fun marqueeText(on: Boolean): String {
        if (!on) return "Monitoring OFF · camera & mic are not being watched          Tap Start monitoring to resume          "
        val evs = (History.events(this, History.CAMERA) + History.events(this, History.MIC))
            .filter { it.isOn }.sortedByDescending { it.millis }.take(6)
        if (evs.isEmpty()) return "Monitoring active · no camera or mic access yet          "
        val kind = { t: String -> if (t == History.MIC) "Microphone" else "Camera" }
        return evs.joinToString("          ") { "${it.label} · ${kind(it.type)} · ${timeLabel(it.millis)}" } + "          "
    }

    private fun todayOnCount(list: List<History.Event>): Int {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return list.count { it.isOn && it.millis >= start }
    }

    private fun actionText(type: String, e: History.Event): String = when {
        type == History.MIC && e.isOn && e.withCamera -> "Started mic + camera (video / call)"
        type == History.MIC && e.isOn -> "Started using the microphone"
        type == History.MIC -> "Stopped using the microphone"
        e.isOn -> "Started using the camera"
        else -> "Stopped using the camera"
    }

    private fun timeLabel(millis: Long): String {
        val startToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val d = Date(millis)
        return if (millis >= startToday) timeFmt.format(d) else "${dayFmt.format(d)} ${timeFmt.format(d)}"
    }

    private fun gradientText(tv: TextView) {
        tv.post {
            val w = tv.paint.measureText(tv.text.toString())
            if (w > 0) {
                tv.paint.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Color.WHITE, accent2), null, Shader.TileMode.CLAMP)
                tv.invalidate()
            }
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}
