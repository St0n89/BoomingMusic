ClearAudioFX (systemweit) - tarvos/Dennis RE-Projekt Sony ClearAudio+ / Walkman NW-A105 RE
======================================================================================

Was es ist
----------
- libclearaudiofx.so: systemweiter AudioEffect (legacy HAL, EffectsFactory-ladbar),
  laedt in audioserver, verarbeitet STREAM_MUSIC (output mix, float stereo,
  44,1 kHz oder 48 kHz) mit der RE-generated Full-Chain der Sony-Firmware:
  Stage A (25er-Polyphase-2x-FIR), Gain, DC-Notch-Biquad, D-FIR-Analyse,
  Upcon P0/P1. Engine bit-exakt gegen Emulator-Fixtures beider Raten
  (je ok=4066 / dust=30 / FAIL=0, 16 Bloecke).
- WICHTIG (ehrliche Technikkunde): die Chain ist grundsaetzlich ein
  2x-Upsampler. Session-0-Effekte duerfen die Stream-Rate NICHT aendern
  (in-place, gleiche frameCount). Systemweite Variante: Chain laeuft intern
  mit 2x, danach Polyphase-Dezimation zurueck auf 1x (ROD_SA als Anti-Alias).
  RE-abgeleitet, aber NICHT bit-identisch mit dem Walkman-Verhalten (dort
  geht 2x direkt zum DAC). Bit-exakt validiert ist die ENGINE (siehe
  engine/), nicht der Systemausschnitt.
- Schalter: System-Property persist.clearaudio.enabled ('1'/'0'), Effekt pollt
  ~alle 40 ms. Standard: AUS.

Voraussetzungen
---------------
- Root (Magisk), getestet-target: Fairphone 6 mit /e/OS (aarch64)
- Tile-APK (ClearAudioTile) installieren fuer Quick-Settings-Kachel

Installation (Magisk)
--------------------
1. Modul-Zip in Magisk installieren. customize.sh patcht eine Kopie der
   systemischen audio_effects.xml (/system/etc ODER /vendor/etc, wird
   erkannt) via Magic-Mount-Overlay. Original bleibt unberuehrt.
2. Reboot. audioserver laedt libclearaudiofx.so beim Start.
3. Tile-APK installieren, Kachel "ClearAudio+" in die Schnelleinstellungen
   legen, einmal tippen (Magisk-su-Dialog bestätigen). Erneutes Tippen
   schaltet ein/aus, sofort wirksam.

Fehlersuche
-----------
- logcat -b all | grep -i clearaudio (Effekt selbst loggt minimal)
- dumpsys media.audio_fader / audio policy: pruefen ob Effekt geladen:
  dumpsys audio | grep -A2 clearaudio
- Tile zeigt "NICHT VERFUEGBAR": kein su (Magisk) oder su verweigert.
- Ohne music-Stream in <postprocess> erzeugt der Installer einen minimalen.

Entfernen
---------
- Magisk: Modul entfernen, Reboot. Overlay weg, Systemdatei unberuehrt.
- Tile-APK normal deinstallieren.

Rechtliches / Herkunft
----------------------
- Effekt-Implementierung: Reverse Engineering der Sony-Firmware
  (Walkman NW-A105 Android, audio.primary ICX1293 ClearAudio+ DSEE),
  Projekt von Dennis Kunde (tarvos). Nur fuer eigene Geraete.
- AOSP-/Lineage-Header (audio_effect.h etc.) unter Apache-2.0, vendorisiert
  aus LineageOS-Repos (lineage-22.2 = Android 15, passend zu /e/OS FP6).
