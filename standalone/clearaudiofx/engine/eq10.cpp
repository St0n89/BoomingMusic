// Eq10 + AntiClip v3.1 - Implementierung (Port siehe eq10.h).
#include "eq10.h"
#include <string.h>
#include <math.h>

namespace clearaudio {

// UNIQUE-Sets der Sony-SE-Tabelle 0x16d658 als IEEE-f32-Bitmuster
// (identisch zu Eq10.kt: Float.fromBits(...); Quelle KNOWLEDGE 303/304).
const uint32_t kEq10Coeffs882[10][5] = {
    {972027340u, 0u, (uint32_t)-1175456308, 1073734068u, (uint32_t)-1082145863},
    {980408681u, 0u, (uint32_t)-1167074967, 1073726237u, (uint32_t)-1082161279},
    {983096497u, 0u, (uint32_t)-1164387151, 1073701731u, (uint32_t)-1082209291},
    {978425748u, 0u, (uint32_t)-1169057900, 1073629247u, (uint32_t)-1082350299},
    {979043440u, 0u, (uint32_t)-1168440208, 1073494772u, (uint32_t)-1082603554},
    {1006832546u, 0u, (uint32_t)-1140651102, 1073374816u, (uint32_t)-1082780994},
    {1007661158u, 0u, (uint32_t)-1139822490, 1072799176u, (uint32_t)-1083691555},
    {(uint32_t)-1127373589, 0u, 1020110059u, 1071449266u, (uint32_t)-1085498282},
    {(uint32_t)-1118764732, 0u, 1028718916u, 1068622261u, (uint32_t)-1087992934},
    {1049358030u, 0u, (uint32_t)-1098125618, 1058744580u, (uint32_t)-1092170409},
};
const uint32_t kEq10Coeffs96K[10][5] = {
    {970750141u, 0u, (uint32_t)-1176733507, 1073734700u, (uint32_t)-1082144609},
    {979132618u, 0u, (uint32_t)-1168351030, 1073727515u, (uint32_t)-1082158775},
    {982284315u, 0u, (uint32_t)-1165199333, 1073705031u, (uint32_t)-1082202898},
    {977316538u, 0u, (uint32_t)-1170167110, 1073638536u, (uint32_t)-1082332544},
    {977892772u, 0u, (uint32_t)-1169590876, 1073515361u, (uint32_t)-1082565625},
    {1005663497u, 0u, (uint32_t)-1141820151, 1073407181u, (uint32_t)-1082729156},
    {1006933262u, 0u, (uint32_t)-1140550386, 1072884228u, (uint32_t)-1083570884},
    {(uint32_t)-1128342767, 0u, 1019140881u, 1071660578u, (uint32_t)-1085256090},
    {(uint32_t)-1119604115, 0u, 1027879533u, 1069111129u, (uint32_t)-1087633676},
    {1049042595u, 0u, (uint32_t)-1098441053, 1060904538u, (uint32_t)-1091536536},
};

Eq10Channel::Eq10Channel(const uint32_t (*coeff_bits)[5]) {
    for (int b = 0; b < kNumBands; b++) {
        float f;
        memcpy(&f, &coeff_bits[b][0], 4); b0_[b] = (double)f;
        memcpy(&f, &coeff_bits[b][1], 4); b1_[b] = (double)f;
        memcpy(&f, &coeff_bits[b][2], 4); b2_[b] = (double)f;
        memcpy(&f, &coeff_bits[b][3], 4); a1_[b] = (double)f;
        memcpy(&f, &coeff_bits[b][4], 4); a2_[b] = (double)f;
    }
    reset();
}

void Eq10Channel::reset() {
    memset(sx1_, 0, sizeof(sx1_));
    memset(sx2_, 0, sizeof(sx2_));
    memset(sy1_, 0, sizeof(sy1_));
    memset(sy2_, 0, sizeof(sy2_));
}

void Eq10Channel::processBlock64(float* samples) {
    for (int b = 0; b < kNumBands; b++) {
        float* ob = band_out_[b];
        // f32-Zustaende laden, intern f64 weiterfuehren
        double x1 = (double)sx1_[b];
        double x2 = (double)sx2_[b];
        double y1 = (double)sy1_[b];
        double y2 = (double)sy2_[b];
        const double bb0 = b0_[b], bb1 = b1_[b], bb2 = b2_[b];
        const double ba1 = a1_[b], ba2 = a2_[b];
        for (int n = 0; n < kBlock; n++) {
            const double x = (double)samples[n];
            const double d21 = (bb1 * x1 + bb2 * x2) + bb0 * x;
            const double d7 = ba2 * y2 + d21;
            const double y = ba1 * y1 + d7;
            ob[n] = (float)y;
            x2 = x1; x1 = x;
            y2 = y1; y1 = y;
        }
        // f32-Persistenz an der 64er-Grenze (str/ldr-Raster des Kernels)
        sx1_[b] = (float)x1;
        sx2_[b] = (float)x2;
        sy1_[b] = (float)y1;
        sy2_[b] = (float)y2;
    }
    // dry + Baender als f32-Adds (keine FMA, Oporder wie der Kernel),
    // final Hardclip +-1.0 (f32-Vergleich).
    for (int n = 0; n < kBlock; n++) {
        float acc = samples[n];
        for (int b = 0; b < kNumBands; b++) {
            acc += band_out_[b][n];
        }
        if (acc > 1.0f) acc = 1.0f;
        if (acc < -1.0f) acc = -1.0f;
        samples[n] = acc;
    }
}

// ---------- AntiClip v3.1 ----------

const float AntiClipChannel::kScale = 0.5677014f;
const float AntiClipChannel::kKnee = 0.9f;
const float AntiClipChannel::kT = 0.04509413f;
const float AntiClipChannel::kCurve = 12.5f;

void AntiClipChannel::init(uint32_t out_rate) {
    // Konstanten wie Kotlin: double-Rechnung, dann .toFloat()
    const double r = (double)out_rate;
    lp_a_ = (float)(1.0 - exp(-2.0 * 3.141592653589793 * 9000.0 / r));
    att_ = (float)exp(-1.0 / (0.010 * r));
    rel_ = (float)exp(-1.0 / (0.150 * r));
    reset();
    ready_ = true;
}

float AntiClipChannel::process(float v) {
    const float x = v * kScale;
    const float lp_s = lp_;
    const float lp = lp_s + lp_a_ * (x - lp_s);
    lp_ = lp;
    const float hf = x - lp;
    const float a = fabsf(hf);
    const float e = env_;
    const float env = (a > e)
        ? (att_ * e + (1.0f - att_) * a)
        : (rel_ * e + (1.0f - rel_) * a);
    env_ = env;
    const float g = fminf(1.0f, kT / fmaxf(1e-9f, env));
    const float y = lp + g * hf;
    if (y > kKnee) {
        const float ex = y - kKnee;
        // kotlin.math.exp(Float) = Math.exp(double).toFloat()
        return kKnee + (1.0f - kKnee) *
               (1.0f - (float)exp((double)(-ex * kCurve)));
    }
    if (y < -kKnee) {
        const float ex = -y - kKnee;
        return -(kKnee + (1.0f - kKnee) *
                 (1.0f - (float)exp((double)(-ex * kCurve))));
    }
    return y;
}

} // namespace clearaudio
