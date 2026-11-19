// AUTO-GENERIERT-Schnittstelle: engine_gen.cpp stellt nur diese drei
// Funktionen bereit (Implementierung in engine_gen.cpp, Namespace cleanaudio).
#pragma once

namespace clearaudio {

void stageA(const float* inArr, float* fin, float* s, const float* rodSA);
void upconP0(float* fin, const float* dBank, float* s, float* stack,
             const float* K, const float* rodP1);
void upconP1(float* fin, const float* dBank, float* s, float* stack,
             const float* K, const float* rodP1);

} // namespace clearaudio
