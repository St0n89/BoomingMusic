#!/system/bin/sh
# ClearAudioFX-Modul-Installer.
# 1. findet audio_effects.xml (/system/etc oder /vendor/etc)
# 2. legt eine GEPATCHTE Kopie als Overlay ins Modul (Magic Mount,
#    Original unberuehrt)
# 3. legt libclearaudiofx.so nach system/lib64/soundfx/ (Modul)
# Patch: library + effect in die jeweiligen Sektionen und <apply> in den
# music-Stream unter <postprocess>. Falls kein music-Stream existiert,
# wird er erzeugt.
SKIPUNZIP=0

MODDIR=$MODPATH
UUID="e8ed75b5-d5cb-5d8f-8e86-fbc541791950"
LIBNAME="libclearaudiofx"

ui_print "  ClearAudioFX: Installer"

# ---- audio_effects.xml finden ----
SRC_XML=""
DST_SUB=""
for cand in /system/etc/audio_effects.xml /vendor/etc/audio_effects.xml; do
  if [ -f "$cand" ]; then
    SRC_XML="$cand"
    DST_SUB="system/etc/audio_effects.xml"
    case "$cand" in
      /vendor/*) DST_SUB="system/vendor/etc/audio_effects.xml" ;;
    esac
    break
  fi
done

if [ -z "$SRC_XML" ]; then
  abort "! keine audio_effects.xml unter /system/etc oder /vendor/etc gefunden"
fi
ui_print "  Quelle: $SRC_XML"

# ---- lib kopieren ----
mkdir -p "$MODDIR/system/lib64/soundfx"
if [ -f "$MODPATH/libclearaudiofx.so" ]; then
  cp "$MODPATH/libclearaudiofx.so" "$MODDIR/system/lib64/soundfx/$LIBNAME.so"
elif [ -f "$ZIPFILE" ]; then
  # libclearaudiofx.so liegt im Zip-Root neben customize.sh
  unzip -o "$ZIPFILE" "libclearaudiofx.so" -d "$MODDIR" >/dev/null 2>&1
  cp "$MODDIR/libclearaudiofx.so" "$MODDIR/system/lib64/soundfx/$LIBNAME.so" \
    2>/dev/null || true
fi
if [ ! -f "$MODDIR/system/lib64/soundfx/$LIBNAME.so" ]; then
  abort "! libclearaudiofx.so nicht im Modul gefunden"
fi
chmod 0644 "$MODDIR/system/lib64/soundfx/$LIBNAME.so"
set_perm "$MODDIR/system/lib64/soundfx/$LIBNAME.so" 0 0 0644 u:object_r:system_lib_file:s0 2>/dev/null || true

# ---- XML patchen ----
OUT_XML="$MODDIR/$DST_SUB"
mkdir -p "$(dirname "$OUT_XML")"

awk -v uuid="$UUID" -v lib="$LIBNAME" '
BEGIN { inpost = 0; inmusic = 0; seenmusic = 0; ineffect = 0; inlib = 0; sawpost = 0; }
{
  line = $0
  # <libraries> merken
  if (line ~ /<libraries>/) { inlib = 1 }
  if (line ~ /<\/libraries>/) {
    print "    <library name=\"" lib "\" path=\"" lib ".so\"/>"
    inlib = 0
    print line; next
  }
  # <effects>
  if (line ~ /<effects>/) { ineffect = 1 }
  if (line ~ /<\/effects>/) {
    print "    <effect name=\"" lib "\" library=\"" lib "\" uuid=\"" uuid "\"/>"
    ineffect = 0
    print line; next
  }
  # <postprocess> Bereich
  if (line ~ /<postprocess>/) { inpost = 1; sawpost = 1 }
  if (line ~ /<\/postprocess>/) {
    if (seenmusic == 0 && inpost == 1) {
      print "  <stream type=\"music\">"
      print "    <apply effect=\"" lib "\"/>"
      print "  </stream>"
    }
    inpost = 0
    print line; next
  }
  if (inpost == 1) {
    if (line ~ /<stream type="music">/) { inmusic = 1; seenmusic = 1; print line; next }
    if (line ~ /<stream type=/ && inmusic == 0) {
      # anderer Stream: unverändert
      print line; next
    }
    if (inmusic == 1) {
      if (line ~ /<\/stream>/) {
        print "    <apply effect=\"" lib "\"/>"
        inmusic = 0
        print line; next
      }
      if (line ~ /<apply /) {
        # bestehende applies behalten
        print line; next
      }
      print line; next
    }
    print line; next
  }
  # Dateiende ohne postprocess: komplett anhaengen
  if (line ~ /<\/audio_effects>/ && sawpost == 0) {
    print "  <postprocess>"
    print "    <stream type=\"music\">"
    print "      <apply effect=\"" lib "\"/>"
    print "    </stream>"
    print "  </postprocess>"
  }
  print line
}
' "$SRC_XML" > "$OUT_XML"

# Plausibilitaet: unsere Eintraege muessen drin sein
if ! grep -q "\"$LIBNAME\"" "$OUT_XML" || ! grep -q "$UUID" "$OUT_XML"; then
  abort "! Patch der audio_effects.xml fehlgeschlagen (Struktur unbekannt)"
fi
chmod 0644 "$OUT_XML"
set_perm "$OUT_XML" 0 0 0644 u:object_r:system_file:s0 2>/dev/null || true

ui_print "  Overlay: $DST_SUB (Original bleibt unberuehrt)"
ui_print "  lib: system/lib64/soundfx/$LIBNAME.so"
ui_print "  "
ui_print "  WICHTIG:"
ui_print "  - Reboot noetig (audioserver liest audio_effects.xml beim Start)"
ui_print "  - Effekt ist standardmaessig AUS; Tile-APK installieren und"
ui_print "    Kachel (oder App) zum Umschalten verwenden (persist.clearaudio.enabled)"
ui_print "  - Erwartung: nur STREAM_MUSIC @ 44,1/48 kHz float stereo wird"
ui_print "    verarbeitet, alles andere laeuft unbearbeitet durch"
