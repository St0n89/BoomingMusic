// Tabellen der Full-Chain, 48 kHz und 44.1 kHz (AUTO-GENERIERT in fc_tables.cpp).
#pragma once
#include <stdint.h>

namespace clearaudio {

struct FcTables {
    float GAIN;
    float BQ_A;
    float BQ_B;
    float BQ_M2;
    float BQ_ONE;
    const float* ROD_SA;   // 25
    const float* ROD_P1;   // 17
    const float* TAPS;     // 11
    const float* K65;      // 65
};

extern const FcTables FC48;
extern const FcTables FC44;

// Auswahl nach Abtastrate: 44100 -> FC44, sonst FC48.
inline const FcTables* fc_for_rate(uint32_t sample_rate) {
    return (sample_rate == 44100u) ? &FC44 : &FC48;
}

} // namespace clearaudio
