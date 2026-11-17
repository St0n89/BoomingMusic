// Upcon.kt - Sony dsx_v2_FIR_filtering_upcon + generate_up_conversion_filter,
// 1:1-Port von ClearAudioFX/src/Upcon.jl (RE Phasen 1ah/1ai/1aj).
// Alle Adressen audio.primary.icx1293.so, f64, 0-basierte Firmware-Indizes.
package com.tarvos.clearaudio

import kotlin.math.log10

object Upcon {
    const val GUCF_DBOFF = 120.0   // .rodata @0x15dcd4

    // ------------------------------------------------------- upcon_direct
    /** Symmetrischer Kern h[d] = c[|d|], out[F*(t-k)+p] =
     *  blend*w4*out_prev + F*sum_u inp[t-u]*h[F*u+p] (upcon_ref.py). */
    fun upconDirect(
        coefs: DoubleArray, k: Int, F: Int, inp: DoubleArray,
        outPrev: DoubleArray, w4f: Double, nPos: Int, blend: Boolean = true
    ): DoubleArray {
        val nOut = F * nPos
        require(outPrev.size >= nOut) { "upcon_direct: out_prev zu kurz" }
        val out = DoubleArray(nOut) { outPrev[it] }
        for (j in 0 until nPos) {
            val t = k + j
            for (p in 0 until F) {
                var a = 0.0
                for (u in -k..k) {
                    val d = F * u + p
                    if (kotlin.math.abs(d) <= F * k) {
                        a += inp[t - u] * coefs[kotlin.math.abs(d)]
                    }
                }
                out[F * j + p] = if (blend) outPrev[F * j + p] * w4f + F * a else F * a
            }
        }
        return out
    }

    // ---------------------------------------------------------- F=8 main
    /** Transkription 0x12a61c-0x12a8f4 (2<=k<=17). 16 Pos, 128 Ausgaenge. */
    fun upcon8Main(coefs: DoubleArray, k: Int, inp: DoubleArray,
                   outPrev: DoubleArray, w4f: Double): DoubleArray {
        val F = 8
        val nOut = F * 16
        fun c(n: Int) = coefs[n]
        fun x(n: Int) = if (n in inp.indices) inp[n] else 0.0
        fun op(n: Int) = if (n in outPrev.indices) outPrev[n] else 0.0
        val out = DoubleArray(nOut)
        for (j in 0..15) {
            val t = k + j
            val in0 = x(t); val in1 = x(t + 1)
            val acc = doubleArrayOf(
                in0 * c(0) + in1 * c(8), in0 * c(1) + in1 * c(7),
                in0 * c(2) + in1 * c(6), in0 * c(3) + in1 * c(5),
                in0 * c(4) + in1 * c(4), in0 * c(5) + in1 * c(3),
                in0 * c(6) + in1 * c(2), in0 * c(7) + in1 * c(1)
            )
            var s7 = c(8)
            for (i in 1 until k) {
                val s17 = x(t - i)
                val s20 = x(t + 1 + i)
                val base = F * i + 1
                acc[0] += s17 * s7
                acc[1] += s17 * c(base)
                acc[2] += s17 * c(base + 1)
                acc[3] += s17 * c(base + 2)
                acc[4] += s17 * c(base + 3)
                acc[5] += s17 * c(base + 4)
                acc[6] += s17 * c(base + 5)
                acc[7] += s17 * c(base + 6)
                acc[1] += s20 * c(base + 6)
                acc[7] += s20 * c(base)
                acc[6] += s20 * c(base + 1)
                acc[5] += s20 * c(base + 2)
                acc[4] += s20 * c(base + 3)
                acc[3] += s20 * c(base + 4)
                acc[2] += s20 * c(base + 5)
                acc[0] += s20 * c(base + 7)
                s7 = c(base + 7)
            }
            acc[0] += x(t - k) * c(F * k)   // Epilog-Tieftap
            for (p in 0 until F) out[F * j + p] = op(F * j + p) * w4f + F * acc[p]
        }
        return out
    }

