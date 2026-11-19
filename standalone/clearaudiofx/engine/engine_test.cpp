// Bitweise Abnahme der C++-Full-Chain gegen die Emulator-Fixtures beider
// Raten (Port von ClearChainMain.kt / ClearChainMain44.kt).
// Klassifikation je Output-Sample: ok / dust (beide < 1e-30) / FAIL.
#include "clear_chain.h"
#include "fixtures.h"
#include <cstdio>
#include <cstring>

namespace {

int32_t bits_of(float f) {
    int32_t b;
    memcpy(&b, &f, 4);
    return b;
}

struct Counters {
    long ok = 0;
    long dust = 0;
    long fail = 0;
};

void run_rate(const char* name, uint32_t sr, const clearaudio::ChainFixtures& fx,
              Counters* c) {
    clearaudio::ClearChainChannel chL(sr);
    clearaudio::ClearChainChannel chR(sr);
    const int bl = fx.blocks;
    for (int b = 0; b < bl; b++) {
        float inL[64], inR[64], outL[128], outR[128];
        for (int i = 0; i < 64; i++) {
            int32_t bL = fx.INL[b * 64 + i];
            int32_t bR = fx.INR[b * 64 + i];
            memcpy(&inL[i], &bL, 4);
            memcpy(&inR[i], &bR, 4);
        }
        chL.processBlock(inL, outL);
        chR.processBlock(inR, outR);
        for (int i = 0; i < 128; i++) {
            for (int ch = 0; ch < 2; ch++) {
                float got = ch ? outR[i] : outL[i];
                int32_t want = ch ? fx.OUTR[b * 128 + i] : fx.OUTL[b * 128 + i];
                float wantf;
                memcpy(&wantf, &want, 4);
                if (bits_of(got) == want) {
                    c->ok++;
                } else if (got < 1e-30f && got > -1e-30f &&
                           wantf < 1e-30f && wantf > -1e-30f) {
                    c->dust++;
                } else {
                    c->fail++;
                    if (c->fail < 8) {
                        printf("FAIL %s blk %d ch %d i %d  got %a (%08x) want "
                               "%a (%08x)\n", name, b, ch, i,
                               (double)got, bits_of(got),
                               (double)wantf, (uint32_t)want);
                    }
                }
            }
        }
    }
}

} // namespace

int main() {
    Counters c48, c44;
    run_rate("48k", 48000u, clearaudio::FX48, &c48);
    run_rate("44k1", 44100u, clearaudio::FX44, &c44);
    printf("48k:   ok=%ld dust=%ld FAIL=%ld\n", c48.ok, c48.dust, c48.fail);
    printf("44k1:  ok=%ld dust=%ld FAIL=%ld\n", c44.ok, c44.dust, c44.fail);
    long total = (c48.fail + c44.fail);
    printf(total == 0 ? "ERGEBNIS: BESTANDEN (beide Raten bit-exakt)\n"
                      : "ERGEBNIS: FEHLGESCHLAGEN (%ld FAIL)\n", total);
    return total == 0 ? 0 : 1;
}
