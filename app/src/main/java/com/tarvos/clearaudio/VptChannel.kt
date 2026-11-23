package com.tarvos.clearaudio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * ClearAudio+ VPT-Studio-Kanal (48 kHz, mode 0), STEREO-Port des gegen den
 * Live-Firmware-Emulator validierten Python-Referenzmodells Vpt48S
 * (vpt_stereo_model.py: Stereo-Musik corrL 0.999999/corrR 0.999998,
 * Stereo-Sinus-Schnellcheck corr 1.000000/maxd 7.6e-6; Mono-Lauf bit-identisch
 * zum alten gegen den Emulator validierten Mono-Modell).
 *
 * Kette pro Kernel-Lauf (jeder 2. Block, 512 Frames), STEREO:
 *   er (ioL>>=1, ioR>>=1) -> PHAN rohL=(8191*ioL)>>23 nach sb0-Slotring
 *   (L-Spektren) UND rohR=(8191*ioR)>>23 nach sb1-Slotring (R-Spektren) ->
 *   pro Runde (R1L+R1R -> ringL, R2L+R2R -> ringR): 4 Slots (phi-c)&3,
 *   je Slot 2 GAIN-Laeufe in denselben Akku: L-Fenster (sb0[slot] +
 *   Folge-Slot, bei slot==3 Spill in das frische sb1[0]-R-Spektrum, die
 *   NULL-Kurve bank[1] neutralisiert den Beitrag) mit Kurve R1/R2[c],
 *   dann R-Fenster (sb1[slot] + Folge-Slot, bei slot==3 Spill in sb2 =
 *   Nullen) mit Kurve R1R/R2R[c] ->
 *   hermitesche iDFT Skala 1/1024 (PERMB9, front = y[0:512]) ->
 *   MAC ring[i] = sat(((front[i]+bank[i])*0x7fff0000)>>17) -> bank <- back.
 * Ausgabe: 256 Frames pro Block; odd-Block ring[0:256], even ring[256:512];
 * Block 0 liefert Boot-Stille (Ring noch null). Beide Eingangskanaele
 * fliessen getrennt ein: outL = f(L, R), outR = g(L, R), dekoriert.
 */
class VptChannel {
    private val sb0 = Array(4) { IntArray(1032) }  // L-Spektren
    private val sb1 = Array(4) { IntArray(1032) }  // R-Spektren
    private var phi = 4
    private val bankL = IntArray(512)
    private val bankR = IntArray(512)
    private val ringL = IntArray(1024)
    private val ringR = IntArray(1024)
    private val histL = IntArray(512)
    private val histR = IntArray(512)
    private var histPos = 0
    private var totalFrames = 0
    private var blkIdx = 0

    private val re = DoubleArray(1024)
    private val im = DoubleArray(1024)
    private val srcF = IntArray(2048)
    private val akku = IntArray(2048)
    private val y = IntArray(1024)
    private val rohL = IntArray(512)
    private val rohR = IntArray(512)

    /**
     * Verarbeitet [inLen] Frames ab [inOff]; L- und R-Kanal fliessen getrennt
     * ein (keine Mono-Summe: das Original ist eine echte Stereo-Kette).
     * Pro abgeschlossenem 256er-Block fallen 256 Ausgabe-Frames
     * (Ring-Slice) ab [outOff] an. Liefert die Anzahl geschriebener Frames.
     */
    fun process(
        inL: FloatArray, inLOff: Int, inR: FloatArray, inROff: Int, inLen: Int,
        outL: FloatArray, outOff: Int, outR: FloatArray
    ): Int {
        var written = 0
        for (i in 0 until inLen) {
            histL[histPos] = f2fix(inL[inLOff + i])
            histR[histPos] = f2fix(inR[inROff + i])
            histPos = (histPos + 1) and 511
            totalFrames++
            if ((totalFrames and 255) == 0) {
                if ((blkIdx and 1) == 1 && totalFrames >= 512) kernelRun()
                val src = if ((blkIdx and 1) == 1) 0 else 256
                for (k in 0 until 256) {
                    outL[outOff + written + k] = ringL[src + k] * SCALE
                    outR[outOff + written + k] = ringR[src + k] * SCALE
                }
                written += 256
                blkIdx++
            }
        }
        return written
    }

    private fun f2fix(v: Float): Int {
        val d = v.toDouble() * 2147483648.0
        return when {
            d >= 2147483647.0 -> 2147483647
            d <= -2147483648.0 -> -2147483648
            else -> d.toInt()
        }
    }

