// Eq10 (HQEQ_10Band, UNIQUE-Preset) + AntiClip v3.1 - C++-Port der
// App-Kette (Eq10.kt / ClearAudioAudioProcessor.kt, Phase 3B/3f).
//
// Eq10: 1:1-Port des Firmware-Kernels 0x11b8f0/Pfad B (audio.primary.
// icx1293.so 4.04). Bit-exakt gegen die Emulator-Fixtures (96k UND
// 88.2k, 3584/3584, eq10_test.cpp als CI-Gate):
//   out = dry + SUM band_b, b = 0..9 (10 parallele Peaking-Biquads)
//   d21 = (b1*x1 + b2*x2) + b0*x
//   d7  = a2*y2 + d21
//   y   = a1*y1 + d7            (Ausgabe je Sample f32-gerundet)
//   Zustaende x1/x2/y1/y2 nur an 64er-Blockgrenzen f32-persistiert,
//   intern f64 weitergefuehrt (str/ldr-Raster des Kernels).
//   Final: Hardclip > +1.0 / < -1.0 (f32-Vergleich).
//   env-Stufe 0x12e6a4 im UNIQUE-Preset empirisch exakt 1.0 =>
//   bewusst als x1.0 vereinfacht (KNOWLEDGE 303-(5)).
// Koeffizienten: Sony-SE-Tabelle 0x16d658, UNIQUE-Sets als Bitmuster
// (memcpy statt Dezimal-Literale, KNOWLEDGE 289-Gotcha). 44.1 kHz
// Stream = COEFFS_882 (2x = 88.2 kHz), 48 kHz Stream = COEFFS_96K.
//
// AntiClip v3.1: App-Referenz aus ClearAudioAudioProcessor (companion):
// -9 dB Vorabsenkung, LP-Split @9 kHz, adaptiver HF-Gain gegen den
// Env-Deckel T=-31 dBFS (Attack 10 ms / Release 150 ms), dann das
// saturierende Exp-Knie ab 0.9 mit Asymptote 1.0. Laeuft auf dem 2x-
// Signal wie in der App (initAntiClipState(2 * sampleRate)).
#pragma once
#include <stdint.h>
#include <stddef.h>

namespace clearaudio {

// 10 Baender x { b0, b1, b2, a1, a2 } als IEEE-f32-Bitmuster.
extern const uint32_t kEq10Coeffs882[10][5];
extern const uint32_t kEq10Coeffs96K[10][5];

inline const uint32_t (*eq10_coeffs_for_rate(uint32_t sample_rate))[5] {
    return (sample_rate == 44100u) ? kEq10Coeffs882 : kEq10Coeffs96K;
}

class Eq10Channel {
public:
    explicit Eq10Channel(const uint32_t (*coeff_bits)[5]);

    // Kaltstart: alle Biquad-Zustaende auf 0 (APK-Bootfall).
    void reset();

    // 64 Samples in-place: trocken + Baender, final Hardclip +-1.0.
    void processBlock64(float* samples);

private:
    static const int kNumBands = 10;
    static const int kBlock = 64;

    double b0_[kNumBands];
    double b1_[kNumBands];
    double b2_[kNumBands];
    double a1_[kNumBands];
    double a2_[kNumBands];
    // Biquad-Zustaende je Band (f32-persistiert an Blockgrenzen)
    float sx1_[kNumBands];
    float sx2_[kNumBands];
    float sy1_[kNumBands];
    float sy2_[kNumBands];
    float band_out_[kNumBands][kBlock];
};

class AntiClipChannel {
public:
    AntiClipChannel() : lp_(0.0f), env_(0.0f), ready_(false) {}

    void init(uint32_t out_rate);
    void reset() { lp_ = 0.0f; env_ = 0.0f; }
    bool ready() const { return ready_; }

    float process(float v);

private:
    static const float kScale;   // 0.5677014f  (-4.9 dB)
    static const float kKnee;    // 0.9f
    static const float kT;       // 0.04509413f (-31 dBFS-Deckel)
    static const float kCurve;   // 12.5f

    float lp_;
    float env_;
    float lp_a_;
    float att_;
    float rel_;
    bool ready_;
};

} // namespace clearaudio
