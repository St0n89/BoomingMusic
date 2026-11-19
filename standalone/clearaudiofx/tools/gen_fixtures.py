#!/usr/bin/env python3
"""Fixture-Generator: fullchain_ref.pkl + fullchain_ref44.pkl -> fixtures.cpp.

Eingabe-Bits und Referenz-Outputs werden als int32-Bit-Muster eingebettet,
damit beim Test KEIN Float-Parsing ins Spiel kommt (decode-treu).
Erwartete Abnahme je Rate (wie fullchain_run.py / Kotlin-Main): ok=4066,
dust=30, FAIL=0 bei L seed42 / R seed7, Kaltstart-State komplett 0.
"""
import os
import pickle
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
QEMU = "/home/user/knowledge/clearaudio-re/tools/qemu"
OUT = os.path.normpath(os.path.join(HERE, "..", "engine"))


def bits_of(floats):
    return [struct.unpack("<i", struct.pack("<f", x))[0] for x in floats]


def load(name):
    with open(os.path.join(QEMU, name), "rb") as f:
        return pickle.load(f)


def carray(var, vals, per_line=12):
    rows = []
    for i in range(0, len(vals), per_line):
        rows.append("    " + ", ".join(str(v) for v in vals[i:i + per_line]) + ",")
    return f"static const int32_t {var}[] = {{\n" + "\n".join(rows) + "};\n"


def emit_rate(tag, ref, txt):
    inl = [bits_of(b) for b in ref["inl"]]
    inr = [bits_of(b) for b in ref["inr"]]
    outl = [bits_of(t[0]) for t in ref["outs"]]
    outr = [bits_of(t[1]) for t in ref["outs"]]
    n = len(inl)
    assert n == 16 and len(outl) == 16
    txt.append(f"// {tag}: {n} Bloecke, L seed42 / R seed7, sr={ref.get('sr', 48000)}")
    txt.append(carray(f"_{tag}_INL", sum(inl, [])).rstrip("\n"))
    txt.append(carray(f"_{tag}_INR", sum(inr, [])).rstrip("\n"))
    txt.append(carray(f"_{tag}_OUTL", sum(outl, [])).rstrip("\n"))
    txt.append(carray(f"_{tag}_OUTR", sum(outr, [])).rstrip("\n"))
    txt.append("")
    txt.append(f"const ChainFixtures {tag} = {{")
    txt.append(f"    .blocks = {n},")
    for nm in ("INL", "INR", "OUTL", "OUTR"):
        txt.append(f"    .{nm} = _{tag}_{nm},")
    txt.append("};")
    txt.append("")


def main():
    txt = []
    txt.append("// AUTO-GENERIERT von gen_fixtures.py aus fullchain_ref.pkl /")
    txt.append("// fullchain_ref44.pkl (Emulator-Referenz, 16 Bloecke).")
    txt.append("// Werte = int32-Bit-Muster, NIEMALS als Float-Literal parsen!")
    txt.append("#include \"fixtures.h\"")
    txt.append("")
    txt.append("namespace clearaudio {")
    txt.append("")
    emit_rate("FX48", load("fullchain_ref.pkl"), txt)
    emit_rate("FX44", load("fullchain_ref44.pkl"), txt)
    txt.append("} // namespace clearaudio")
    with open(os.path.join(OUT, "fixtures.cpp"), "w", encoding="utf-8") as f:
        f.write("\n".join(txt) + "\n")
    print("OK: fixtures.cpp erzeugt (" + str(len(txt)) + " Zeilen-Kopf)")


if __name__ == "__main__":
    main()
