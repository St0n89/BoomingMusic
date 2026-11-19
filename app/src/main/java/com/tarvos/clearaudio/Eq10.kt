package com.tarvos.clearaudio

/**
 * ClearAudio+ Eq10 (HQEQ_10Band, UNIQUE-Preset) - 1:1-Port des Firmware-
 * Kernels 0x11b8f0/Pfad B (audio.primary.icx1293.so 4.04), bitweise gegen
 * die Emulator-Referenz abgenommen (KNOWLEDGE 303; Random-Stereo, je Rate
 * 512/1024 Samples 0 falsch):
 *
 *   out = dry + SUM band_b,  b = 0..9 (10 parallele Peaking-Biquads)
 *   Biquad (f64 intern, exakte Oporder des Kernels 0x11cb04):
 *     d21 = (b1*x1 + b2*x2) + b0*x
 *     d7  = a2*y2 + d21
 *     y   = a1*y1 + d7            (Ausgabe je Sample f32-gerundet)
 *   Zustaende x1/x2/y1/y2 nur an 64er-Blockgrenzen f32-persistiert
 *   (Kernel: str/ldr f32; intern f64 weitergefuehrt).
 *   env-Stufe 0x12e6a4 (Attack/Release-Peak-Dynamik) ist im UNIQUE-Preset
 *   empirisch exakt 1.0 (Stille bis -0.4 dBFS) => bit-neutraler Unity-Pass,
 *   hier bewusst als x1.0 vereinfacht (KNOWLEDGE 303-(5)).
 *   Final: Hardclip > +1.0 / < -1.0 (f32-Vergleich, bit-treu zum
 *   sshr/csetm/bsl-Trick des Kernels).
 *
 * Koeffizienten: Sony-SE-Tabelle 0x16d658 (6 Raten-Modi x 10 Baender x 41
 * Gainstufen), hier UNIQUE-Sets als Bitmuster gebakt (fromBits, KEINE
 * Dezimal-Literale, KNOWLEDGE 289-Gotcha). 88.2 kHz = Mode 2 nach der
 * 44.1-k-Full-Chain, 96 kHz = Mode 3 nach der 48-k-Full-Chain (App laeuft
 * @2x Rate); der Kernel erwartet dort 128er-Calls, intern 2x64er-Subchunks,
 * deshalb verarbeitet dieser Port immer 64er-Bloecke (State-Rundung bleibt
 * am 64er-Raster wie in der Firmware).
 */
object Eq10Tables {
    val COEFFS_882: Array<FloatArray> = arrayOf(
        floatArrayOf(Float.fromBits(972027340), Float.fromBits(0), Float.fromBits(-1175456308), Float.fromBits(1073734068), Float.fromBits(-1082145863)),
        floatArrayOf(Float.fromBits(980408681), Float.fromBits(0), Float.fromBits(-1167074967), Float.fromBits(1073726237), Float.fromBits(-1082161279)),
        floatArrayOf(Float.fromBits(983096497), Float.fromBits(0), Float.fromBits(-1164387151), Float.fromBits(1073701731), Float.fromBits(-1082209291)),
        floatArrayOf(Float.fromBits(978425748), Float.fromBits(0), Float.fromBits(-1169057900), Float.fromBits(1073629247), Float.fromBits(-1082350299)),
        floatArrayOf(Float.fromBits(979043440), Float.fromBits(0), Float.fromBits(-1168440208), Float.fromBits(1073494772), Float.fromBits(-1082603554)),
        floatArrayOf(Float.fromBits(1006832546), Float.fromBits(0), Float.fromBits(-1140651102), Float.fromBits(1073374816), Float.fromBits(-1082780994)),
        floatArrayOf(Float.fromBits(1007661158), Float.fromBits(0), Float.fromBits(-1139822490), Float.fromBits(1072799176), Float.fromBits(-1083691555)),
        floatArrayOf(Float.fromBits(-1127373589), Float.fromBits(0), Float.fromBits(1020110059), Float.fromBits(1071449266), Float.fromBits(-1085498282)),
        floatArrayOf(Float.fromBits(-1118764732), Float.fromBits(0), Float.fromBits(1028718916), Float.fromBits(1068622261), Float.fromBits(-1087992934)),
        floatArrayOf(Float.fromBits(1049358030), Float.fromBits(0), Float.fromBits(-1098125618), Float.fromBits(1058744580), Float.fromBits(-1092170409)),
    )
    val COEFFS_96K: Array<FloatArray> = arrayOf(
        floatArrayOf(Float.fromBits(970750141), Float.fromBits(0), Float.fromBits(-1176733507), Float.fromBits(1073734700), Float.fromBits(-1082144609)),
        floatArrayOf(Float.fromBits(979132618), Float.fromBits(0), Float.fromBits(-1168351030), Float.fromBits(1073727515), Float.fromBits(-1082158775)),
        floatArrayOf(Float.fromBits(982284315), Float.fromBits(0), Float.fromBits(-1165199333), Float.fromBits(1073705031), Float.fromBits(-1082202898)),
        floatArrayOf(Float.fromBits(977316538), Float.fromBits(0), Float.fromBits(-1170167110), Float.fromBits(1073638536), Float.fromBits(-1082332544)),
        floatArrayOf(Float.fromBits(977892772), Float.fromBits(0), Float.fromBits(-1169590876), Float.fromBits(1073515361), Float.fromBits(-1082565625)),
        floatArrayOf(Float.fromBits(1005663497), Float.fromBits(0), Float.fromBits(-1141820151), Float.fromBits(1073407181), Float.fromBits(-1082729156)),
        floatArrayOf(Float.fromBits(1006933262), Float.fromBits(0), Float.fromBits(-1140550386), Float.fromBits(1072884228), Float.fromBits(-1083570884)),
        floatArrayOf(Float.fromBits(-1128342767), Float.fromBits(0), Float.fromBits(1019140881), Float.fromBits(1071660578), Float.fromBits(-1085256090)),
        floatArrayOf(Float.fromBits(-1119604115), Float.fromBits(0), Float.fromBits(1027879533), Float.fromBits(1069111129), Float.fromBits(-1087633676)),
        floatArrayOf(Float.fromBits(1049042595), Float.fromBits(0), Float.fromBits(-1098441053), Float.fromBits(1060904538), Float.fromBits(-1091536536)),
    )
}

