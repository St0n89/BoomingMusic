// Exciter.kt - Sony dsx_v2 Exciter-Energiekette, 1:1-Port von
// ClearAudioFX/src/Exciter.jl (RE Phasen 1al-1an, 1ap, 1ar-1au).
// Alle Adressen audio.primary.icx1293.so (NW-A105 4.04-OTA), f64.
package com.tarvos.clearaudio

import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

object Exciter {
    const val ENV_TINY = 1e-12   // f32 @0x15dcd0 = f32(1e-12)
    const val F32_TINY = 9.999999960041972e-13

    // ------------------------------------------------------------- gdcf
    /** dsx_v2_generate_down_conversion_filter @0x12c44c: 11 adaptive
     *  Downcon-Koeffizienten. f[k] = clamp(f[k]*0.0625, f32(1e-12), 1.0);
     *  gA = min(sqrt(f[i]/f[1])*A, cap); gB via fcsel; gD = fminnm(cap, 0). */
    fun gdcf(K: DoubleArray, feats: DoubleArray, idx: Int, A: Double, cap: Double): DoubleArray {
        require(K.size >= 44) { "gdcf: Kernelbank braucht 44 f32" }
        require(feats.size >= 5) { "gdcf: 5 Features noetig" }
        val f = DoubleArray(5)
        for (k in 0..4) {
            var v = feats[k] * 0.0625
            if (v < 1e-12) v = F32_TINY else if (v > 1.0) v = 1.0
            f[k] = v
        }
        val i = idx    // Julia 1-basiert f[idx+1] -> 0-basiert f[idx]
        val gA = min(sqrt(f[i] / f[0]) * A, cap)
        val gBRaw = sqrt(f[i] / f[1]) * A
        val gB = if (gBRaw > cap) cap else gBRaw       // fcsel
        val gD = if (cap.isNaN()) 0.0 else min(cap, 0.0)  // fminnm(cap, 0.0)
        val out = DoubleArray(11)
        for (j in 0..10) {
            out[j] = gA * K[4 * j] + gB * K[4 * j + 1] + 0.0 +
                    gD * K[4 * j + 2] + gD * K[4 * j + 3]
        }
        return out
    }

    // ----------------------------------------------------- iir_filtering
    /** dsx_v2_IIR_filtering @0x12c974: Pre-Gain + Biquad-Kaskade (DF2,
     *  b0 = 1). Rueckgabe (out, stateNeu) - state wird NICHT mutiert. */
    fun iirFiltering(
        coeffs: DoubleArray, pregain: Double, n2: Int, n1: Int,
        sig: DoubleArray, state: DoubleArray
    ): Pair<DoubleArray, DoubleArray> {
        val N = 64 * n1
        if (N < 1) return Pair(DoubleArray(0), state.copyOf())
        val g = pregain
        val out = DoubleArray(N) { sig[it] * g }
        val st = state.copyOf()
        if (n2 >= 4) {
            val K = n2 shr 2
            for (b in 0 until K) {
                val A = coeffs[4 * b]
                val Bc = coeffs[4 * b + 1]
                val C = coeffs[4 * b + 2]
                val D = coeffs[4 * b + 3]
                var w1 = st[2 * b]
                var w2 = st[2 * b + 1]
                for (i in 0 until N) {
                    val w = out[i] - C * w1 - D * w2
                    out[i] = w + A * w1 + Bc * w2
                    w2 = w1; w1 = w
                }
                st[2 * b] = w1      // w_N
                st[2 * b + 1] = w2  // w_{N-1}
            }
        }
        return Pair(out, st)
    }

    // ---------------------------------------------------------- fourband
    /** dsx_v2_4band_filtering @0x12d9cc: 4 dB aus 21-Tap-Bandkernen,
     *  15 Slots stride 8, KEINE 0.5-Skala, Cascade-Clobber treu. */
    fun fourband(A: DoubleArray, sig: DoubleArray): DoubleArray {
        require(A.size >= 84) { "fourband: A braucht 84 f32 (21x4)" }
        require(sig.size >= 153) { "fourband: sig[0..152] noetig" }
        val E = DoubleArray(4)
        for (i in 0..14) {
            val c = 20 + 8 * i
            val acc = DoubleArray(4) { A[it] * sig[c] }
            for (d in 1..20) {
                val pair = sig[c + d] + sig[c - d]
                for (j in 0..3) acc[j] += A[4 * d + j] * pair
            }
            for (j in 0..3) E[j] += acc[j] * acc[j]
        }
        val slots = DoubleArray(4) { E[it] * 0.0625 }
        val out = DoubleArray(4)
        for (j in 0..3) {
            var v = slots[j]
            if (!(v >= 1e-12)) {                    // f64-Compare, faengt NaN
                slots[j] = F32_TINY
                if (j < 3) slots[j + 1] = 0.0       // Cascade-Clobber
                v = F32_TINY
            }
            out[j] = 10.0 * log10(v)
        }
        return out
    }

