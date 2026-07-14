package com.privacy.camerawatch.net

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.privacy.camerawatch.MainActivity
import com.privacy.camerawatch.MonitorNotif
import com.privacy.camerawatch.Prefs
import com.privacy.camerawatch.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * DNS-capture VPN. Routes ONLY the stub DNS address through the tunnel, so normal
 * traffic is untouched (internet keeps working). Reads each DNS query, records the
 * domain + GeoIP country of the answer, forwards to a real resolver, writes the
 * reply back. No per-connection notifications; optional rate-limited country alerts.
 */
class SniffVpnService : VpnService() {

    private var vpn: ParcelFileDescriptor? = null
    private var thread: Thread? = null
    @Volatile private var alive = false
    private var lastAlertAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown(); stopSelf(); return START_NOT_STICKY
        }
        createChannels()
        try {
            startForeground(MonitorNotif.ID, MonitorNotif.build(this))
        } catch (e: Exception) {
            Log.w(TAG, "startForeground refused: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (alive) return
        alive = true
        Prefs.setConnEnabled(this, true)
        MonitorNotif.update(this) // reflect "connections" in the shared notification
        thread = Thread { runTunnel() }.also { it.start() }
        Log.i(TAG, "DNS-capture VPN thread started")
    }

    /**
     * Establishes the tunnel and pumps packets. If the tunnel drops unexpectedly (the OS closes the
     * fd, a transient error, etc.) while we're still meant to be running, it re-establishes with
     * backoff instead of silently dying — this is what stops the monitor "cutting off after a while"
     * with the notification still up. Only a real Stop / revoke (alive=false) ends the loop.
     */
    private fun runTunnel() {
        GeoIp.init(this) // load the 2 MB GeoIP DB off the main thread
        var backoff = 1000L
        var establishFails = 0
        while (alive) {
            val pfd = runCatching { establishTunnel() }.getOrNull()
            if (pfd == null) {
                if (!alive) break
                if (++establishFails >= 6) {            // can't get a tunnel at all → tell the user
                    Log.w(TAG, "giving up establishing tunnel after $establishFails tries")
                    alertStopped(); stopSelf(); return
                }
                try { Thread.sleep(backoff) } catch (e: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(30_000L)
                continue
            }
            establishFails = 0; backoff = 1000L
            vpn = pfd
            MonitorNotif.update(this)
            Log.i(TAG, "DNS-capture tunnel established")
            pump(pfd)                                   // blocks until the tunnel breaks or we stop
            runCatching { pfd.close() }
            if (!alive) break
            Log.w(TAG, "tunnel dropped — reconnecting")
        }
    }

    private fun establishTunnel(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("YourSnitch")
            .addAddress(TUN_ADDR, 32)
            .addDnsServer(DNS_STUB)
            .addRoute(DNS_STUB, 32) // only DNS to the stub is captured; everything else is normal
        runCatching { builder.addDisallowedApplication(packageName) }
        return builder.establish()
    }

    /** Reads packets until the tunnel fd breaks (read throws or hits EOF) or we're stopped. */
    private fun pump(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        while (alive) {
            val len = try { input.read(buf) } catch (e: Exception) { return } // broke → reconnect
            if (len < 0) return                                               // EOF → reconnect
            if (len == 0) continue
            runCatching { handle(buf, len, output) }
        }
    }

    private fun alertStopped() {
        runCatching {
            val n = Notification.Builder(this, CH_ALERT)
                .setContentTitle("Connection monitor stopped")
                .setContentText("Tap to restart monitoring your connections.")
                .setSmallIcon(R.drawable.ic_stat_globe)
                .setContentIntent(contentPI())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, n)
        }
    }

    private fun handle(buf: ByteArray, len: Int, out: FileOutputStream) {
        if (Packets.ipVersion(buf) != 4 || Packets.protocol(buf) != Packets.PROTO_UDP) return
        val ipHdr = Packets.ihl(buf)
        if (Packets.udpDstPort(buf, ipHdr) != 53) return
        val srcIp = Packets.srcIp(buf)
        val dstIp = Packets.dstIp(buf)
        val srcPort = Packets.udpSrcPort(buf, ipHdr)
        val payOff = Packets.udpPayloadOffset(ipHdr)
        if (len - payOff <= 0) return
        val query = buf.copyOfRange(payOff, len)
        val domain = Packets.dnsQueryName(query, 0, query.size)

        val resp = forward(query)
        if (resp == null) {
            Log.w(TAG, "no upstream reply for '$domain' (DNS would fail)")
            return
        }
        val ips = Packets.dnsAnswerIps(resp, 0, resp.size)
        if (domain != null && !domain.endsWith(".arpa")) {
            val ip = ips.firstOrNull()
            val ipStr = ip?.let { Packets.ipToString(it) } ?: ""
            val cc = ip?.let { GeoIp.country(it) } ?: "ZZ"
            record(domain, ipStr, cc)
        }
        val reply = Packets.buildUdp(dstIp, 53, srcIp, srcPort, resp)
        synchronized(out) { out.write(reply); out.flush() }
    }

    private val resolvers = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

    private fun forward(query: ByteArray): ByteArray? {
        for (r in resolvers) {
            try {
                DatagramSocket().use { sock ->
                    protect(sock)
                    sock.soTimeout = 3000
                    sock.send(DatagramPacket(query, query.size, InetAddress.getByName(r), 53))
                    val b = ByteArray(4096)
                    val dp = DatagramPacket(b, b.size)
                    sock.receive(dp)
                    return b.copyOf(dp.length)
                }
            } catch (e: Exception) {
                Log.w(TAG, "forward via $r failed: ${e.message}")
            }
        }
        return null
    }

    private fun record(domain: String, ip: String, cc: String) {
        Log.i(TAG, "captured $domain -> $ip [$cc]")
        ConnStore.add(this, domain, ip, cc, System.currentTimeMillis())
        sendBroadcast(Intent(ConnStore.ACTION_UPDATE).setPackage(packageName))
        if (cc in Prefs.watchedCountries(this)) {
            val now = System.currentTimeMillis()
            if (now - lastAlertAt >= 120_000) { // max 1 alert / 2 min
                lastAlertAt = now
                alertCountry(cc, domain)
            }
        }
    }

    private fun teardown() {
        alive = false
        Prefs.setConnEnabled(this, false)
        runCatching { vpn?.close() }
        vpn = null
        thread?.interrupt()
        // Leave the shared notification up if the camera/mic monitor is still running; else drop it.
        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
        MonitorNotif.refreshOrCancel(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the DNS/connection monitor alive when the app is swiped from recents (OEMs kill
        // the process). If the user hasn't stopped it, schedule a quick restart.
        if (Prefs.connEnabled(this)) {
            runCatching {
                val pi = PendingIntent.getForegroundService(
                    this, 8, Intent(this, SniffVpnService::class.java),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                getSystemService(AlarmManager::class.java)
                    .set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1500, pi)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onRevoke() { teardown(); stopSelf(); super.onRevoke() }
    override fun onDestroy() { teardown(); super.onDestroy() }

    // ---- notifications ----

    private fun contentPI() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun alertCountry(cc: String, domain: String) {
        val n = Notification.Builder(this, CH_ALERT)
            .setContentTitle("${GeoIp.flag(cc)} Connection to ${GeoIp.name(cc)}")
            .setContentText(domain)
            .setStyle(Notification.BigTextStyle().bigText("$domain\nresolved to a server in ${GeoIp.name(cc)}"))
            .setSmallIcon(R.drawable.ic_stat_globe)
            .setContentIntent(contentPI())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, n)
    }

    private fun createChannels() {
        MonitorNotif.ensureChannel(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH_ALERT, "Country alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    companion object {
        const val TAG = "YsSniff"
        const val CH = "ys_conn"
        const val CH_ALERT = "ys_conn_alert"
        const val NOTIF = 2001
        const val NOTIF_ALERT = 2002
        const val ACTION_STOP = "com.privacy.camerawatch.SNIFF_STOP"
        private const val DNS_STUB = "10.111.222.3"
        private const val TUN_ADDR = "10.111.222.2"
        private const val UPSTREAM = "1.1.1.1"
    }
}
