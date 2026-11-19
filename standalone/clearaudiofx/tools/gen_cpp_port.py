#!/usr/bin/env python3
"""Generator: C++-Port der validierten Kotlin-Full-Chain (Stage A + Upcon P0/P1).

Quellen (valide 289-294, bitweise gegen Emulator-Fixtures):
  FullStageA.kt / FullUpcon.kt   -> engine_gen.cpp (regulaer, maschinell)
  FullTables.kt / FC44Tables.kt  -> fc_tables.cpp (Bits 1:1, keine Float-Parser)

Ausgabe ist decode-treu (nur float a op b, kein FMA). Bauen mit
  -ffp-contract=off -fno-fast-math
"""
import os
import re

SRC = "/home/user/knowledge/clearaudio-re/ClearAudioFullKotlin/src"
OUT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "engine"))
CSE = None  # wird in main gesetzt (Codierung siehe unten: ASCII-only)


def read(name):
    with open(os.path.join(SRC, name), encoding="utf-8") as f:
        return f.read().splitlines()


def transform_fn(lines, fn_name, ret_decl):
    """Kotlin-Funktionskoerper (Subset der generierten Dateien) nach C++."""
    out = []
    started = False
    depth = 0
    for ln in lines:
        if not started:
            if ln.startswith(f"fun {fn_name}("):
                started = True
                out.append(ret_decl + " {")
                depth = 1
            continue
        depth += ln.count("{") - ln.count("}")
        t = ln
        t = re.sub(r"java\.util\.Arrays\.fill\(s, 0f\)",
                   "for (int _z = 0; _z < 32; _z++) s[_z] = 0.0f;", t)
        t = re.sub(r"java\.util\.Arrays\.fill\(stack, 0f\)",
                   "for (int _z = 0; _z < 16; _z++) stack[_z] = 0.0f;", t)
        m = re.match(r"(\s*)for \((\w+) in (\d+)\.\.(\d+)\) \{\s*$", t)
        if m:
            ind, v, a, b = m.groups()
            t = f"{ind}for (int {v} = {a}; {v} <= {b}; {v}++) {{"
        t = re.sub(r"(?<![A-Za-z0-9_.])(\d+)f\b", r"\1.0f", t)
        # Kotlin braucht keine Semikolons, C++ schon.
        st = t.strip()
        if st and not st.endswith(("{", "}", ";")) and not st.startswith("//"):
            t += ";"
        out.append(t)
        if depth == 0:
            break
    assert out and out[-1].strip() == "}", f"{fn_name}: Ende nicht gefunden"
    return out


def gen_engine():
    sa = read("FullStageA.kt")
    up = read("FullUpcon.kt")
    body = []
    body += transform_fn(
        sa, "stageA",
        "void stageA(const float* inArr, float* fin, float* s, "
        "const float* rodSA)")
    body.append("")
    body += transform_fn(
        up, "upconP0",
        "void upconP0(float* fin, const float* dBank, float* s, "
        "float* stack, const float* K, const float* rodP1)")
    body.append("")
    body += transform_fn(
        up, "upconP1",
        "void upconP1(float* fin, const float* dBank, float* s, "
        "float* stack, const float* K, const float* rodP1)")
    txt = (
        "// AUTO-GENERIERT von gen_cpp_port.py aus FullStageA.kt / "
        "FullUpcon.kt (valide 289-294).\n"
        "// NICHT MANUELL AENDERN. f32-Semantik: mit\n"
        "// -ffp-contract=off -fno-fast-math bauen.\n"
        "#include \"engine_gen.h\"\n\n"
        "namespace clearaudio {\n\n"
        + "\n".join(body) +
        "\n\n} // namespace clearaudio\n")
    with open(os.path.join(OUT, "engine_gen.cpp"), "w",
              encoding="utf-8") as f:
        f.write(txt)


