package com.tarvos.clearaudiobridge

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Schaltzentrale der Bruecke: Ein/Aus startet bzw. stoppt den Vordergrund-
 * dienst, der Status zeigt live, ob Session-0 auf diesem Geraet erlaubt ist
 * und welche Baender mit welcher Eq10-UNIQUE-Pegel gesetzt wurden.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var btn: Button
    private var refresh: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply {
            text = getString(R.string.status_fmt, getString(R.string.status_off))
            textSize = 20f
        }
        detail = TextView(this).apply {
            text = ""
            textSize = 14f
            setPadding(0, pad / 2, 0, 0)
        }
        btn = Button(this).apply {
            text = getString(R.string.btn_toggle_on)
            setOnClickListener { toggle() }
        }
        val hint = TextView(this).apply {
            text = getString(R.string.hint)
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }
        root.addView(status)
        root.addView(btn)
        root.addView(detail)
        root.addView(hint)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        startRefresh()
    }

    override fun onPause() {
        super.onPause()
        refresh?.interrupt()
        refresh = null
    }

    private fun startRefresh() {
        refresh?.interrupt()
        refresh = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val running = BridgeService.running
                val err = BridgeService.lastError
                val det = BridgeService.lastDetail
                runOnUiThread {
                    val st = when {
                        !running -> getString(R.string.status_off)
                        err != null -> getString(R.string.status_blocked)
                        else -> getString(R.string.status_on)
                    }
                    status.text = getString(R.string.status_fmt, st)
                    btn.text = getString(
                        if (running) R.string.btn_toggle_off else R.string.btn_toggle_on)
                    detail.text = when {
                        !running -> ""
                        err != null -> "Session-0-Fehler: $err\n" +
                            "Dieses Gerät blockiert globale Effekte " +
                            "für normale Apps. Dann bleibt nur der " +
                            "Root-Weg (Magisk-Modul)."
                        else -> "Plattform-EQ auf Session 0 aktiv:\n$det"
                    }
                }
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.also { it.start() }
    }

    private fun toggle() {
        if (!BridgeService.running) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            startForegroundService(Intent(this, BridgeService::class.java))
        } else {
            stopService(Intent(this, BridgeService::class.java))
        }
    }
}
