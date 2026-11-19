// clearaudio_fx.cpp - Systemweiter ClearAudio-Effekt (AudioEffect-Legacy-HAL,
// laedt in audioserver via audio_effects.xml, Muster JamesDSP).
//
// DSP: pro Kanal die validierte Full-Chain (Stage A 25er-Polyphase-2x-FIR,
// Gain, DC-Notch, D-FIR-Analyse, Upcon P0/P1; bit-exakt gegen Emulator-
// Fixtures, 48k UND 44.1k, je ok=4066/dust=30/FAIL=0). Die Kette ist
// grundsaetzlich ein 2x-Upsampler; ein Session-0-Effekt darf die Stream-Rate
// aber NICHT aendern (in-place). Kompromiss (bewusst, dokumentiert):
// Chain laeuft intern mit 2x, danach Polyphase-Dezimation 2x->1x mit dem
// SElbigen ROD_SA-Kern als Anti-Alias-Synthese. RE-abgeleitet, aber NICHT
// bit-identisch mit dem Walkman (kein 2x-Ausgang moeglich).
//
// Schalter: System-Property persist.clearaudio.enabled (Tile-APK via su),
// gepollt ~alle 40 ms, gebounct auf process()-Framezaehler.
//
// Requests/Kommentare: tarvos/Dennis, RE-Stand 294.
#include <hardware/audio_effect.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <stdio.h>
#include <new>

#include "../engine/clear_chain.h"

#ifdef __ANDROID__
#include <sys/system_properties.h>
#include <time.h>
#endif

namespace {

// Forward-Deklaration: Interface-Deskriptor (unten definiert)
extern const struct effect_interface_s kItfe;

//_EFFECT_UUID_TYPE_BEGIN (frei gewaehlt, keine Standard-IID)
const effect_uuid_t kTypeUuid = {0x7c4d3a10, 0x9b8e, 0x4f21, 0xa556,
                                 {0xd1, 0xe2, 0xf3, 0xa4, 0xb5, 0xc6}};
// Implementierungs-UUID (fix)
const effect_uuid_t kImplUuid = {0xe8ed75b5, 0xd5cb, 0x5d8f, 0x8e86,
                                 {0xfb, 0xc5, 0x41, 0x79, 0x19, 0x50}};
// Bibliotheks-UUID (fix, nur fuer Deskriptor-Queries via lib, create nimmt impl)
const effect_uuid_t kLibUuid = {0x1bbbaba9, 0xa9f9, 0x5a51, 0x9eae,
                                {0x64, 0x29, 0x85, 0x7c, 0x10, 0x84}};

const uint32_t kSupportedRates[2] = {44100u, 48000u};

// Ein Effekt-Engine-Modul: Symmetrie mit contexts + Interface-Zeiger oben.
struct ClearContext {
    // MUSS zuerst stehen: effect_handle_t zeigt auf &itfe.
    const struct effect_interface_s* itfe;
    clearaudio::ClearChainChannel chL;
    clearaudio::ClearChainChannel chR;
    uint32_t sampleRate;
    bool configured;
    bool bypass;          // Rate/Format/Kanaele nicht unterstuetzt -> копiere
    // 1x-Eingangsreservoir (64 Frames)
    float inL[64];
    float inR[64];
    int inFill;
    // 1x-Ausgangsreservoir (64 Frames)
    float outL[64];
    float outR[64];
    int outRead;
    int outWrite;
    // Dezimator-Historie: letzte 24 Samples der 2x-Reihe je Kanal
    float prevUL[24];
    float prevUR[24];
    // Property-Poll-Zustand
    bool propEnabled;
    int framesSincePropPoll;

    explicit ClearContext(uint32_t sr)
        : itfe(nullptr), chL(sr), chR(sr), sampleRate(sr),
          configured(false), bypass(false),
          inFill(0), outRead(0), outWrite(0),
          propEnabled(false), framesSincePropPoll(0) {
        memset(inL, 0, sizeof(inL));
        memset(inR, 0, sizeof(inR));
        memset(outL, 0, sizeof(outL));
        memset(outR, 0, sizeof(outR));
        memset(prevUL, 0, sizeof(prevUL));
        memset(prevUR, 0, sizeof(prevUR));
    }

