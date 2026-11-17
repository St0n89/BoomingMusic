// DsxSim.kt - DSEE-AI-Gesamtsimulator, 1:1-Port von ClearAudioFX/src/DsxSim.jl
// (Stand 1bf/1bg; dsx_execute_func @0x1178a4, high_pow @0x116bd8,
// dsx_process @0x114b2c). Realwerte ICX1293, 48 kHz, mode2.
// TODOs aus der Referenz als no-op: (1) Analyse-IIRs Passthrough,
// (2) ine_proc verworfen, (5) LCG-Bitextraktion Top-16 des High-32.
package com.tarvos.clearaudio

/** Alle Steuergroessen des Handles h. Defaults = ICX1293-Realwerte. */
class DsxConfig(
    val ch: Int = 2,
    val sr: Int = 48000,
    val d: Int = 1,
    val frame: Int = 256,
    val b: Int = 12,
    val anaGate: Int = 1,
    val synGate: Int = 1,
    val idx: Double = 0.0,
    val gdcfA: Double = Tables.GDCF_A,
    val gdcfCap: Double = Tables.GDCF_CAP,
    val noiseScale: Double = Tables.NOISE_SCALE,
    val procMode: Int = 0,
    val mode: Int = 2,
    val gucfMode: Int = 0,
    val x23Gate: Int = 0,
    val x23Add: Double = -20.0,
    val bypass: Int = 0,
    val modelIndex: Int = 12,
    val modelId: Int = 4,
    val nn2Id: Int = 8,
    val nn2Index: Int = 16,
    val lcgSeed: ULong = 0x1234u,
    val hrBank: DoubleArray = Tables.DSX_HR_BANK,
    val hrIirCoefs: DoubleArray = Tables.DSX_HR_IIR_CO,
    val hrPregain: Double = Tables.DSX_HR_PREGAIN
)

/** Bandwork bw je Kanal (audio.primary-Offsets aus 197/198). */
class ChanWork {
    var sig2d4 = DoubleArray(64)      // bw+0x2d4 dezimiertes Signal
    var sig3d4 = DoubleArray(104)     // bw+0x3d4 (20 hist + 64 + 20)
    var sig574 = DoubleArray(97)      // bw+0x574 orgHigh-Signal
    var sigd0 = DoubleArray(64)       // bw+0xd0 IIR(h+0x118)-Ausgang
    var chain = DoubleArray(40)       // bw+0x1e0..0x280 8x5-Energiekette
    var accuPrev = DoubleArray(9)     // bw+0x290.. 2-Block-Summen-Paare
    val envPrev = DoubleArray(8)      // bw+0x13c0 (8-Gain-Slots)
    val gucfPrev = DoubleArray(4)     // bw+0x13e0 (3-Band-Gain-Slots)
    var gucfKern = DoubleArray(113)   // bw+0x1338/0x1170 Band-1-Kern
    var hrIirState = DoubleArray(8)
    var noise = DoubleArray(128)      // bw+0x1110-Schieberegister
    var bandHist = DoubleArray(256)   // bw+0x714 Band-History
    val outHist = DoubleArray(128)    // bw+0xd84 HR-Kette
    val e84 = DoubleArray(80)         // bw+0xe84
    val slots1068 = DoubleArray(8)    // bw+0x1068 mdf_hr-Akkus
    var up1Prev = DoubleArray(0)      // Site1-Ausgabe (Blend-Zustand)
    var up2Prev = DoubleArray(0)      // Sites-2/3-Ausgabe
    var slots1090 = DoubleArray(16)   // bw+0x1090 (downcon, dezimiert)
    val pads = HashMap<String, DoubleArray>()
}

/** AI-Workspace C je Kanal: off1 = 21.0721 (16B-Struct @0xbcc2),
 *  off2 = -9.0, off3 = 6.0 (20B-Struct @0xd329; Clamps -120, Slew 10). */
class CWork {
    var buf = DoubleArray(0)
    var win1 = DoubleArray(0)
    var dB1: Array<DoubleArray>? = null
    var dB2 = Array<DoubleArray>(0) { DoubleArray(0) }
    var nn1Ys: Array<DoubleArray?> = Array(0) { null }
    val g8 = HashMap<Int, DoubleArray>()
    val g3 = HashMap<Int, DoubleArray>()
    val off1 = 21.072099685668945
    val off2 = -9.0
    val off3 = 6.0
}