/**
 * Eq10 je Kanal. processBlock64 verarbeitet GENAU 64 Samples (Firmware-
 * Blockraster); die Biquad-Zustaende laufen ueber Aufrufe und Trackgrenzen
 * fort, reset() stellt den Kaltstart (alles 0) her.
 */
class Eq10Channel(coeffs: Array<FloatArray>) {

    private val nb = coeffs.size
    private val b0 = DoubleArray(nb)
    private val b1 = DoubleArray(nb)
    private val b2 = DoubleArray(nb)
    private val a1 = DoubleArray(nb)
    private val a2 = DoubleArray(nb)

    // Biquad-Zustaende je Band: x1, x2, y1, y2 (f32-persistiert)
    private val sx1 = FloatArray(nb)
    private val sx2 = FloatArray(nb)
    private val sy1 = FloatArray(nb)
    private val sy2 = FloatArray(nb)

    // Scratch (keine Allokation im Frame)
    private val bandOut = Array(nb) { FloatArray(BLOCK) }

    init {
        for (b in 0 until nb) {
            b0[b] = coeffs[b][0].toDouble()
            b1[b] = coeffs[b][1].toDouble()
            b2[b] = coeffs[b][2].toDouble()
            a1[b] = coeffs[b][3].toDouble()
            a2[b] = coeffs[b][4].toDouble()
        }
    }

    /** Kaltstart: alle Biquad-Zustaende auf 0 (APK-Bootfall). */
    fun reset() {
        sx1.fill(0f); sx2.fill(0f); sy1.fill(0f); sy2.fill(0f)
    }

    /**
     * 64 Samples in-place: samples[off..off+64) treten in den Eq10 ein und
     * werden mit dem gefilterten Ergebnis ueberschrieben (dry + Baender,
     * final Hardclip +-1.0).
     */
    fun processBlock64(samples: FloatArray, off: Int) {
        for (b in 0 until nb) {
            val ob = bandOut[b]
            var x1 = sx1[b].toDouble()
            var x2 = sx2[b].toDouble()
            var y1 = sy1[b].toDouble()
            var y2 = sy2[b].toDouble()
            val bb0 = b0[b]; val bb1 = b1[b]; val bb2 = b2[b]
            val ba1 = a1[b]; val ba2 = a2[b]
            for (n in 0 until BLOCK) {
                val x = samples[off + n].toDouble()
                val d21 = (bb1 * x1 + bb2 * x2) + bb0 * x
                val d7 = ba2 * y2 + d21
                val y = ba1 * y1 + d7
                ob[n] = y.toFloat()
                x2 = x1; x1 = x
                y2 = y1; y1 = y
            }
            sx1[b] = x1.toFloat(); sx2[b] = x2.toFloat()
            sy1[b] = y1.toFloat(); sy2[b] = y2.toFloat()
        }
        for (n in 0 until BLOCK) {
            var acc = samples[off + n]
            for (b in 0 until nb) {
                acc += bandOut[b][n]
            }
            if (acc > 1f) acc = 1f
            if (acc < -1f) acc = -1f
            samples[off + n] = acc
        }
    }

    companion object {
        const val BLOCK = 64
    }
}
