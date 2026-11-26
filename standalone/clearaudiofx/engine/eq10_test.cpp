// Bitweise Abnahme des C++-Eq10 gegen die Firmware-Fixtures beider Raten
// (aa64emu, Random stereo; Python-Referenz: 3584/3584 diff 0; Kotlin-
// Abnahme Phase 3B: differing=0). Klassifikation wie engine_test: exakt
// (Eq10 hat keine Dust-Klasse, die Kotlin-Abnahme war strikt).
#include "eq10.h"
#include "eq10_fixtures.h"
#include <cstdio>
#include <cstring>

namespace {

uint32_t bits_of(float f) {
    uint32_t b;
    memcpy(&b, &f, 4);
    return b;
}

float bits_to_f(uint32_t b) {
    float f;
    memcpy(&f, &b, 4);
    return f;
}

long run_channel(const char* name, const clearaudio::Eq10Channel& proto,
                 const uint32_t* in, const uint32_t* want, int n) {
    clearaudio::Eq10Channel ch(proto);
    ch.reset();
    long fail = 0;
    for (int off = 0; off < n; off += 64) {
        float blk[64];
        for (int i = 0; i < 64; i++) blk[i] = bits_to_f(in[off + i]);
        ch.processBlock64(blk);
        for (int i = 0; i < 64; i++) {
            const uint32_t got = bits_of(blk[i]);
            const uint32_t w = want[off + i];
            if (got != w) {
                fail++;
                if (fail <= 8) {
                    printf("FAIL %s off %d i %d  got %a (%08x) want %a "
                           "(%08x)\n", name, off, i,
                           (double)blk[i], got,
                           (double)bits_to_f(w), w);
                }
            }
        }
    }
    return fail;
}

} // namespace

int main() {
    long fail = 0, total = 0;
    {
        clearaudio::Eq10Channel proto(clearaudio::kEq10Coeffs96K);
        fail += run_channel("96k-L", proto, EQ10_96K_INL, EQ10_96K_OUTL,
                            Eq10Fx96K::SAMPLES);
        fail += run_channel("96k-R", proto, EQ10_96K_INR, EQ10_96K_OUTR,
                            Eq10Fx96K::SAMPLES);
        total += 2 * Eq10Fx96K::SAMPLES;
    }
    {
        clearaudio::Eq10Channel proto(clearaudio::kEq10Coeffs882);
        fail += run_channel("88k2-L", proto, EQ10_88K_INL, EQ10_88K_OUTL,
                            Eq10Fx88K::SAMPLES);
        fail += run_channel("88k2-R", proto, EQ10_88K_INR, EQ10_88K_OUTR,
                            Eq10Fx88K::SAMPLES);
        total += 2 * Eq10Fx88K::SAMPLES;
    }
    printf("eq10:  n=%ld FAIL=%ld\n", total, fail);
    printf(fail == 0 ? "ERGEBNIS: BESTANDEN (Eq10 bit-exakt, beide Raten)\n"
                     : "ERGEBNIS: FEHLGESCHLAGEN (%ld FAIL)\n", fail);
    return fail == 0 ? 0 : 1;
}