/** execute_func + high_pow + dsx_process mit Zustand pro Kanal. */
class DsxSimulator(val cfg: DsxConfig = DsxConfig()) {
    val nn1 = DseeAi.DseeProcessor(cfg.modelId, cfg.modelIndex)
    val nn2 = DseeAi.DseeProcessor(cfg.nn2Id, cfg.nn2Index)
    val works = Array(cfg.ch) { ChanWork() }
    val C = Array(cfg.ch) { CWork() }
    var pingpong = 1
    var frames = 0
    val rows4 = Tables.ROWS4          // base_tbl_8x5 geflacht (40)
    val pairs1 = Tables.PAIRS1        // tbl_b_8x2 geflacht (16)
    val w3 = 0                        // [h+0x8d4]: !=0 -> 1-Band
    val k1 = Tables.K1                 // ine state+0x4ec (HF-EMA)
    val k2 = Tables.K2                 // ine state+0x4f4 (Energie-EMA)
    val ibmapN: Int                    // dsx_v2_set_ibmap (1bc)
    val ibmapM = 4
    val ibmapBandidx: IntArray
    val firG0 = Tables.FIR_G0          // 2*cos(i*pi/4) @0x162f64
    val firG1 = Tables.FIR_G1          // 2*cos(i*pi/2)
    val bank1a: DoubleArray
    val bank1b: DoubleArray
    val bank2a: DoubleArray
    val bank2b: DoubleArray
    val orgbank: DoubleArray           // orgHigh h+0x358 (Level 16)
    val featIdx = 3                   // [h+0x122c] bei 44.1/48 kHz
    val gucfC: Array<DoubleArray>      // [h+0x11ec..0x1228], Tabelle 'b'
    var lcgState: ULong = 0x0u

    init {
        ibmapN = maxOf(0, minOf(16 - cfg.modelIndex, 8))
        ibmapBandidx = intArrayOf(0, 1, 2, 3).copyOfRange(0, ibmapN)
        bank1a = DseeAi.bank84FromLevel(cfg.modelIndex - 4)
        bank1b = DseeAi.bank84FromLevel(cfg.modelIndex)
        bank2a = DseeAi.bank84FromLevel(cfg.nn2Index)
        bank2b = DseeAi.bank84FromLevel(cfg.nn2Index + 4)
        orgbank = DseeAi.bank84FromLevel(16)
        gucfC = Tables.GUCF_C.copyOf()
        lcgState = cfg.lcgSeed
    }