    // ---------------------------------------------------------- F=8 edge
    /** 0x12a8fc-0x12aa88 (k<=1, Praxis k=1: Kernel c[0..8]). */
    fun upcon8Edge(coefs: DoubleArray, inp: DoubleArray,
                   outPrev: DoubleArray, w4f: Double): DoubleArray {
        val F = 8
        val k = 1
        val nOut = F * 16
        fun c(n: Int) = coefs[n]
        fun x(n: Int) = if (n in inp.indices) inp[n] else 0.0
        fun op(n: Int) = if (n in outPrev.indices) outPrev[n] else 0.0
        val out = DoubleArray(nOut)
        for (j in 0..15) {
            val t = k + j
            val in0 = x(t); val in1 = x(t + 1)
            val acc = doubleArrayOf(
                in0 * c(0) + in1 * c(8) + x(t - 1) * c(8),
                in0 * c(1) + in1 * c(7), in0 * c(2) + in1 * c(6),
                in0 * c(3) + in1 * c(5), in0 * c(4) + in1 * c(4),
                in0 * c(5) + in1 * c(3), in0 * c(6) + in1 * c(2),
                in0 * c(7) + in1 * c(1)
            )
            for (p in 0 until F) out[F * j + p] = op(F * j + p) * w4f + F * acc[p]
        }
        return out
    }

    // ---------------------------------------------------------------- F=2
    /** 0x12a2b0-0x12a354 (1<=k<=65): 64 Pos, w4 ignoriert (kein Blend). */
    fun upcon2(coefs: DoubleArray, k: Int, inp: DoubleArray): DoubleArray {
        val F = 2
        fun c(n: Int) = coefs[n]
        fun x(n: Int) = if (n in inp.indices) inp[n] else 0.0
        val out = DoubleArray(F * 64)
        for (j in 0..63) {
            val t = k + j
            var even = x(t) * c(0)
            var odd = 0.0
            for (i in 1..k) {
                even += (x(t + i) + x(t - i)) * c(2 * i)
                odd += (x(t + i) + x(t - i + 1)) * c(2 * i - 1)
            }
            out[F * j] = 2.0 * even
            out[F * j + 1] = 2.0 * odd
        }
        return out
    }

    // ---------------------------------------------------------------- F=4
    /** 0x12a360-0x12a444 (1<=k<=65): 64 Pos, w4 ignoriert, Skala 4.0. */
    fun upcon4(coefs: DoubleArray, k: Int, inp: DoubleArray): DoubleArray {
        val F = 4
        fun c(n: Int) = coefs[n]
        fun x(n: Int) = if (n in inp.indices) inp[n] else 0.0
        val out = DoubleArray(F * 64)
        for (j in 0..63) {
            val t = k + j
            val a = doubleArrayOf(x(t) * c(0), 0.0, 0.0, 0.0)
            for (i in 1..k) {
                a[0] += (x(t + i) + x(t - i)) * c(4 * i)
                a[1] += x(t + i) * c(4 * i - 1) + x(t - i + 1) * c(4 * i - 3)
                a[2] += (x(t + i) + x(t - i + 1)) * c(4 * i - 2)
                a[3] += x(t - i + 1) * c(4 * i - 1) + x(t + i) * c(4 * i - 3)
            }
            for (q in 0 until F) out[F * j + q] = 4.0 * a[q]
        }
        return out
    }

    // --------------------------------------------------------------- F=16
    /** 0x12aa94 (2<=k<=17): 16 Pos, blend = w4, Skala 16.0. */
    fun upcon16(coefs: DoubleArray, k: Int, inp: DoubleArray,
                outPrev: DoubleArray, w4f: Double): DoubleArray {
        val F = 16
        val nOut = F * 16
        fun c(n: Int) = coefs[n]
        fun x(n: Int) = if (n in inp.indices) inp[n] else 0.0
        fun op(n: Int) = if (n in outPrev.indices) outPrev[n] else 0.0
        val out = DoubleArray(nOut)
        for (j in 0..15) {
            val t = k + j
            val in0 = x(t); val in1 = x(t + 1)
            val acc = DoubleArray(F) { p -> in0 * c(p) + in1 * c(F - p) }
            for (i in 1 until k) {
                val s17 = x(t - i)
                val s20 = x(t + 1 + i)
                for (p in 0 until F) {
                    acc[p] += s17 * c(F * i + p)
                    acc[p] += s20 * c(F * i + F - p)
                }
            }
            acc[0] += x(t - k) * c(F * k)   // Epilog-Tieftap
            for (p in 0 until F) out[F * j + p] = op(F * j + p) * w4f + F * acc[p]
        }
        return out
    }

