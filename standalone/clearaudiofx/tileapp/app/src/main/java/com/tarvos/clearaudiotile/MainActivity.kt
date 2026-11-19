package com.tarvos.clearaudiotile

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Statusscreen: zeigt ob der systemweite Effekt aktiv ist und bietet den
 * Umschalter (identisch zur Kachel). Voraussetzungen (Magisk-Modul + su)
 * werden angezeigt.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var detail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply {
            text = getString(R.string.status_fmt, "...")
            textSize = 20f
        }
        detail = TextView(this).apply {
            text = ""
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        val btn = Button(this).apply {
            text = getString(R.string.btn_toggle)
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
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        refresh()
    }

    private fun refresh() {
        Thread {
            if (!Su.suAvailable()) {
                runOnUiThread {
                    status.text = getString(R.string.status_fmt,
                        getString(R.string.status_unavailable))
                    detail.text = ""
                }
                return@Thread
            }
            val on = Su.prop() == "1"
            val mod = Su.moduleInstalled()
            runOnUiThread {
                status.text = getString(
                    R.string.status_fmt,
                    getString(if (on) R.string.status_on else R.string.status_off))
                detail.text = if (mod) {
                    "Modul: libclearaudiofx.so gefunden"
                } else {
                    "Modul: libclearaudiofx.so NICHT gefunden " +
                        "(Magisk-Modul installiert und neu gestartet?)"
                }
            }
        }.start()
    }

    private fun toggle() {
        Thread {
            val ok = Su.setPropEnabled(Su.prop() != "1")
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, "su fehlgeschlagen", Toast.LENGTH_LONG)
                        .show()
                }
                refresh()
            }
        }.start()
    }
}