    // --------------------------------------------------------------- mdf
    /** dsx_v2_maximally_decimated_filtering @0x12cb20: 5 Sub-Band-Energien. */
    fun mdf(M: DoubleArray, sig: DoubleArray): DoubleArray {
        val S = DoubleArray(5)
        for (i in 0..6) {
            val t = 20 + 8 * i
            val accs = DoubleArray(4)
            for (j in 0..3) {
                var a = sig[t] * M[4 * j]
                for (d in 1..20) a += (sig[t + d] + sig[t - d]) * M[4 * j + d]
                accs[j] = a
            }
            for (j in 0..3) S[j] += accs[j] * accs[j]
            val tot = accs[0] + accs[1] + accs[2] + accs[3]
            S[4] += tot * tot
        }
        for (j in 0..4) S[j] = 0.5 * S[j]
        return S
    }

    // ----------------------------------------------------------- orgHigh
    /** @0x12cc28: 4 hohe Energien. Pos 0: 18 Paare (19 Taps), 1..7: 20. */
    fun orgHigh(K: DoubleArray, sig: DoubleArray): DoubleArray {
        require(K.size >= 84) { "org_high: Kernelbank braucht 84 f32" }
        require(sig.size >= 97) { "org_high: sig[0..96] noetig" }
        val S = DoubleArray(4)
        for (i in 0..7) {
            val t = 20 + 8 * i
            val D = if (i == 0) 18 else 20
            for (j in 0..3) {
                var a = sig[t] * K[j]
                for (d in 1..D) a += (sig[t + d] + sig[t - d]) * K[4 * d + j]
                S[j] += a * a
            }
        }
        for (j in 0..3) S[j] = 0.5 * S[j]
        return S
    }

    // ------------------------------------------------------------ mdf_hr
    /** @0x12b3bc: 5 HR-Bandenergien, KEINE 0.5-Skala. K 44, sig 77. */
    fun mdfHr(K: DoubleArray, sig: DoubleArray): DoubleArray {
        require(K.size >= 44) { "mdf_hr: Kernelbank braucht 44 f32" }
        require(sig.size >= 77) { "mdf_hr: sig[0..76] noetig" }
        val S = DoubleArray(5)
        for (k in 0..7) {
            val t = 10 + 8 * k
            val accs = DoubleArray(4)
            for (j in 0..3) {
                var a = sig[t] * K[j]
                for (d in 1..10) a += (sig[t + d] + sig[t - d]) * K[4 * d + j]
                accs[j] = a
            }
            for (j in 0..3) S[j] += accs[j] * accs[j]
            val tot = accs[0] + accs[1] + accs[2] + accs[3]
            S[4] += tot * tot
        }
        return S
    }

    // ------------------------------------------------------------ env_dB
    /** dB = 10*log10(clamp(16*E, f32(1e-12), 16.0)). */
    fun envDBe(E: Double): Double {
        var v = 16.0 * E
        if (v < ENV_TINY) v = F32_TINY
        else if (v > 16.0) v = 16.0
        return 10.0 * log10(v)
    }

