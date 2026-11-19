// Emulator-Referenz-Fixtures der Full-Chain (AUTO-GENERIERT, fixtures.cpp).
#pragma once
#include <stdint.h>

namespace clearaudio {

struct ChainFixtures {
    int blocks;
    const int32_t* INL;   // blocks * 64 (1x-Eingang, Bit-Muster)
    const int32_t* INR;
    const int32_t* OUTL;  // blocks * 128 (2x-Ausgang, Bit-Muster)
    const int32_t* OUTR;
};

extern const ChainFixtures FX48;
extern const ChainFixtures FX44;

} // namespace clearaudio