    private fun kernelRun() {
        val p = phi and 3
        for (j in 0 until 512) {
            val ioL = histL[(histPos + j) and 511] shr 1
            val ioR = histR[(histPos + j) and 511] shr 1
            rohL[j] = ((8191L * ioL) shr 23).toInt()
            rohR[j] = ((8191L * ioR) shr 23).toInt()
        }
        rdft1024(rohL, sb0[p])
        rdft1024(rohR, sb1[p])
        for (round in 0..1) {
            val kurvenL = if (round == 0) VptTables.R1 else VptTables.R2
            val kurvenR = if (round == 0) VptTables.R1R else VptTables.R2R
            java.util.Arrays.fill(akku, 0)
            for (c in 0..3) {
                val slot = (phi - c) and 3
                // L-Fenster: bei slot==3 spillt es in das frische sb1[0]
                slotSrc2048(sb0, slot, sb1)
                gainOp(srcF, kurvenL[c], akku)
                // R-Fenster: bei slot==3 spillt es in sb2 = Nullen
                slotSrc2048(sb1, slot, null)
                gainOp(srcF, kurvenR[c], akku)
            }
            idft1024(akku, y)
            val bank = if (round == 0) bankL else bankR
            val ring = if (round == 0) ringL else ringR
            for (k in 0 until 512) {
                val w = ((y[k].toLong() + bank[k].toLong()) * 0x7fff0000L) shr 17
                ring[k] = if (w > 2147483647L) 2147483647
                          else if (w < -2147483648L) -2147483648
                          else w.toInt()
            }
            for (k in 0 until 512) bank[k] = y[512 + k]
        }
        phi++
    }

    /** 2048 kontiguous ueber die Bankgrenze: Slot p voll + die naechsten
     *  1016 Werte aus bank[p+1]; bei p==3 aus nxt[0] (L) bzw. Nullen (nxt =
     *  null, R: sb2 bleibt immer null da PERM#3/#4 nur Nullen schreiben). */
    private fun slotSrc2048(bank: Array<IntArray>, p: Int, nxt: Array<IntArray>?) {
        val a = bank[p]
        for (j in 0 until 1032) srcF[j] = a[j]
        if (p < 3) {
            val b = bank[p + 1]
            for (j in 0 until 1016) srcF[1032 + j] = b[j]
        } else if (nxt != null) {
            val b = nxt[0]
            for (j in 0 until 1016) srcF[1032 + j] = b[j]
        } else {
            for (j in 0 until 1016) srcF[1032 + j] = 0
        }
    }

