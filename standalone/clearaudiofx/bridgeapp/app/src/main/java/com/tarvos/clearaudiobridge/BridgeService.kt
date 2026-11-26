package com.tarvos.clearaudiobridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.IBinder

/**
 * Vordergrund-Dienst, der einen Plattform-EQ auf dem globalen Output-Mix
 * (Audio-Session 0) haelt und mit der Eq10-UNIQUE-Kurve versieht.
 * Ueberlebt im Hintergrund (persistent Notification), re-asserted alle
 * 2 s (Geraete-Wechsel/BT kann Effekte zuruecksetzen), detected Fehler
 * der Session-0-Attach (manche Geraete blockieren das seit Android-Umbauten).
 *
 * KEINE Root-Voraussetzung. Aber auch nur die EQ-Kurve: die Dsx-Kette und
 * AntiClip koennen als eigene Effekt-Library nur mit Root in audioserver
 * geladen werden (audio_effects.xml). Das ist der dokumentierte Kompromiss
 * der Bruecke.
 */
class BridgeService : Service() {

    companion object {
        const val CHANNEL_ID = "clearaudio_bridge"
        const val NOTIF_ID = 42
        @Volatile var running = false
        @Volatile var lastError: String? = null
        @Volatile var lastDetail: String? = null
    }

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notif: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.status_on))
                .setSmallIcon(R.drawable.ic_bridge)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        running = true
        worker = Thread { loop() }.also { it.start() }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_MIN))
    }

    private fun loop() {
        var eq: Equalizer? = null
        while (running) {
            try {
                if (eq == null || eq.enabled.not()) {
                    eq?.release()
                    // Session 0 = globaler Output-Mix (deprecated, aber auf
                    // vielen Geraeten noch erlaubt; sonst wirft die Ausnahme)
                    eq = Equalizer(0, 0)
                    eq.enabled = true
                }
                val detail = EqCurve.apply(eq)
                lastError = null
                lastDetail = detail ?: "keine EQ-Baender"
            } catch (t: Throwable) {
                try { eq?.release() } catch (_: Throwable) {}
                eq = null
                lastError = (t.message ?: t.javaClass.simpleName)
                lastDetail = null
            }
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                return
            }
        }
        try { eq?.release() } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        running = false
        super.onDestroy()
    }
}