    // ------------------------------------------------------------- Dispatch
    /** @0x12a2a4: F 2/4 ohne Blend, 8/16 mit; F=8 k>17 No-Op, k<=1 Edge. */
    fun upconDispatch(coefs: DoubleArray, k: Int, F: Int, inp: DoubleArray,
                      outPrev: DoubleArray, w4f: Double): DoubleArray {
        return when (F) {
            2 -> upcon2(coefs, k, inp)
            4 -> upcon4(coefs, k, inp)
            8 -> when {
                k > 17 -> outPrev.copyOf()
                k <= 1 -> upcon8Edge(coefs, inp, outPrev, w4f)
                else -> upcon8Main(coefs, k, inp, outPrev, w4f)
            }
            16 -> if (k > 17) outPrev.copyOf() else upcon16(coefs, k, inp, outPrev, w4f)
            else -> outPrev.copyOf()
        }
    }

    // ----------------------------------------------------------------- GUCF
    /** 4 Gain-Bloecke 0x12b818-0x12b9c0. dB_k = 10*log10(feats[idx-2+k])+120;
     *  target_i = ci[0] + ci[1]*dB0 + ci[2]*dB1 + ci[3]*dB2; Slew falls Flag;
     *  gain_i = 10^((t_i-dB2)/20). */
    fun gucfGains(
        feats: DoubleArray, idx: Int, C: Array<DoubleArray>,
        slewOn: Boolean = false,
        prev: DoubleArray = DoubleArray(4),
        cUp: Double = 10.0, cDn: Double = -120.0,
        dboff: Double = GUCF_DBOFF
    ): DoubleArray {
        val dB = DoubleArray(3) { k ->
            10.0 * log10(feats[idx - 2 + k]) + dboff
        }
        val gains = DoubleArray(4)
        for (i in 0..3) {
            val ci = C[i]
            var target = ci[0] + ci[1] * dB[0] + ci[2] * dB[1] + ci[3] * dB[2]
            if (slewOn) {
                val base = prev[i] + dboff
                val delta = base - target
                target = when {
                    delta > cUp -> target + cUp
                    delta < cDn -> target + cDn
                    else -> base
                }
            }
            gains[i] = Math.pow(10.0, (target - dB[2]) / 20.0)
        }
        return gains
    }

    /** P-Mix 0x12b9c4-0x12bb80: mode==2 -> P[0] roh (Gains tot), sonst
     *  acc[j] = sum_i gains[i]*P[i][j]. */
    fun gucfWindow(P: Array<DoubleArray>, gains: DoubleArray, mode: Int): DoubleArray {
        if (mode == 2) return P[0].copyOf()
        val acc = DoubleArray(25)
        for (i in 0..3) {
            val g = gains[i]
            val Pi = P[i]
            for (j in 0..24) acc[j] += g * Pi[j]
        }
        return acc
    }

    /** Konstruktion 0x12bd84-0x12c40c: out[i] = acc[0]*src[24+i] +
     *  sum_{m=1..24} acc[m]*(src[24+i+m]+src[24+i-m]), i = 0..n+24.
     *  n < 24 -> leer. */
    fun gucfConstruct(acc: DoubleArray, src: DoubleArray, n: Int): DoubleArray {
        if (n < 24) return DoubleArray(0)
        val out = DoubleArray(n + 25)
        for (i in 0..n + 24) {
            // Julia 1-basiert src[25+i] = 0-basiert src[24+i] (Zentrum)
            var s = acc[0] * src[24 + i]
            for (m in 1..24) s += acc[m] * (src[24 + i + m] + src[24 + i - m])
            out[i] = s
        }
        return out
    }

    /** Kompletter dsx_v2_generate_up_conversion_filter-Aufruf. */
    fun gucf(
        feats: DoubleArray, idx: Int, P: Array<DoubleArray>, src: DoubleArray,
        n: Int, C: Array<DoubleArray>, mode: Int,
        slewOn: Boolean = false, prev: DoubleArray = DoubleArray(4),
        cUp: Double = 10.0, cDn: Double = -120.0
    ): DoubleArray {
        val gains = gucfGains(feats, idx, C, slewOn, prev, cUp, cDn)
        val acc = gucfWindow(P, gains, mode)
        return gucfConstruct(acc, src, n)
    }
}