    /** GAIN 0x1283dc: Zellformel (frame_iter + Skalarstarts + Epilog).
     *  dst-Zellen sind u32-Muster (Int-Wraparound entspricht der Firmware). */
    private fun gainOp(src: IntArray, g16: IntArray, dst: IntArray) {
        dst[0] += ((src[0].toLong() * g16[1].toLong()) shr 16).toInt()
        dst[1024] += ((src[1024].toLong() * g16[0].toLong()) shr 16).toInt()
        for (i in 0 until 127) {
            val base = 2 + 8 * i
            val goff = 2 + 8 * i
            val a0 = src[base].toLong(); val b0 = src[base + 1].toLong()
            val a1 = src[base + 2].toLong(); val b1 = src[base + 3].toLong()
            val a2 = src[base + 4].toLong(); val b2 = src[base + 5].toLong()
            val a3 = src[base + 6].toLong(); val b3 = src[base + 7].toLong()
            val g00 = g16[goff].toLong(); val g10 = g16[goff + 1].toLong()
            val g01 = g16[goff + 2].toLong(); val g11 = g16[goff + 3].toLong()
            val g02 = g16[goff + 4].toLong(); val g12 = g16[goff + 5].toLong()
            val g03 = g16[goff + 6].toLong(); val g13 = g16[goff + 7].toLong()
            val pG0a0 = a0 * g00
            val pG0a2 = a2 * g02
            val artefV0 = (pG0a0 ushr 32).toInt().toLong() * g01
            val artefV7 = (pG0a2 ushr 32).toInt().toLong() * g03
            val pG1b0 = b0 * g10
            val pG1b2 = b2 * g12
            val pG1b3 = b3 * g13
            val artefV4 = (pG1b0 ushr 32).toInt().toLong() * b1
            val pG1a0 = g10 * a0
            val pG1a1 = g11 * a1
            val pG1a2 = g12 * a2
            val pG1a3 = g13 * a3
            val pG0b0 = g00 * b0
            val pG0b1 = g01 * b1
            val pG0b2 = g02 * b2
            val pG0b3 = g03 * b3
            var v0c0 = pG0a0 ushr 16
            var v0c1 = artefV0 ushr 16
            var v2c0 = pG1b2 ushr 16
            var v2c1 = pG1b3 ushr 16
            var v3c0 = pG1a2 ushr 16
            var v3c1 = pG1a3 ushr 16
            val v4c0 = pG0a2 ushr 16
            val v4c1 = artefV7 ushr 16
            val eOld0 = dst[base].toLong() and 0xffffffffL
            val eOld1 = dst[base + 2].toLong() and 0xffffffffL
            val eOld2 = dst[base + 4].toLong()
            val eOld3 = dst[base + 6].toLong()
            val oOld0 = dst[base + 1].toLong() and 0xffffffffL
            val oOld1 = dst[base + 3].toLong() and 0xffffffffL
            val oOld2 = dst[base + 5].toLong()
            val oOld3 = dst[base + 7].toLong()
            val v190 = eOld0 + (pG1a0 ushr 48) + (pG0b0 ushr 48)
            val v191 = eOld1 + (pG1a1 ushr 48) + (pG0b1 ushr 48)
            var v1c0 = oOld0 + (pG1b0 ushr 48)
            var v1c1 = oOld1 + (artefV4 ushr 48)
            v3c0 += (pG0b2 ushr 48) + eOld2
            v3c1 += (pG0b3 ushr 48) + eOld3
            v2c0 += oOld2
            v2c1 += oOld3
            v0c0 = v1c0 - v0c0
            v0c1 = v1c1 - v0c1
            v1c0 = v2c0 - v4c0
            v1c1 = v2c1 - v4c1
            val comp = v190 or (v191 shl 32)
            dst[base] = comp.toInt()      // out_e[0]
            dst[base + 1] = v0c0.toInt()   // out_o[0]
            dst[base + 2] = (comp ushr 32).toInt()  // out_e[1]
            dst[base + 3] = v0c1.toInt()   // out_o[1]
            dst[base + 4] = v3c0.toInt()   // out_e[2]
            dst[base + 5] = v1c0.toInt()   // out_o[2]
            dst[base + 6] = v3c1.toInt()   // out_e[3]
            dst[base + 7] = v1c1.toInt()   // out_o[3]
        }
        for (j in intArrayOf(1018, 1020, 1022)) {
            val a = src[j].toLong()
            val b = src[j + 1].toLong()
            val g0 = g16[j].toLong()
            val g1 = g16[j + 1].toLong()
            dst[j] += ((g1 * a) shr 16).toInt() + ((g0 * b) shr 16).toInt()
            dst[j + 1] += ((g1 * b) shr 16).toInt() - ((g0 * a) shr 16).toInt()
        }
    }

    /** PERM76: reelle 1024-FFT von [roh, 0...]; Layout out[2k]=Re, out[2k+1]=Im,
     *  plus Nyquist-Zelle 1024 und Null-Padding-Zellen. */
    private fun rdft1024(roh: IntArray, out: IntArray) {
        for (k in 0 until 512) { re[k] = roh[k].toDouble(); im[k] = 0.0 }
        for (k in 512 until 1024) { re[k] = 0.0; im[k] = 0.0 }
        fft(inv = false)
        for (k in 0 until 512) {
            out[2 * k] = re[k].roundToLong().toInt()
            out[2 * k + 1] = im[k].roundToLong().toInt()
        }
        out[1024] = re[512].roundToLong().toInt()
        for (j in 1025 until 1032) out[j] = 0
    }

    /** PERMB9: hermitesche iDFT, Skala 1/1024; y[0:512]=front, y[512:1024]=back. */
    private fun idft1024(nr: IntArray, y: IntArray) {
        for (k in 0 until 512) {
            val reK = nr[2 * k]
            val imK = if (k == 0) 0 else nr[2 * k + 1]
            if (k > 0) {
                re[1024 - k] = reK.toDouble()
                im[1024 - k] = -imK.toDouble()
            }
            re[k] = reK.toDouble()
            im[k] = imK.toDouble()
        }
        re[512] = nr[1024].toDouble()
        im[512] = 0.0
        fft(inv = true)
        for (n in 0 until 1024) y[n] = (re[n] / 1024.0).toInt()
    }

    /** iterative radix-2 FFT auf re/im.
     *  inv=false: e^{-2 pi i / len} (forward), inv=true: e^{+...} (inverse,
     *  unskaliert, Perlmutt wie in der Firmware-Analysereferenz Python-Modell). */
    private fun fft(inv: Boolean) {
        val n = 1024
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = (if (inv) 2.0 else -2.0) * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val xr = re[i + k + len / 2]; val xi = im[i + k + len / 2]
                    val vr = xr * cr - xi * ci
                    val vi = xr * ci + xi * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    val nci = cr * wi + ci * wr
                    cr = ncr; ci = nci
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object { const val SCALE = 1.0f / 2147483648.0f }
}