    /** in_ch: je Kanal frame N Werte. Rueckgabe out_ch. */
    fun executeFrame(inCh: Array<DoubleArray>): Array<DoubleArray> {
        val c = cfg
        val n = c.frame
        require(inCh.all { it.size == n }) { "execute_frame: frame length" }
        if (c.bypass != 0 || (c.anaGate == 0 && c.synGate == 0)) {
            return Array(inCh.size) { inCh[it].copyOf() }   // Bypass 192-(2)/(3)
        }

        // (1) int-Staging: hist + frame
        for (i in inCh.indices) {
            val cw = C[i]
            val keep = maxOf(1, cw.buf.size - c.d * n + 1)
            cw.buf = cw.buf.copyOfRange(keep - 1, cw.buf.size) + inCh[i].copyOf()
        }

        // (2) Analyse-Input (Dezimation D)
        val ana = Array(inCh.size) { i ->
            if (c.d > 1) {
                val x = inCh[i]
                DoubleArray((x.size + c.d - 1) / c.d) { x[it * c.d] }
            } else inCh[i].copyOf()
        }

        // (0) AI-Frame-Paritaet [x24+0x400]: AI nur auf jedem 2. Frame
        val take = pingpong != 1
        pingpong = if (take) 1 else 0

        // (3)+(4) Stage 1: 4band -> 8 dB je Kanal/Sub-Block
        val sub = maxOf(1, n / 128)
        if (take) {
            for (cw in C) {
                cw.dB1 = Array(sub) { DoubleArray(8) { -120.0 } }
                cw.nn1Ys = arrayOfNulls(sub)
            }
            for (ci in C.indices) {
                val cw = C[ci]
                val ch = ana[ci]
                for (w21 in 0 until sub) {
                    var hist = cw.win1.copyOfRange(
                        maxOf(0, cw.win1.size - 40), cw.win1.size
                    )
                    if (hist.size < 40) {
                        hist = DoubleArray(40 - hist.size) + hist
                    }
                    val x = hist + ch.copyOfRange(w21 * 128, w21 * 128 + 128)
                    cw.win1 = x.copyOfRange(x.size - 40, x.size)
                    val (f8, y) = nn1.processBlock(x)
                    cw.dB1!![w21] = DoubleArray(8) { f8[it] + c.idx }  // += idx
                    cw.nn1Ys!![w21] = y
                }
            }

            // (5) Dispatcher A/B @0x117dac: y + off1 - idx, Fallback
            for (cw in C) {
                for (w21 in 0 until sub) {
                    val y = cw.nn1Ys!![w21]
                    val ny = y?.size ?: 0
                    cw.g8[w21] = DoubleArray(8) { i ->
                        if (i < ny) y!![i] + cw.off1 - c.idx
                        else cw.off1 - 120.0 - c.idx
                    }
                }
            }

            // (6) dB_feat2 @0x118044
            val nn = maxOf(0, minOf(16 - c.modelIndex, 8))
            for (cw in C) {
                cw.dB2 = Array(sub) { DoubleArray(0) }
                for (w21 in 0 until sub) {
                    val feats = cw.dB1!![w21]
                    val dbf = ArrayList<Double>()
                    for (j in 1..c.modelId) dbf.add(feats[nn + j - 1])
                    val y = cw.nn1Ys!![w21]
                    val ny = y?.size ?: 0
                    for (j in 1..minOf(nn, ny)) dbf.add(y!![j - 1] + cw.off3)
                    while (dbf.size < 8) dbf.add(cw.off3 - 120.0)
                    cw.dB2[w21] = dbf.toDoubleArray()
                }
            }

            // (7) NN2 = Modell 8 auf dB_feat2; Dispatcher #2 @0x1183bc
            for (cw in C) {
                for (w21 in 0 until sub) {
                    val y2 = DseeAi.processFrame(nn2.model, nn2.state, cw.dB2[w21])
                    val n3 = minOf(3, y2.size)
                    cw.g3[w21] = DoubleArray(n3) { j ->
                        maxOf(y2[j], -120.0) + cw.off2 - c.idx
                    }
                }
            }
        }

        frames += 1
        // (8) high_pow
        return highPow(inCh)
    }

    // -------------------------------------------------------------- high_pow
    private fun highPow(inCh: Array<DoubleArray>): Array<DoubleArray> {
        val n = inCh[0].size
        val out = Array(inCh.size) { inCh[it].copyOf() }
        val nblk = maxOf(1, n / 64)
        for (s in 0 until nblk) {
            for (i in out.indices) {
                val blk = out[i].copyOfRange(s * 64, s * 64 + 64)
                val res = dsxProcess(blk, i, s)
                System.arraycopy(res, 0, out[i], s * 64, 64)
            }
        }
        return out
    }

