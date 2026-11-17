// Util.kt - Hilfsfunktionen fuer generierte Tabellen (Phase 2e).
package com.tarvos.clearaudio

/** Konkateniert Double-Arrays (fuer gechunkte generierte Tabellen). */
fun cat(vararg parts: DoubleArray): DoubleArray {
    var n = 0
    for (p in parts) n += p.size
    val r = DoubleArray(n)
    var o = 0
    for (p in parts) {
        System.arraycopy(p, 0, r, o, p.size)
        o += p.size
    }
    return r
}
