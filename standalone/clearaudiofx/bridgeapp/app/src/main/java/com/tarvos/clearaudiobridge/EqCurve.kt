package com.tarvos.clearaudiobridge

import android.media.audiofx.Equalizer
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Eq10-UNIQUE-Smiley-Kurve der ClearAudio+-App (KNOWLEDGE 301-(3), Rohwerte
 * 12,12,7,2,1,6,3,-5,-6,12 in 0.5-dB-Einheiten), als Zielkurve fuer den
 * Plattform-EQ: log-frequenz-lineare Interpolation zwischen den 10 Stuetz-
 * stellen, ausserhalb geclampt. Der Plattform-EQ hat typischerweise 5
 * Baelle mit geraeteabhaengigen Mitten (AOSP: 60/230/910/3600/14000 Hz);
 * gesetzt wird je Band der interpolierte Zielpegel in Milli-dB, geclammt
 * an die vom Geraet gemeldete Band-Level-Range.
 */
object EqCurve {

    // (Frequenz Hz, Zielpegel dB) - Eq10 UNIQUE
    val ANCHORS: List<Pair<Double, Double>> = listOf(
        31.25 to 6.0,
        62.5 to 6.0,
        125.0 to 3.5,
        250.0 to 1.0,
        500.0 to 0.5,
        1000.0 to 3.0,
        2000.0 to 1.5,
        4000.0 to -2.5,
        8000.0 to -3.0,
        16000.0 to 6.0,
    )

    /** Zielpegel in dB bei f Hz (log-lineare Interpolation, Rand geclampt). */
    fun targetDb(f: Double): Double {
        if (f <= ANCHORS.first().first) return ANCHORS.first().second
        if (f >= ANCHORS.last().first) return ANCHORS.last().second
        for (i in 0 until ANCHORS.size - 1) {
            val (f0, g0) = ANCHORS[i]
            val (f1, g1) = ANCHORS[i + 1]
            if (f in f0..f1) {
                val w = log2(f / f0) / log2(f1 / f0)
                return g0 + w * (g1 - g0)
            }
        }
        return 0.0
    }

    /**
     * Setzt die Kurve auf einen Equalizer. Liefert die beschriebenen
     * Baender als "(Hz: dB, ...)" fuer die Statusanzeige, oder null,
     * falls der Equalizer keine Baender meldet.
     */
    fun apply(eq: Equalizer): String? {
        val nb = eq.numberOfBands
        if (nb <= 0) return null
        val range = eq.bandLevelRange
        val lo = range[0].toInt()
        val hi = range[1].toInt()
        val sb = StringBuilder()
        for (b in 0 until nb) {
            val fcHz = eq.getCenterFreq(b.toShort()) / 1000.0   // API liefert milliHz
            val db = targetDb(fcHz)
            var lvl = (db * 100.0).roundToInt()
            if (lvl < lo) lvl = lo
            if (lvl > hi) lvl = hi
            eq.setBandLevel(b.toShort(), lvl.toShort())
            if (sb.isNotEmpty()) sb.append(", ")
            val fTxt = if (fcHz >= 1000.0) "${(fcHz / 1000.0)}k" else "${fcHz.roundToInt()}"
            sb.append(fTxt).append(" Hz: ").append(lvl / 100.0).append(" dB")
        }
        return sb.toString()
    }
}
