package com.tarvos.clearaudiotile

import java.io.File

/**
 * Minimaler su-Zugang (Magisk): getprop/setprop von
 * persist.clearaudio.enabled. Kein Root -> false/leer, UI zeigt
 * nicht verfuegbar. bewusst ohne LibSu: null Abhaengigkeiten.
 */
object Su {

    private fun runSu(cmd: String, timeoutMs: Long = 3000): String? {
        return try {
            val script = "timeout ${timeoutMs / 1000} $cmd\nexit \$?\n"
            val p = Runtime.getRuntime().exec(
                arrayOf("su", "-c", script.replace("\n", "; "))
            )
            val finished = pollWithTimeout(p, timeoutMs)
            if (!finished) {
                p.destroy()
                null
            } else if (p.exitValue() != 0) {
                null
            } else {
                p.inputStream.bufferedReader().readText().trim()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun pollWithTimeout(p: Process, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                p.exitValue()
                return true
            } catch (e: IllegalThreadStateException) {
                Thread.sleep(50)
            }
        }
        return false
    }

    fun suAvailable(): Boolean = runSu("true") != null

    /** Wert von persist.clearaudio.enabled; "" wenn nicht lesbar. */
    fun prop(): String = runSu("getprop persist.clearaudio.enabled") ?: ""

    /** Schreibt persist.clearaudio.enabled; true nur bei vorhandenem su. */
    fun setPropEnabled(enabled: Boolean): Boolean {
        val v = if (enabled) "1" else "0"
        val rc = runSu("setprop persist.clearaudio.enabled $v; getprop persist.clearaudio.enabled")
        return rc == v
    }

    /** Liest exclude-liste weglassen; Hilfsfunktion fuer Anzeige. */
    fun moduleInstalled(): Boolean {
        val so = runSu("ls /system/lib64/soundfx/libclearaudiofx.so /vendor/lib64/soundfx/libclearaudiofx.so 2>/dev/null")
        return so != null && so.contains("libclearaudiofx.so")
    }

    @Suppress("unused")
    private fun tmpTag() = File("/data/local/tmp").exists()
}
