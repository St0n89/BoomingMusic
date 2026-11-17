// DseeAi.kt - DSEE-AI-NN-Stack, 1:1-Port von ClearAudioFX/src/DSEEAI.jl
// (Phase 2b; Referenz tools/dsee_ref.py, Phasen 1t/1u/1ab).
// Alles Double (f64 wie Julia/Python). 0-basierte Firmware-Indizes.
package com.tarvos.clearaudio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max

object DseeAi {
    const val LOG_GAIN = 10.0    // fmov imm8 0x24 (VFPExpandImm, 1ab)
    const val MS_SCALE = 0.0625 // 1/16 nach der Positions-Summe
    const val FLOOR_DB = 1e-12  // -120 dB

    class ConvL(
        val cout: Int, val d2: Int, val d3: Int,
        val bias: DoubleArray,
        val k: DoubleArray   // flat[oc*d2*d3 + d2c*d3 + d3c] (0-basiert)
    )

    class DseeModel(
        val mean: DoubleArray,
        val scale: DoubleArray,
        val convs: Array<ConvL>,
        val creluAfter: BooleanArray,
        val delay: Int,
        val n: Int,
        val K: DoubleArray,      // n x 24, flach
        val linBias: DoubleArray
    )

    class DseeState {
        val deltaHist = ArrayList<DoubleArray>()                    // letzte 2 feat8
        val convHist = ArrayList<ArrayDeque<DoubleArray>>()         // je Conv: letzte d2 Frames
        val ring = ArrayList<DoubleArray>()                         // ResDelay

        constructor(m: DseeModel) {
            repeat(m.convs.size) { convHist.add(ArrayDeque()) }
        }
    }

    /** modfreqs(level, n) = -|2*level + 2j + 1 - 2n| * pi/32 (Phase 1w). */
    fun modfreqs(level: Int, n: Int): DoubleArray {
        val out = DoubleArray(n)
        for (j in 0 until n) out[j] = -abs(2.0 * level + 2.0 * j + 1.0 - 2.0 * n) * Math.PI / 32.0
        return out
    }

    /** bank84_from_level: 4-Band-Bank im Firmware-Layout (84 Werte).
     *  bank[b] = -2*W[20]; bank[4+4j+b] = -2*W[20-n]*cos(w_b*n), n = j+1. */
    fun bank84FromLevel(level: Int): DoubleArray {
        val mods = modfreqs(level, 4)
        val bank = DoubleArray(84)
        for (b in 0..3) {
            bank[b] = -2.0 * TablesNN.W_TABLE[20]
            for (j in 0..19) {
                val n = j + 1
                bank[4 + 4 * j + b] = -2.0 * TablesNN.W_TABLE[20 - n] * cos(mods[b] * n)
            }
        }
        return bank
    }

    /** features_4band: 4 MS-Features (dB) einer Bank aus 168 Werten.
     *  Positionen t = 20+8i: v = center*x[t] + sum taps[j]*(x[t+1+j]+x[t-1-j]);
     *  feature_b = 10*log10(max(0.0625*sum v^2, 1e-12)). */
    fun features4band(bank: DoubleArray, x: DoubleArray): DoubleArray {
        val out = DoubleArray(4)
        val taps = DoubleArray(20)
        for (b in 0..3) {
            for (j in 0..19) taps[j] = bank[4 + 4 * j + b]
            var ms = 0.0
            for (i in 0..14) {
                val t = 20 + 8 * i
                var v = bank[b] * x[t]
                for (j in 0..19) v += taps[j] * (x[t + 1 + j] + x[t - 1 - j])
                ms += v * v
            }
            ms *= MS_SCALE
            out[b] = LOG_GAIN * log10(max(ms, FLOOR_DB))
        }
        return out
    }

    /** processFrame: Delta [x,dx,dx2] -> Std -> ConvStack (oldest_first,
     *  Null-Pad) -> CReLU pos_first -> ResDelay-Ring -> LinReg -> y = c + gains. */
    fun processFrame(m: DseeModel, st: DseeState, feat8: DoubleArray): DoubleArray {
        val x1 = if (st.deltaHist.isEmpty()) null else st.deltaHist.last()
        val x2 = if (st.deltaHist.size >= 2) st.deltaHist[st.deltaHist.size - 2] else null
        val dx = if (x1 == null) DoubleArray(8)
        else DoubleArray(8) { feat8[it] - x1[it] }
        val dx2 = if (x1 == null || x2 == null) DoubleArray(8)
        else DoubleArray(8) { dx[it] - (x1[it] - x2[it]) }
        st.deltaHist.add(feat8.copyOf())
        if (st.deltaHist.size > 2) st.deltaHist.removeAt(0)
        val d = DoubleArray(24) {
            when { it < 8 -> feat8[it]; it < 16 -> dx[it - 8]; else -> dx2[it - 16] }
        }
        val s = DoubleArray(24) { (d[it] - m.mean[it]) / m.scale[it] }
        // Conv-Stack
        var c = s
        for (i in m.convs.indices) {
            val conv = m.convs[i]
            val h = st.convHist[i]
            h.addLast(c.copyOf())
            if (h.size > conv.d2) h.removeFirst()
            val npad = conv.d2 - h.size   // Null-Pad vorne
            val out = DoubleArray(conv.cout)
            for (oc in 0 until conv.cout) {
                var acc = conv.bias[oc]
                val base = oc * conv.d2 * conv.d3
                for (d2c in 0 until conv.d2) {
                    val fi = d2c - npad
                    val fr = if (fi >= 0) h[fi] else null
                    val kb = base + d2c * conv.d3
                    if (fr != null) {
                        for (cc in 0 until conv.d3) acc += conv.k[kb + cc] * fr[cc]
                    }
                }
                out[oc] = acc
            }
            c = if (m.creluAfter[i]) {
                val r = DoubleArray(2 * conv.cout)
                for (q in 0 until conv.cout) {
                    r[q] = max(out[q], 0.0)
                    r[conv.cout + q] = max(-out[q], 0.0)
                }
                r
            } else out
        }
        // ResDelay auf s (Ring)
        var sdel = s
        if (m.delay > 0) {
            st.ring.add(s.copyOf())
            sdel = if (st.ring.size > m.delay) st.ring.removeAt(0) else DoubleArray(24)
        }
        // LinReg
        val gains = DoubleArray(m.n)
        for (r in 0 until m.n) {
            var acc = m.linBias[r]
            val kb = r * 24
            for (k in 0 until 24) acc += m.K[kb + k] * sdel[k]
            gains[r] = acc
        }
        return DoubleArray(m.n) { c[it] + gains[it] }
    }

    /** DSEEProcessor: 2 Feature-Bänke + NN für einen Sub-Block. */
    class DseeProcessor(
        val model: DseeModel,
        val state: DseeState,
        val bankA: DoubleArray,
        val bankB: DoubleArray
    ) {
        constructor(modelId: Int, modelIndex: Int) : this(
            TablesNN.NN_MODELS[modelId],
            DseeState(TablesNN.NN_MODELS[modelId]),
            bank84FromLevel(modelIndex - 4),
            bank84FromLevel(modelIndex)
        )

        /** processBlock: 168 Werte (40 History + 128 Block) -> (feat8, y). */
        fun processBlock(x168: DoubleArray): Pair<DoubleArray, DoubleArray> {
            val feat8 = features4band(bankA, x168) + features4band(bankB, x168)
            val y = processFrame(model, state, feat8)
            return Pair(feat8, y)
        }
    }
}
