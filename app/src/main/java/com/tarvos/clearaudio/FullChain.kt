package com.tarvos.clearaudio

/**
 * Full-Chain-Kanal: Port des validierten Full-Chain-Blockmodells
 * (KNOWLEDGE 289-290, bitweise abgenommen gegen die 16-Block-Emulator-
 * Referenz: FAIL=0, ok=4066, dust=30, maxDust 2.6e-35).
 *
 * Pipeline je Block (64 Eingangswerte -> 128 Ausgangswerte @2x Rate):
 *   In-Bank = hist24 ++ fresh64 ++ win20
 *   Stage A  (25er-Polyphase-2x-FIR)             -> fin[62..190)
 *   Analyse  Gain -> DC-Notch-Biquad -> D-FIR 16 Outputs (exakte Oporder)
 *   Upcon    P0 (FIN + 8*lane, D/K je Gruppe) -> P1 (P1-Taps, Staub=0)
 *   OUT      = fin[0..128)
 *   Carries  hist/win/(u,v)/D-Shift/FIN-Rotation (fin[0..62) = saOut[66..128))
 *
 * 44.1 kHz: identische Kette, nur ratenabhaengige Tabellen K65/TAPS/
 * ROD_SA aus FC44 (KNOWLEDGE 293, bitweise abgenommen gegen die 44.1-
 * Emulator-Referenz: FAIL=0, ok=4066, dust=30). Der Konstruktor-Parameter
 * for44k waehlt das Tabellen-Set; alles Andere ist rateninvariant.
 * Der komplette Streaming-State sind 124 f32 je Kanal plus FIN-Tail;
 * Kaltstart = alles 0.0 == APK-Bootfall. Alle Arithmetik in Kotlin-Float
 * (= IEEE f32 je Operation, kein FMA): identische Semantik zur Maschine.
 * Der 0x115-Driver-Staub D[32..52) ist als konstante 0 in upconP1 kodiert
 * (KNOWLEDGE 289: Werte <= 2.7e-35, block-/kanalabhaengig, PCM16-wirkungslos).
 */
class FullChainChannel(private val for44k: Boolean = false) {

    // Ratenabhaengige Tabellen (einmalig aufgeloest, kein Branch im Block-Loop)
    private val rodSA = if (for44k) FC44.ROD_SA else FC.ROD_SA
    private val k65 = if (for44k) FC44.K65 else FC.K65
    private val taps = if (for44k) FC44.TAPS else FC.TAPS

    private val hist = FloatArray(24)
    private val win = FloatArray(20)
    private val d = FloatArray(32)
    private val fin = FloatArray(190)
    private val s = FloatArray(32)
    private val stack = FloatArray(16)
    private var u = 0f
    private var v = 0f

    /** Zustaende auf Kaltstart (APK-Bootfall, == pre0-Snapshot der Referenz). */
    fun reset() {
        java.util.Arrays.fill(hist, 0f)
        java.util.Arrays.fill(win, 0f)
        java.util.Arrays.fill(d, 0f)
        java.util.Arrays.fill(fin, 0f)
        java.util.Arrays.fill(s, 0f)
        java.util.Arrays.fill(stack, 0f)
        u = 0f
        v = 0f
    }

    /**
     * Verarbeitet EXAKT 64 Eingangswerte (f32) und schreibt 128 Ausgangswerte
     * (2x Rate) in out[0..128). Die Inputs muessen bereits auf f32 gerundet
     * sein (PCM16-Werte sind exakt; Float-Passthrough ist es per Definition).
     * Der Zustand laeuft ueber Blockgrenzen weiter (Carries wie in der Firmware).
     */
    fun processBlock(fresh: FloatArray, out: FloatArray) {
        require(fresh.size == 64)
        require(out.size >= 128)

        // --- In-Bank bauen (Floats 0..107) ---
        val inArr = sInBank
        for (i in 0..23) inArr[i] = hist[i]
        for (i in 0..63) inArr[24 + i] = fresh[i]
        for (i in 0..19) inArr[88 + i] = win[i]

        // --- Stage A (schreibt fin[62..190)) ---
        stageA(inArr, fin, s, rodSA)
        val saOut = sSaOut
        for (i in 0..127) saOut[i] = fin[62 + i]

        // --- Analyse: Gain -> DC-Notch -> D-FIR ---
        val c82 = sC82
        var uu = u
        var vv = v
        for (j in 0..63) {
            val x = fresh[j] * FC.GAIN
            // exakte Oporder der Instruktionskette 0x12ca8c..0x12cab0
            val s7 = vv * FC.BQ_B        // v*b
            val s0a = uu * FC.BQ_A       // u*a
            var s0 = x - s0a             // x - u*a
            val s3m = uu * FC.BQ_M2      // u*(-2)
            s0 = s0 - s7                 // - v*b
            val s1v = vv * FC.BQ_ONE     // v*1
            val s3 = s0 + s3m
            val y = s3 + s1v
            c82[j] = y
            val oldU = uu
            uu = s0                      // u' = s0
            vv = oldU                    // v' = u_alt
        }
        val st = { i: Int -> if (i < 20) win[i] else c82[i - 20] }
        for (j in 0..15) {
            val c = 10 + 4 * j
            var acc = st(c) * taps[0]
            for (k in 1..10) {
                val sk = st(c + k) + st(c - k)
                acc = acc + sk * taps[k]
            }
            sDneu[j] = acc
        }
        val dneu = sDneu
        for (i in 0..15) d[16 + i] = dneu[i]

        // --- Upcon Phase 0 + Phase 1 ---
        upconP0(fin, d, s, stack, k65, FC.ROD_P1)
        upconP1(fin, d, s, stack, k65, FC.ROD_P1)
        for (i in 0..127) out[i] = fin[i]

        // --- Carries (Caller-Sektion) ---
        for (i in 0..23) hist[i] = fresh[40 + i]
        for (i in 0..19) win[i] = c82[44 + i]
        u = uu
        v = vv
        for (i in 0..15) d[i] = dneu[i]
        for (i in 0..61) fin[i] = saOut[66 + i]
    }

    // Scratch-Buffer (wiederverwendet, keine Reallokation je Block)
    private val sInBank = FloatArray(108)
    private val sSaOut = FloatArray(128)
    private val sC82 = FloatArray(64)
    private val sDneu = FloatArray(16)
}
