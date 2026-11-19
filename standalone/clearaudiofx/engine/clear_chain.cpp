// Port von FullChainChannel.processBlock (ClearChainMain.kt / ...Main44.kt).
// Pipeline je Block: In-Bank (hist24+fresh64+win20) -> Stage A (2x-FIR,
// fin[62..190)) -> Analyse (Gain, DC-Notch-Biquad, D-FIR) -> Upcon P0+P1
// -> OUT = fin[0..128) -> Carries.
#include "clear_chain.h"
#include "engine_gen.h"
#include <string.h>

namespace clearaudio {

void ClearChainChannel::processBlock(const float* fresh, float* out) {
    float inArr[108];
    for (int i = 0; i < 24; i++) inArr[i] = hist_[i];
    for (int i = 0; i < 64; i++) inArr[24 + i] = fresh[i];
    for (int i = 0; i < 20; i++) inArr[88 + i] = win_[i];

    // Stage A: schreibt fin[62..190)
    stageA(inArr, fin_, s_, fc_->ROD_SA);
    float saOut[128];
    memcpy(saOut, fin_ + 62, 128 * sizeof(float));

    // Analyse: Gain -> DC-Notch -> D-FIR. Exakte Oporder 0x12ca8c..0x12cab0.
    float c82[64];
    float uu = u_;
    float vv = v_;
    for (int j = 0; j < 64; j++) {
        float x = fresh[j] * fc_->GAIN;
        float s7 = vv * fc_->BQ_B;
        float s0a = uu * fc_->BQ_A;
        float s0 = x - s0a;
        float s3m = uu * fc_->BQ_M2;
        s0 = s0 - s7;
        float s1v = vv * fc_->BQ_ONE;
        float s3 = s0 + s3m;
        float y = s3 + s1v;
        c82[j] = y;
        float oldU = uu;
        uu = s0;
        vv = oldU;
    }
    float dneu[16];
    for (int j = 0; j < 16; j++) {
        int c = 10 + 4 * j;
        // st(i) = i < 20 ? win[i] : c82[i-20]
        float stc = (c < 20) ? win_[c] : c82[c - 20];
        float acc = stc * fc_->TAPS[0];
        for (int k = 1; k <= 10; k++) {
            float stp = (c + k < 20) ? win_[c + k] : c82[c + k - 20];
            float stm = (c - k < 20) ? win_[c - k] : c82[c - k - 20];
            float sk = stp + stm;
            acc = acc + sk * fc_->TAPS[k];
        }
        dneu[j] = acc;
    }
    for (int i = 0; i < 16; i++) d_[16 + i] = dneu[i];

    // Upcon Phase 0 + Phase 1
    upconP0(fin_, d_, s_, stack_, fc_->K65, fc_->ROD_P1);
    upconP1(fin_, d_, s_, stack_, fc_->K65, fc_->ROD_P1);
    memcpy(out, fin_, 128 * sizeof(float));

    // Carries (Reihenfolge nach 288/289)
    for (int i = 0; i < 24; i++) hist_[i] = fresh[40 + i];
    for (int i = 0; i < 20; i++) win_[i] = c82[44 + i];
    u_ = uu;
    v_ = vv;
    for (int i = 0; i < 16; i++) d_[i] = dneu[i];
    for (int i = 0; i < 62; i++) fin_[i] = saOut[66 + i];
}

} // namespace clearaudio