def parse_fields(name):
    """FullTables.kt / FC44Tables.kt parsen: liefert name -> [int32 Bits]."""
    lines = read(name)
    fields = {}
    cur = None
    buf = []
    scalar = False
    for ln in lines + [")"]:
        if cur is None:
            m = re.match(r"\s*@JvmField val (\w+) = (.*)$", ln)
            if not m:
                continue
            header = m.group(2)
            if "Float.fromBits" not in header and "floatArrayOf" not in header:
                continue
            if "fromBits" not in header and "floatArrayOf" in header:
                # Liste beginnt auf eigener Zeile: nur Oeffnung
                cur = m.group(1)
                fields[cur] = []
                buf = []
                continue
            cur = m.group(1)
            scalar = "floatArrayOf" not in header
            fields[cur] = []
            if scalar:
                mm = re.search(r"Float\.fromBits\((-?\d+)\)", header)
                fields[cur] = [int(mm.group(1))]
                cur = None
                continue
            buf = [header.split("floatArrayOf(", 1)[1]]
            if "".join(buf).rstrip().endswith(")"):
                fields[cur] = [int(x) for x in re.findall(
                    r"Float\.fromBits\((-?\d+)\)", "".join(buf))]
                cur = None
            continue
        buf.append(ln)
        if "".join(buf).rstrip().endswith(")"):
            body = "".join(buf)
            fields[cur] = [int(x) for x in re.findall(
                r"Float\.fromBits\((-?\d+)\)", body)]
            cur = None
    exp = {"GAIN", "BQ_A", "BQ_B", "BQ_M2", "BQ_ONE", "K65", "TAPS",
           "ROD_SA", "ROD_P1"}
    assert set(fields) == exp, (name, set(fields))
    return fields


import struct


def hexf(x):
    """Exakter Hex-Float-Literal (C99), kein Parser-Rundungsrisiko."""
    if x == 0.0:
        return "0x0.0p+0f" if not (struct.pack("<f", x) == struct.pack("<f", -0.0)) else "-0x0.0p+0f"
    h = float(x).hex()  # '0x1.8p+1' / '-0x1.8p+1'
    return h + "f" if "p" in h else h


def float_of_bits(b):
    return struct.unpack("<f", struct.pack("<i", b))[0]


BITARRS = ("ROD_SA", "ROD_P1", "TAPS", "K65")


def bitblock(varname, bits, per_line=8):
    """bits = int32-Bit-Muster; Ausgabe: exakte Hex-Float-Arrays."""
    vals = [hexf(float_of_bits(b)) for b in bits]
    rows = []
    for i in range(0, len(vals), per_line):
        rows.append("    " + ", ".join(vals[i:i + per_line]) + ",")
    return f"static const float {varname}[] = {{\n" + "\n".join(rows) + "};\n"


def gen_tables():
    t48 = parse_fields("FullTables.kt")
    t44 = parse_fields("FC44Tables.kt")
    txt = []
    txt.append("// AUTO-GENERIERT von gen_cpp_port.py aus FullTables.kt /")
    txt.append("// FC44Tables.kt. Floats als Bit-Muster (Float.fromBits),")
    txt.append("// KEINE Rundung irgendeines Parsers!")
    txt.append("#include \"fc_tables.h\"")
    txt.append("")
    txt.append("namespace clearaudio {")
    txt.append("")
    for tag, tbl in (("FC48", t48), ("FC44", t44)):
        for k in BITARRS:
            txt.append(bitblock(f"_{tag}_{k}", tbl[k]).rstrip("\n"))
        txt.append("")
    for tag, tbl in (("FC48", t48), ("FC44", t44)):
        txt.append(f"const FcTables {tag} = {{")
        for k in ("GAIN", "BQ_A", "BQ_B", "BQ_M2", "BQ_ONE"):
            txt.append(f"    .{k} = {hexf(float_of_bits(tbl[k][0]))},")
        for k in BITARRS:
            txt.append(f"    .{k} = _{tag}_{k},")
        txt.append("};")
        txt.append("")
    txt.append("} // namespace clearaudio")
    with open(os.path.join(OUT, "fc_tables.cpp"), "w",
              encoding="utf-8") as f:
        f.write("\n".join(txt) + "\n")


if __name__ == "__main__":
    gen_engine()
    gen_tables()
    print("OK: engine_gen.cpp + fc_tables.cpp erzeugt in " + OUT)
