// Full-Chain-Kanal, Port von FullChainChannel.kt / ClearChainMain44.kt
// (valide 289-294 gegen Emulator-Fixtures, exakte Oporder 288).
#pragma once
#include <stdint.h>
#include "fc_tables.h"

namespace clearaudio {

class ClearChainChannel {
public:
    explicit ClearChainChannel(uint32_t sample_rate = 48000u)
        : fc_(fc_for_rate(sample_rate)) {
        reset();
    }

    void reset() {
        for (int i = 0; i < 24; i++) hist_[i] = 0.0f;
        for (int i = 0; i < 20; i++) win_[i] = 0.0f;
        for (int i = 0; i < 32; i++) d_[i] = 0.0f;
        for (int i = 0; i < 190; i++) fin_[i] = 0.0f;
        for (int i = 0; i < 32; i++) s_[i] = 0.0f;
        for (int i = 0; i < 16; i++) stack_[i] = 0.0f;
        u_ = 0.0f;
        v_ = 0.0f;
    }

    // Eingang: 64 frische Samples (1x-Rate). Ausgang: 128 Samples (2x-Rate).
    void processBlock(const float* fresh, float* out);

private:
    const FcTables* fc_;
    float hist_[24];
    float win_[20];
    float d_[32];
    float fin_[190];
    float s_[32];
    float stack_[16];
    float u_;
    float v_;
};

} // namespace clearaudio
