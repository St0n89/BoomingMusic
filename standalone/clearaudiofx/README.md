# ClearAudioFX standalone (systemweiter Effekt + Quick-Settings-Tile)

Vollstaendige, in einem Durchgang gebaute Loesung fuer: "ClearAudio+ systemweit
auf ALLE Apps wirken lassen, schaltbar per Schnelleinstellungen-Kachel (Tile)".

## Komponenten
- `engine/` - die RE-generated Full-Chain (Stage A + Analyse + Upcon P0/P1)
  als C++, 44,1 UND 48 kHz, bit-exakt gegen die Emulator-Fixtures validiert
  (je ok=4066 / dust=30 / FAIL=0 bei 16 Bloecken, L seed42 / R seed7,
  Kaltstart). `engine_test.cpp` ist die Abnahme, Fail = Build-Abbruch.
  Dazu `eq10.cpp` (v1.1): Eq10 UNIQUE (Firmware-Kernel 0x11b8f0, 10
  Peaking-Biquads, f64-Kern, f32-Zustandsraster an 64er-Blockgrenzen) +
  AntiClip v3.1 (-4.9 dB, LP-Split @9 kHz, Env-Deckel -31 dBFS, Exp-Knie).
  `eq10_test.cpp` nimmt den Eq10 bit-exakt gegen die aa64emu-Fixtures ab
  (96k + 88.2k, 3584/3584, gleiche Datenbasis wie die Kotlin-Abnahme).
- `effect/` - libclearaudiofx.so: systemweiter AudioEffect nach dem
  Legacy-HAL-Muster (EffectsFactory laedt via audio_effects.xml in
  audioserver). Echte AOSP-/LineageOS-Header vendoriert (lineage-22.2 =
  Android 15, passend zu /e/OS auf dem Fairphone 6). Eigene fixe UUIDs.
- `magisk/` - Magisk-Modul: Kopie der audio_effects.xml patchen (Overlay,
  Original bleibt unberuehrt), lib nach system/lib64/soundfx/. Der Installer
  erkennt /system/etc vs. /vendor/etc und erzeugt fehlende Sektionen.
- `tileapp/` - ClearAudioTile (minSdk 29, KEINE Abhaengigkeiten):
  Quick-Settings-Tile + Statusscreen, togelt persist.clearaudio.enabled
  via su (setprop). Der Effekt pollt die Property (~40 ms), Schalten wirkt
  sofort.
- `tools/` - Generatoren (Kotlin->C++ Transform der validierten Quellen,
  Fixture-Export). Generierte Dateien sind committed, Generatoren dienen
  der Reproduzierbarkeit.

## Der ehrliche Kompromiss (bitte lesen)
Die Sony-Kette ist ein 2x-Upsampler. Ein Session-0-Effekt darf die Rate des
Streams nicht aendern (in-place, feste frameCount). Deshalb: Chain intern
mit 2x, dann Polyphase-Dezimation zurueck (ROD_SA-Kern als Anti-Alias).
RE-abgeleitet, aber nicht bit-identisch mit dem Walkman (dort geht das 2x-
Signal direkt zum DAC). Bit-exakt validiert sind ENGINE (engine_test) und
EQ10 (eq10_test); AntiClip v3.1 ist ein Port der App-Referenz.

## Klang-Paritaet zur App (v1.1)
v1.0 lieferte nur die nackte Chain und klang dadurch heller/ohne Smile als
die App. v1.1 zieht auf dem internen 2x-Signal die gleiche Reihenfolge wie
ClearAudioAudioProcessor: Chain -> Eq10 UNIQUE (2x64er-Subblocks, 44.1-k-
Stream = COEFFS_882, 48-k = COEFFS_96K) -> AntiClip v3.1 -> Dezimation.
Uebergang beim Schalten: Raised-Cosine-Crossfade ueber 64 Frames zwischen
Wet und Dry (kein Knacks, kein Neustart des Tracks noetig).

## Abnahme/Lieferung (CI, `.github/workflows/build-clearaudiofx.yml`)
1. Engine-Abnahme nativ (clang++, -ffp-contract=off -fno-fast-math),
   inkl. Eq10-Fixtures: FAIL>0 bricht den Build ab.
2. Effect-Smoke nativ: create/config/enable/process(44.1+48k)/release.
3. libclearaudiofx.so cross (NDK r27c, aarch64, API 21).
4. Magisk-Modul-Zip (module.prop, customize.sh, lib).
5. Tile-APK (debug-signiert, installierbar).
Artefakte: clearaudiofx-magisk-module + clearaudiofx-tile-apk.

## Installation auf dem Geraet (FP6 / /e/OS)
1. Magisk-Modul-Zip installieren, Reboot.
2. Tile-APK installieren, Kachel in die Schnelleinstellungen legen.
3. Kachel antippen (Magisk-su erlauben), Musik spielen.

## Status / bekannte Rest-Risiken
- Engine + Eq10: bit-exakt validiert (beide Raten; Eq10 gegen echte
  Firmware-Fixtures, Python-Referenzreproduktion 3584/3584 vor dem Push).
- AntiClip v3.1: Port der App-Referenz (kein Firmware-Bitvergleich noetig,
  eigene v3.1-Designkennlinie).
- Effect-Logik: nativ gesmoke-testet (Integrationspfad create->release).
- audioserver-Integration auf dem Geraet: nicht vor Ort testbar von CI;
  XML-Patch gegen 3 Struktur-Varianten getestet (music vorhanden /
  postprocess ohne music / ganz ohne postprocess). Rueckmeldelog:
  `logcat | grep -i clearaudio`, `dumpsys audio | grep -i clearaudio`.