    // ------------------------------------------------------------ dsx_process
    /** x: 64 Werte des Kanals; Rueckgabe: 64 Effekt-Output (197/198). */
    private fun dsxProcess(x: DoubleArray, chI: Int, sub: Int): DoubleArray {
        val c = cfg
        val w = works[chI]
        val cw = C[chI]

        if (c.procMode == 2 || c.procMode == 1) {
            w.sig2d4 = x.copyOf()       // TODO(1): IIR h+0x130 in-place
            return synthesis(w, cw, sub)
        }

        // Prolog (a): Dezimation; History 40/64
        // Julia: sig3d4 = vcat(sig3d4[65:end], zeros(64)) -> letzte 40 behalten
        val shifted = DoubleArray(104)
        System.arraycopy(w.sig3d4, 64, shifted, 0, 40)   // sig3d4[65:end] 1-basiert
        w.sig3d4 = shifted
        for (k in 1..64) w.sig3d4[39 + k] = x[(k - 1) * c.d]
        w.sig2d4 = x.copyOf()

        // TODO(1): IIR(h+0x130/0x118/0x148) hier Passthrough
        w.sigd0 = w.sig2d4.copyOf()
        val s574 = DoubleArray(104)
        System.arraycopy(w.sig574, maxOf(0, w.sig574.size - 40), s574, 0,
            minOf(40, w.sig574.size))
        System.arraycopy(w.sigd0, 0, s574, 40, 64)
        w.sig574 = s574
        // ine_proc-Ergebnis wird verworfen (kein State-Effekt) -> no-op.

        // mdf-Schiebekette: 8x5, dann mdf schreibt neuen Kopf
        val newChain = DoubleArray(40)
        System.arraycopy(w.chain, 0, newChain, 5, 35)   // chain[1:35]
        w.chain = newChain
        val mdfE = Exciter.mdf(bank1b, w.sig3d4)
        for (j in 0..4) w.chain[j] = mdfE[j]
        val orgE = Exciter.orgHigh(orgbank, w.sig574)
        for (j in 0..3) w.chain[32 + j] = orgE[j]

        // 9 Akku-Paare: slot = roh + prev, prev = roh
        val e9 = DoubleArray(9)
        for (j in 0..4) {
            val roh = w.chain[j]
            e9[j] = roh + w.accuPrev[j]
            w.accuPrev[j] = roh
        }
        for (j in 0..3) {
            val roh = w.chain[32 + j]
            e9[5 + j] = roh + w.accuPrev[5 + j]
            w.accuPrev[5 + j] = roh
        }

        // envelope + FIR_2band; Slew 10/-120 aus tail16 @0xbcc2
        val keys8 = cw.g8.keys.sorted()
        val prev8 = if (keys8.isEmpty()) DoubleArray(8)
        else cw.g8[keys8[minOf(sub, keys8.size - 1)]]!!
        val (coefs, _gains) = Exciter.envelopeExtrapolation(
            e9, prev8, ibmapN, ibmapM, ibmapBandidx, bank1b, w3, pairs1, rows4,
            slewOn = true, cUp = 10.0, cDn = -120.0, clampMode = 1, ceil = c.idx,
            flEn = 1, flOff = 0.0, off1 = 0.0, off2 = 0.0, gmax = 20.0
        )
        // fir_2band: count 4 -> n2 = 8, nblocks 8 -> 64 Ausgaben
        val enhanced = Exciter.fir2band(coefs, firG0, firG1, w.sig3d4, 8)
        w.sig2d4 = enhanced.copyOfRange(0, 64)
        return synthesis(w, cw, sub)
    }

    // ------------------------------------------------------------------- pads
    /** upcon braucht inp[0..2k+n_pos-1]: k History + core + k Scratch. */
    private fun pad(w: ChanWork, key: String, core: DoubleArray, k: Int): DoubleArray {
        var hist = w.pads[key] ?: DoubleArray(0)
        hist = if (hist.size < k) {
            DoubleArray(k - hist.size) + hist
        } else {
            hist.copyOfRange(hist.size - k, hist.size)
        }
        w.pads[key] = core.copyOfRange(maxOf(0, core.size - k), core.size)
        return hist + core + DoubleArray(k)
    }