    // ---------------------------------------------------------- envelope
    /** dsx_v2_envelope_extrapolation @0x12a100: 9 Energien -> dB -> Targets
     *  -> Slew -> Clamps -> Gains -> (coefs 42, gains 8). */
    fun envelopeExtrapolation(
        E: DoubleArray, prev: DoubleArray, N: Int, M: Int,
        bandidx: IntArray, A: DoubleArray, w3: Int,
        pairs1: DoubleArray, rows4: DoubleArray,
        slewOn: Boolean = false, cUp: Double = 0.0, cDn: Double = 0.0,
        clampMode: Int = 0, ceil: Double = 0.0,
        flEn: Int = 0, flOff: Double = 0.0,
        off1: Double = 0.0, off2: Double = 0.0,
        gmax: Double = Double.POSITIVE_INFINITY
    ): Pair<DoubleArray, DoubleArray> {
        require(N in 0..8) { "envelope: N ausserhalb 0..8" }
        require(E.size == 9) { "envelope: E braucht 9 Werte" }
        require(prev.size == 8) { "envelope: prev braucht 8 Werte" }
        require(A.size == 84) { "envelope: A braucht 84 f32" }
        require(pairs1.size == 16) { "envelope: pairs1 braucht 16 f32" }
        require(rows4.size == 40) { "envelope: rows4 braucht 40 f32" }

        // Phase A: 5 niedrige, dann 4 hohe dB
        val dB = DoubleArray(9) { envDBe(E[it]) }

        // Phase B: 8 Targets
        val tgt = DoubleArray(8)
        if (w3 != 0) {                       // 1-Band: dB[4] = bw+0x1f0
            for (i in 0..7) tgt[i] = pairs1[2 * i] + pairs1[2 * i + 1] * dB[4]
        } else {                             // 4-Band: dB[0..3]
            for (i in 0..7) {
                var t = rows4[5 * i]
                for (k in 0..3) t += rows4[5 * i + 1 + k] * dB[k]
                tgt[i] = t
            }
        }

        // Phase C: Slew
        if (slewOn) {
            for (i in 0..7) {
                val delta = prev[i] - tgt[i]
                tgt[i] = when {
                    delta > cUp -> tgt[i] + cUp
                    delta < cDn -> tgt[i] + cDn
                    else -> prev[i]           // Fensterfall: base
                }
            }
        }

        // Phase D: Clamps (Floors nur Targets 0..3 = dB_high + fl_off)
        val ceiling = -2.0 - ceil
        val floors = DoubleArray(4) { dB[5 + it] + flOff }
        val offset = off1 + off2
        if (clampMode == 1) {                 // Offset VOR Ceil/Floor
            for (i in 0..7) {
                var t = tgt[i] + offset
                if (t > ceiling) t = ceiling
                if (flEn != 0 && i < 4 && t < floors[i]) t = floors[i]
                tgt[i] = t
            }
        } else {
            for (i in 0..7) {
                var t = tgt[i]
                if (t > ceiling) t = ceiling
                if (flEn != 0 && i < 4 && t < floors[i]) t = floors[i]
                tgt[i] = t
            }
            if (clampMode == 0) for (i in 0..7) tgt[i] += offset
        }

        // Phase E: Gains (Flat-Index; hohe Baender 40..43)
        val dBFlat = DoubleArray(44)
        for (i in 0..4) dBFlat[i] = dB[i]
        for (i in 0..3) dBFlat[40 + i] = dB[5 + i]
        val gains = DoubleArray(8)
        for (i in 0 until N) {
            val ref = dBFlat[bandidx[i]]
            gains[i] = min(Math.pow(10.0, (tgt[i] - ref) / 20.0), gmax)
        }

        // Phase F: Mix (M > 0; M <= 0 = toter WSP-Pfad)
        require(M > 0) { "envelope: M <= 0 = WSP-Muell-Variante, toter Pfad" }
        var R = 0.0
        for (k in M until N) R += A[k - M] * gains[k]
        val coefs = DoubleArray(42)
        for (j in 0..20) {
            var L = 0.0
            for (k in 0 until M) L += A[4 * j + k] * gains[k]
            coefs[2 * j] = L
            coefs[2 * j + 1] = R
        }
        return Pair(coefs, gains)
    }

    // ---------------------------------------------------------- fir_2band
    /** @0x12d094: 2-Band-FIR (gerade/ungerade = Band 0/1), nblocks =
     *  floor(83/n2)+1; g0/g1 per Position. */
    fun fir2band(
        coefs: DoubleArray, g0: DoubleArray, g1: DoubleArray,
        inp: DoubleArray, n2: Int
    ): DoubleArray {
        require(n2 > 0) { "fir_2band: n2 <= 0 (toter Firmware-Stub-Pfad)" }
        val out = ArrayList<Double>()
        var x15 = 20
        while (true) {
            for (j in 0 until n2) {
                val t = x15 + j
                var b0 = inp[t] * coefs[0]
                var b1 = inp[t] * coefs[1]
                for (d in 1..20) {
                    val p = inp[t + d] + inp[t - d]
                    b0 += p * coefs[2 * d]
                    b1 += p * coefs[2 * d + 1]
                }
                out.add(g0[j] * b0 + g1[j] * b1)
            }
            x15 += n2
            if (x15 > 83) break
        }
        return out.toDoubleArray()
    }

    // ------------------------------------------------------------- downcon
    /** @0x12c898: 11-Tap-Dezimator. t = 10+m*w1, Loop-Check NACH Store. */
    fun downcon(coefs: DoubleArray, x: DoubleArray, w1: Int): DoubleArray {
        val out = ArrayList<Double>()
        var m = 0
        while (true) {
            val t = 10 + m * w1
            var acc = x[t] * coefs[0]
            for (d in 1..10) acc += (x[t + d] + x[t - d]) * coefs[d]
            out.add(acc)
            m += 1
            if (t + w1 > 73) break
        }
        return out.toDoubleArray()
    }

    // ------------------------------------------------------------- pow_calc
    /** @0x12c82c: sum_{k=0..7} p[8k]^2 (Stride 8). */
    fun powCalc(p: DoubleArray): Double {
        var s = 0.0
        for (k in 0..7) {
            val v = p[8 * k]
            s += v * v
        }
        return s
    }
}
