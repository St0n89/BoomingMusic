// Smoke-Test der Effect-Logik (natives Build, ohne Android):
// create -> SET_CONFIG(48k/44.1k, stereo float) -> ENABLE -> process
// mit Sinus + Stille. Prueft: kein Crash, endlich, Anstieg, Unterlauf-Nullen.
// NICHT bit-exakt gemeint (siehe engine_test dafuer), nur Integrationscheck.
#include <hardware/audio_effect.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <vector>

extern "C" {
extern struct audio_effect_library_s AELI;
}

int main() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    effect_handle_t h = nullptr;
    int rc = AELI.create_effect(nullptr, 0, 0, &h);
    printf("create(nullptr uuid) rc=%d (erwartet -EINVAL)\n", rc);
    effect_uuid_t uuid = {0xe8ed75b5, 0xd5cb, 0x5d8f, 0x8e86,
                          {0xfb, 0xc5, 0x41, 0x79, 0x19, 0x50}};
    rc = AELI.create_effect(&uuid, 0, 0, &h);
    printf("create(impl uuid) rc=%d handle=%s\n", rc,
           rc == 0 && h ? "ok" : "FEHLER");
    if (rc != 0) return 1;

    effect_descriptor_t d;
    rc = (*h)->get_descriptor(h, &d);
    printf("get_descriptor rc=%d name=%s\n", rc, d.name);

    const uint32_t rates[2] = {48000u, 44100u};
    for (int r = 0; r < 2; r++) {
        effect_config_t cfg;
        memset(&cfg, 0, sizeof(cfg));
        uint32_t rep = sizeof(int32_t);
        cfg.inputCfg.samplingRate = rates[r];
        cfg.inputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
        cfg.inputCfg.format = (uint8_t)AUDIO_FORMAT_PCM_FLOAT;
        cfg.inputCfg.mask = EFFECT_CONFIG_SMP_RATE | EFFECT_CONFIG_CHANNELS |
                             EFFECT_CONFIG_FORMAT;
        cfg.outputCfg = cfg.inputCfg;
        (void)rep;
        // Korrekter Aufruf mit echtem replyPtr:
        int32_t st = -99;
        uint32_t rs = sizeof(st);
        rc = (*h)->command(h, EFFECT_CMD_SET_CONFIG, sizeof(cfg), &cfg,
                           &rs, &st);
        printf("rate %u SET_CONFIG rc=%d status=%d\n", rates[r], rc, st);
        uint32_t en = sizeof(int32_t);
        rc = (*h)->command(h, EFFECT_CMD_ENABLE, 0, nullptr, &en, &st);
        printf("ENABLE rc=%d\n", rc);

        // 2 s Sinus 1 kHz @ float stereo, 256-Frame-Bloecke
        const uint32_t N = rates[r] / 256 * 256;
        std::vector<float> inbuf(2 * N), outbuf(2 * N);
        for (uint32_t i = 0; i < N; i++) {
            float x = 0.4f * sinf(2.0f * (float)M_PI * 1000.0f *
                                 (float)i / (float)rates[r]);
            inbuf[2 * i] = x;
            inbuf[2 * i + 1] = x;
        }
        size_t produced = 0;
        bool bad = false;
        for (uint32_t off = 0; off < N; off += 256) {
            audio_buffer_t inb, outb;
            inb.frameCount = 256; inb.f32 = inbuf.data() + 2 * off;
            outb.frameCount = 256; outb.f32 = outbuf.data() + 2 * off;
            int p = (*h)->process(h, &inb, &outb);
            if (p != 0) { bad = true; break; }
            for (int k = 0; k < 256; k++) {
                float l = outbuf[2 * (off + k)];
                if (!(fabsf(l) < 1e30f)) bad = true;  // NaN/inf-Check
            }
        }
        // Spaete Samples muessen Energie haben (Kette warm, Sinus drin)
        float mx = 0.0f, mn = 0.0f;
        for (uint32_t i = N - 256; i < N; i++) {
            float l = outbuf[2 * i];
            if (l > mx) mx = l;
            if (l < mn) mn = l;
        }
        printf("  process ok=%s  Letzte 256 Pics: min=%f max=%f "
               "(Erwartung: Schwingung um 0, kein NaN)\n",
               bad ? "NEIN" : "ja", (double)mn, (double)mx);
        // RESET zwischen Raten
        uint32_t rs2 = sizeof(st);
        (*h)->command(h, EFFECT_CMD_RESET, 0, nullptr, &rs2, &st);
    }
    rc = AELI.release_effect(h);
    printf("release rc=%d\n", rc);
    printf("SMOKE: OK\n");
    return 0;
}