    void resetStreams() {
        chL.reset();
        chR.reset();
        memset(inL, 0, sizeof(inL));
        memset(inR, 0, sizeof(inR));
        memset(outL, 0, sizeof(outL));
        memset(outR, 0, sizeof(outR));
        memset(prevUL, 0, sizeof(prevUL));
        memset(prevUR, 0, sizeof(prevUR));
        inFill = 0;
        outRead = 0;
        outWrite = 0;
    }
};

const effect_descriptor_t kDescriptor = {
    {0x7c4d3a10, 0x9b8e, 0x4f21, 0xa556, {0xd1, 0xe2, 0xf3, 0xa4, 0xb5, 0xc6}},
    {0xe8ed75b5, 0xd5cb, 0x5d8f, 0x8e86, {0xfb, 0xc5, 0x41, 0x79, 0x19, 0x50}},
    EFFECT_CONTROL_API_VERSION,
    EFFECT_FLAG_INSERT_LAST,
    10,  // cpuLoad
    1,   // memoryUsage
    "ClearAudioFX",
    "tarvos/Dennis ClearAudio+ RE"};

// ---------- Dezimation 2x->1x (ROD_SA als Anti-Alias, aequidistant) ----------
// y[n] = sum_{k=0..24} h[k] * u[2n-k], u = (prevU24 || block128).
// Nutzbar fuer alle n: 2n-k in [-24 .. 126]. 12 Frames Delay gegenueber
// der 1x-Reihe.
void decimate64(const float* uCh, float* prevU, float* y,
                const float* h) {
    float U[152];
    memcpy(U, prevU, 24 * sizeof(float));
    memcpy(U + 24, uCh, 128 * sizeof(float));
    for (int n = 0; n < 64; n++) {
        float acc = 0.0f;
        const float* p = U + 24 + 2 * n - 24;
        for (int k = 0; k < 25; k++) {
            acc = acc + h[k] * p[24 - k];
        }
        y[n] = acc;
    }
    memcpy(prevU, uCh + 104, 24 * sizeof(float));
}

// ---------- Property-Auswertung ----------
bool propIsEnabled(ClearContext* c) {
#ifdef __ANDROID__
    if (c->framesSincePropPoll < 1920) return c->propEnabled;  // ~40 ms
    c->framesSincePropPoll = 0;
    char val[PROP_VALUE_MAX] = {0};
    __system_property_get("persist.clearaudio.enabled", val);
    c->propEnabled = (val[0] == '1');
    return c->propEnabled;
#else
    (void)c;
    return true;
#endif
}

// ---------- Interface-Funktionen ----------
int32_t proc_process(effect_handle_t self, audio_buffer_t* inb,
                     audio_buffer_t* outb) {
    // effect_handle_t zeigt auf c->itfe; da itfe erstes Member ist, gilt
    // (char*)self == (char*)c (uebliches AOSP-Idiom: cast auf self).
    ClearContext* c = (ClearContext*)self;
    if (inb == nullptr || outb == nullptr ||
        inb->raw == nullptr || outb->raw == nullptr) {
        return -EINVAL;
    }
    const size_t frames = inb->frameCount;
    if (frames == 0) return 0;
    c->framesSincePropPoll += (int)frames;

    const float* in = inb->f32 != nullptr ? inb->f32
                                          : (const float*)inb->raw;
    float* out = outb->f32 != nullptr ? outb->f32 : (float*)outb->raw;
    const size_t outCap = outb->frameCount;

    if (c->bypass || !propIsEnabled(c) || !c->configured) {
        size_t n = frames < outCap ? frames : outCap;
        if (in == out) return 0;              // in-place: nichts zu tun
        memcpy(out, in, n * 2 * sizeof(float));
        if (n < outCap && outCap == frames) memset(out + n * 2, 0,
            (outCap - n) * 2 * sizeof(float));
        outb->frameCount = n;
        return 0;
    }

    // Frames einsammeln, in 64er-Bloecken durch die Chain
    size_t producedPos = 0;
    if (in == out) {
        // in-place: Eingang erst Sicherungskopieren nicht noetig, wir lesen
        //.Frame-fuer-Frame und schreiben erst nach Lesezugriff.
    }
    for (size_t f = 0; f < frames; f++) {
        c->inL[c->inFill] = in[2 * f];
        c->inR[c->inFill] = in[2 * f + 1];
        c->inFill++;
        if (c->inFill == 64) {
            float oL[128], oR[128];
            c->chL.processBlock(c->inL, oL);
            c->chR.processBlock(c->inR, oR);
            float dL[64], dR[64];
            const clearaudio::FcTables* fc = clearaudio::fc_for_rate(
                c->sampleRate);
            decimate64(oL, c->prevUL, dL, fc->ROD_SA);
            decimate64(oR, c->prevUR, dR, fc->ROD_SA);
            memcpy(c->outL, dL, 64 * sizeof(float));
            memcpy(c->outR, dR, 64 * sizeof(float));
            c->outRead = 0;
            c->outWrite = 64;
            c->inFill = 0;
        }
        // Aus sofern verfuegbar
        if (c->outWrite > c->outRead && producedPos < outCap) {
            out[2 * producedPos] = c->outL[c->outRead];
            out[2 * producedPos + 1] = c->outR[c->outRead];
            c->outRead++;
            producedPos++;
        }
    }
    // weitere fertig verfuegbare Frames aus dem Reservoir
    while (c->outWrite > c->outRead && producedPos < outCap) {
        out[2 * producedPos] = c->outL[c->outRead];
        out[2 * producedPos + 1] = c->outR[c->outRead];
        c->outRead++;
        producedPos++;
    }
    // Unterlauf (nur beim Start): Rest mit Nullen anfuellen
    if (producedPos < outCap && producedPos >= frames && outCap == frames) {
        memset(out + 2 * producedPos, 0,
               (outCap - producedPos) * 2 * sizeof(float));
    }
    outb->frameCount = outCap;
    return 0;
}

int32_t proc_command(effect_handle_t self, uint32_t cmd, uint32_t cmdSize,
                     void* cmdData, uint32_t* replySize, void* replyData) {
    ClearContext* c = (ClearContext*)self;
    (void)cmdSize;
    switch (cmd) {
    case EFFECT_CMD_INIT: {
        if (replyData == nullptr || *replySize < (uint32_t)sizeof(int32_t))
            return -EINVAL;
        *(int32_t*)replyData = 0;
        *replySize = sizeof(int32_t);
        return 0;
    }
    case EFFECT_CMD_SET_CONFIG: {
        if (cmdData == nullptr || cmdSize < sizeof(effect_config_t))
            return -EINVAL;
        effect_config_t* cfg = (effect_config_t*)cmdData;
        const buffer_config_t* ic = &cfg->inputCfg;
        c->bypass = true;
        c->configured = false;
        bool rateOk = (ic->samplingRate == 44100u ||
                       ic->samplingRate == 48000u);
        uint32_t mask = ic->channels;
        int nch = __builtin_popcount(mask & 0xFFFF);
        bool stereo = (mask == (uint32_t)AUDIO_CHANNEL_OUT_STEREO) ||
                      (nch == 2);
        bool floatOk = (static_cast<audio_format_t>(ic->format) ==
                       AUDIO_FORMAT_PCM_FLOAT) ||
                      (ic->mask & EFFECT_CONFIG_FORMAT) == 0;
        if (rateOk && stereo && floatOk) {
            bool newRate = (ic->samplingRate != c->sampleRate);
            if (newRate) {
                c->~ClearContext();
                new (c) ClearContext(ic->samplingRate);
                c->itfe = &kItfe;
            } else {
                c->resetStreams();
            }
            c->bypass = false;
            c->configured = true;
        }
        if (replyData != nullptr && replySize != nullptr &&
            *replySize >= sizeof(int32_t)) {
            *(int32_t*)replyData = 0;
            *replySize = sizeof(int32_t);
        }
        return 0;
    }
    case EFFECT_CMD_RESET:
    case EFFECT_CMD_ENABLE:
    case EFFECT_CMD_DISABLE:
    case EFFECT_CMD_SET_DEVICE:
    case EFFECT_CMD_SET_INPUT_DEVICE:
    case EFFECT_CMD_SET_AUDIO_MODE:
    case EFFECT_CMD_SET_AUDIO_SOURCE:
    case EFFECT_CMD_OFFLOAD: {
        if (replyData != nullptr && replySize != nullptr &&
            *replySize >= sizeof(int32_t)) {
            *(int32_t*)replyData = 0;
            *replySize = sizeof(int32_t);
        } else if (replySize != nullptr) {
            *replySize = 0;
        }
        return 0;
    }
    case EFFECT_CMD_SET_VOLUME: {
        // cmd: 2 x int32 (ml, mr); reply: 2 x int32 (skaliert). Wir skalieren
        // nicht: 1:1 zurueckmelden.
        if (replySize != nullptr && *replySize >= 2 * sizeof(int32_t) &&
            cmdData != nullptr) {
            memcpy(replyData, cmdData, 2 * sizeof(int32_t));
            *replySize = 2 * sizeof(int32_t);
        }
        return 0;
    }
    case EFFECT_CMD_SET_PARAM:
    case EFFECT_CMD_SET_PARAM_DEFERRED:
    case EFFECT_CMD_SET_PARAM_COMMIT: {
        if (replyData != nullptr && replySize != nullptr &&
            *replySize >= sizeof(int32_t)) {
            *(int32_t*)replyData = -EINVAL;
            *replySize = sizeof(int32_t);
        }
        return 0;
    }
    case EFFECT_CMD_GET_PARAM: {
        if (replyData == nullptr || replySize == nullptr) return -EINVAL;
        effect_param_t* p = (effect_param_t*)replyData;
        p->status = -ENOSYS;
        p->vsize = 0;
        return 0;
    }
    case EFFECT_CMD_GET_CONFIG: {
        // inputCfg als Reflektion melden (minimal)
        if (replyData == nullptr || replySize == nullptr ||
            *replySize < sizeof(effect_config_t)) return -EINVAL;
        effect_config_t* cfg = (effect_config_t*)replyData;
        memset(cfg, 0, sizeof(*cfg));
        cfg->inputCfg.samplingRate = c->sampleRate;
        cfg->inputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
        cfg->inputCfg.format = (uint8_t)AUDIO_FORMAT_PCM_FLOAT;
        cfg->outputCfg = cfg->inputCfg;
        *replySize = sizeof(effect_config_t);
        return 0;
    }
    case EFFECT_CMD_DUMP:
        return 0;
    default:
        if (replyData != nullptr && replySize != nullptr &&
            *replySize >= sizeof(int32_t)) {
            *(int32_t*)replyData = 0;
            *replySize = sizeof(int32_t);
        }
        return 0;
    }
}

int32_t proc_get_descriptor(effect_handle_t, effect_descriptor_t* d) {
    if (d == nullptr) return -EINVAL;
    *d = kDescriptor;
    return 0;
}

const struct effect_interface_s kItfe = {
    proc_process,
    proc_command,
    proc_get_descriptor,
    nullptr,  // process_reverse (nicht benoetigt)
};

int32_t lib_create(const effect_uuid_t* uuid, int32_t, int32_t,
                   effect_handle_t* handle) {
    if (uuid == nullptr || handle == nullptr) return -EINVAL;
    if (memcmp(uuid, &kImplUuid, sizeof(effect_uuid_t)) != 0 &&
        memcmp(uuid, &kLibUuid, sizeof(effect_uuid_t)) != 0) {
        return -ENOENT;
    }
    ClearContext* c =
        (ClearContext*)calloc(1, sizeof(ClearContext));
    if (c == nullptr) return -ENOMEM;
    new (c) ClearContext(48000u);
    c->itfe = &kItfe;
    *handle = (effect_handle_t)&c->itfe;
    return 0;
}

int32_t lib_release(effect_handle_t handle) {
    if (handle == nullptr || *handle == nullptr) return -EINVAL;
    // handle == &c->itfe == c (itfe ist erstes Member)
    ClearContext* c = (ClearContext*)handle;
    c->~ClearContext();
    free(c);
    return 0;
}

int32_t lib_get_descriptor(const effect_uuid_t* uuid,
                           effect_descriptor_t* d) {
    if (uuid == nullptr || d == nullptr) return -EINVAL;
    if (memcmp(uuid, &kImplUuid, sizeof(effect_uuid_t)) != 0)
        return -ENOENT;
    *d = kDescriptor;
    return 0;
}

} // namespace

// Einstiegspunkt, von EffectsFactory per dlsym geladen.
extern "C" {
struct audio_effect_library_s AELI = {
    AUDIO_EFFECT_LIBRARY_TAG,       // 'AELT'
    EFFECT_LIBRARY_API_VERSION_3_1,
    "ClearAudioFX",
    "tarvos/Dennis",
    lib_create,
    lib_release,
    lib_get_descriptor,
    nullptr,  // create_effect_3_1
};
}