    // -------------------------------------------------------------- _synthesis
    private fun synthesis(w: ChanWork, cw: CWork, sub: Int): DoubleArray {
        val c = cfg
        val mode = c.mode

        // upcon-Site1 (nur [h+0x11c0]==0; Fullband, k=12, F=MODE, 48k-Tabelle)
        val inp1 = pad(w, "up1", w.sig2d4, 12)
        val prev1 = (w.up1Prev + DoubleArray(128)).copyOfRange(0, 128)
        val up1 = Upcon.upconDirect(
            Tables.WIN_48K_MODE2, 12, mode, inp1, prev1, 0.0, 64, blend = false
        )
        w.up1Prev = up1.copyOfRange(0, mode * 64)

        // IIR (HR-Biquad-Kette h+0x11a8, 4 Sektionen)
        val irRes = Exciter.iirFiltering(
            c.hrIirCoefs, c.hrPregain, 4, 1, up1.copyOfRange(0, 64), w.hrIirState
        )
        val ir = irRes.first
        w.hrIirState = irRes.second

        // mdf_hr (Eingang = IIR-Signal) + pow_calc; Slot 4 = IIR-Power (159)
        val inpHr = pad(w, "hr", ir, 10)
        val mE = Exciter.mdfHr(c.hrBank, inpHr)
        val powv = Exciter.powCalc(ir)
        val feats = DoubleArray(5)
        for (i in 0..3) feats[i] = mE[i]
        feats[4] = powv

        // GDCF -> downcon-Koeffs (11)
        val dcoef = Exciter.gdcf(c.hrBank, feats, featIdx, c.gdcfA, c.gdcfCap)

        // downcon (stride 4, 16 Ausgaenge) auf dem IIR-Signal
        val inpDc = pad(w, "dc", ir, 10)
        val dwn = Exciter.downcon(dcoef, inpDc, 4)
        w.slots1090 = dwn.copyOfRange(0, minOf(16, dwn.size))

        // GUCF (Band-1-Kern): prev = dB_slot2, Slot 3 = 0; Slew 10/-120
        var g3 = cw.g3[sub]
        if (g3 == null) {
            val ks = cw.g3.keys.sorted()
            g3 = if (ks.isEmpty()) DoubleArray(0) else cw.g3[ks.last()]!!
        }
        val n3 = minOf(3, g3.size)
        val prev4 = DoubleArray(4)
        for (i in 0 until n3) prev4[i] = g3[i]
        var kern: DoubleArray
        kern = try {
            val k = Upcon.gucf(
                feats, featIdx, Tables.P_MODE2, Tables.SRC_48K_MODE2, 36,
                gucfC, c.gucfMode, slewOn = true, prev = prev4,
                cUp = 10.0, cDn = -120.0
            )
            // Python-Fallback: log10(0) -> ValueError -> Nullkern
            if (k.all { it.isFinite() }) k else DoubleArray(61)
        } catch (e: Exception) {
            DoubleArray(61)
        }
        val kernPad = DoubleArray(maxOf(0, 65 - kern.size))
        w.gucfKern = kern + kernPad

        // Rausch-LCG + Gain-Fenster (198): f = 10*log10(pow_calc)
        val f = 10.0 * Math.log10(maxOf(powv, 1e-12))
        val s14 = if (powv > 1e-12) c.noiseScale else 0.0
        val gain = when {
            powv <= 1e-12 || f < -110.0 - c.idx -> 0.0
            f > -80.0 - c.idx -> s14
            else -> s14 * Math.pow(10.0, (f + c.idx + 80.0) / 20.0)
        }
        val lcgRes = lcgSeq(lcgState, 64)
        val noiseVals = lcgRes.first
        lcgState = lcgRes.second
        w.noise = DoubleArray(64) { noiseVals[it] * gain }

        // upcon-Sites 2/3: mode 2 -> EIN Call, sonst 2 Calls mit Blend
        val inp2 = pad(w, "up2", w.slots1090, 8)
        val outB: DoubleArray = if (mode == 2) {
            Upcon.upconDirect(
                w.gucfKern, 8, 8, inp2, DoubleArray(128), 0.0, 16, blend = false
            )
        } else {
            val prev2 = (w.up2Prev + DoubleArray(128)).copyOfRange(0, 128)
            val ob = Upcon.upconDirect(
                w.gucfKern, 8, 8, inp2, prev2, 1.0, 16, blend = true
            )
            val inp3 = pad(w, "up3", w.noise, 2)
            Upcon.upconDirect(
                Tables.BW_TBL_MODE2, 2, 8, inp3, ob, 1.0, 16, blend = true
            )
        }
        w.up2Prev = outB.copyOfRange(0, 128)

        // finaler Effekt-Output: bw+0x714[0..MODE*64)
        val result = outB.copyOfRange(0, mode * 64)
        val newBandHist = DoubleArray(256)
        System.arraycopy(result, 0, newBandHist, 0, minOf(256, mode * 64))
        System.arraycopy(
            w.bandHist, 0, newBandHist, minOf(256, mode * 64),
            256 - minOf(256, mode * 64)
        )
        w.bandHist = newBandHist
        return result.copyOfRange(0, 64)
    }

    companion object {
        /** Rausch-LCG @0x12a3c-Familie: s = s*0x41C64E6D + 0x3039 mod 2^64,
         *  x16 = Top-16 des High-32 (TODO 5), out = (x16-32768)/32768. */
        fun lcgSeq(state0: ULong, n: Int): Pair<DoubleArray, ULong> {
            val out = DoubleArray(n)
            var s = state0
            for (i in 0 until n) {
                s = s * 0x41C64E6DUL + 0x3039UL      // ULong: mod 2^64
                val hi = (s shr 32) and 0xFFFFFFFFUL
                val x16 = (hi shr 16) and 0xFFFFUL
                out[i] = (x16.toDouble() - 32768.0) / 32768.0
            }
            return Pair(out, s)
        }
    }
}

operator fun DoubleArray.plus(other: DoubleArray): DoubleArray {
    val r = DoubleArray(size + other.size)
    System.arraycopy(this, 0, r, 0, size)
    System.arraycopy(other, 0, r, size, other.size)
    return r
}
