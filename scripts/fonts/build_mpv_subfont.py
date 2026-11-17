#!/usr/bin/env python3
"""Build app/src/main/assets/subfont.ttf, mpv's last-resort subtitle font (Spec 98 S2, D6).

mpv's libass has no system font provider on Android (no fontconfig in libmpv), so every glyph it
cannot find in an ASS style font or attachment comes from ~~/subfont.ttf. mpv-android-lib ships a
Droid Sans Fallback merge that lacks 13 scripts; this script merges Noto Sans Regular for them into
it. The app's asset overrides the AAR's, and is.xyz.mpv.Utils.copyAssets re-copies it into
filesDir whenever its size differs.

Steps: scale each Noto font to the base's unitsPerEm (256), merge (fontTools.merge), then point
every GSUB/GPOS/GDEF reference to a Noto font's own ZWJ / ZWNJ / dotted-circle glyph at the base
glyph the cmap maps (the merger renames duplicates, so without this Malayalam chillus and Sinhala
ZWJ forms such as "sri" stop shaping), restore the base's vertical metrics and timestamps, state
both licences in the name table, then check HarfBuzz shaping of every added script against its
source font and of the base's own scripts against the base. Output is written only after the check
passes, and is byte-reproducible with the same inputs and tool versions.

Built 2026-11-13 with fontTools 4.63.0 and uharfbuzz 0.57.0 (other versions may rename glyphs or
choose other table formats: rebuild, rerun the check, and compare). Bumping mpv-android-lib: the
base sha256 below stops the build; rebuild from the new AAR's subfont.ttf and update the pin.

Run with fontTools and uharfbuzz available:
  python3 scripts/fonts/build_mpv_subfont.py <mpv-android-lib subfont.ttf> <out.ttf>
"""
import hashlib
import io
import shutil
import re
import sys
import tempfile
import urllib.request
from pathlib import Path

from fontTools.merge import Merger, Options
from fontTools.ttLib import TTFont
from fontTools.ttLib.scaleUpem import scale_upem

# assets/subfont.ttf of io.github.abdallahmehiz:mpv-android-lib:0.1.12.
BASE_SHA256 = "75048bc128cdaf5f840814717aef634c001a1e8613c3298415d295e30e132ca2"
NOTO_PIN = "3460ae9d93e7ac7a90d38a247d5d4d0f6147d239"  # notofonts/notofonts.github.io
NOTO_URL = "https://raw.githubusercontent.com/notofonts/notofonts.github.io/{pin}/fonts/NotoSans{s}/unhinted/ttf/NotoSans{s}-Regular.ttf"

# Script -> sample text checked after the merge (conjuncts, ZWJ forms, stacked marks).
SCRIPTS = {
    "Bengali": "ক্ষমা বাংলা ভাষা স্ত্রী র‍্যাব",
    "Telugu": "తెలుగు భాష క్ష్మ శ్రీ",
    "Kannada": "ಕನ್ನಡ ಭಾಷೆ ಕ್ಷ್ಮ ರ‍್ಯ",
    "Malayalam": "മലയാളം ഭാഷ ക്ഷ്മ അവന്‍ കല്‍",
    "Gujarati": "ગુજરાતી ભાષા ક્ષ ક્‌ષ",
    "Gurmukhi": "ਪੰਜਾਬੀ ਭਾਸ਼ਾ ਕ੍ਰਿ",
    "Sinhala": "සිංහල භාෂාව ශ්‍රී ක්‍ය",
    "Lao": "ພາສາລາວ ສະບາຍດີ",
    "Khmer": "ភាសាខ្មែរ ស្រ្តី",
    "Myanmar": "မြန်မာဘာသာ ကျွန်ုပ်",
    "Armenian": "Հայերեն լեզու",
    "Georgian": "ქართული ენა",
    "Ethiopic": "አማርኛ ቋንቋ",
}
# Scripts the base already covers: their shaping must not change at all.
BASE_SAMPLES = {
    "Latin": "Cô đưa một chai nước",
    "Devanagari": "हिन्दी भाषा क्षत्रिय",
    "Arabic": "اللغة العربية",
    "Thai": "ภาษาไทย",
    "Tamil": "தமிழ் மொழி",
    "Hebrew": "עברית",
    "CJK": "你好世界 こんにちは 안녕",
    "Latin NFD": "Co\u0302 \u0111u\u031Ba mo\u0323\u0302t",
    "Arabic marks": "\u0628\u064E\u064A\u0652\u062A\u064C",
    "Hebrew marks": "\u05E9\u05C1\u05B8\u05DC\u05D5\u05B9\u05DD",
    "Thai marks": "\u0E19\u0E49\u0E33 \u0E01\u0E35\u0E48",
    "Digits, punctuation": "0123456789 .,:;!?-()'\" \u2014 12:34 \u00A0\u2026",
}

# Copied from the base: text size and line height (libass), and the regular/bold bits (fsSelection).
BASE_METRICS = {
    "hhea": ("ascent", "descent", "lineGap"),
    "OS/2": ("usWinAscent", "usWinDescent", "sTypoAscender", "sTypoDescender", "sTypoLineGap", "fsSelection"),
}

_JOINER_REF = re.compile(
    r'(?<=["\',])(?:(uni200C)\.\d+|(uni200D)\.\d+|(uni25CC)\.\d+'
    r'|(zerowidthnonjoiner)(?:\.\d+)?|(zerowidthjoiner)(?:\.\d+)?|(dotted[cC]ircle)(?:\.\d+)?)(?=["\',])'
)
_JOINER_TARGET = {1: "uni200C", 2: "uni200D", 3: "uni25CC", 4: "uni200C", 5: "uni200D", 6: "uni25CC"}
# Name table: the merger keeps the base's (Droid copyright, Apache-2.0 only).
NAME_COPYRIGHT = ("Digitized data copyright Google Corporation \u00a9 2006 (Droid). "
                  "Copyright 2022, 2025 The Noto Project Authors (https://github.com/notofonts/) (Noto Sans).")
NAME_LICENSE = ("This Font Software is licensed under the SIL Open Font License, Version 1.1. "
                "The Droid parts are also licensed under the Apache License, Version 2.0.")
NAME_LICENSE_URL = "https://openfontlicense.org"
# Base tables the merge drops on purpose (Noto has no vertical metrics; FFTM is a FontForge stamp).
DROPPED_TABLES = {"vhea", "vmtx", "FFTM", "DSIG", "meta"}

_ALIAS = {"zerowidthjoiner": "uni200D", "zerowidthnonjoiner": "uni200C", "dottedCircle": "uni25CC",
          "dottedcircle": "uni25CC"}


def noto_path(work: Path, script: str) -> Path:
    return work / f"NotoSans{script}-Regular.ttf"


def merge(base_path: Path, work: Path) -> TTFont:
    if hashlib.sha256(base_path.read_bytes()).hexdigest() != BASE_SHA256:
        sys.exit(f"{base_path} is not mpv-android-lib 0.1.12's subfont.ttf; see the docstring")
    base = TTFont(base_path)
    upem = base["head"].unitsPerEm
    for name in ("uni200C", "uni200D", "uni25CC"):
        if name not in base.getGlyphOrder():
            sys.exit(f"base font has no {name}; the joiner remap assumes it")
    paths = [str(base_path)]
    for script in SCRIPTS:
        src = noto_path(work, script)
        with urllib.request.urlopen(NOTO_URL.format(pin=NOTO_PIN, s=script), timeout=60) as resp:
            src.write_bytes(resp.read())
        font = TTFont(src)
        if font["head"].unitsPerEm != upem:
            scale_upem(font, upem)
        scaled = work / f"scaled_{script}.ttf"
        font.save(scaled)
        paths.append(str(scaled))
    merged = Merger(options=Options(drop_tables=sorted(DROPPED_TABLES))).merge(paths)
    if len(merged.getGlyphOrder()) > 65535:
        sys.exit("merged font exceeds 65535 glyphs")
    # The merger takes the largest ascent/descent of all inputs (the tall Noto scripts), and libass
    # sizes text from them: every subtitle would shrink (~60% on the emulator). Keep the base's.
    for table, fields in BASE_METRICS.items():
        for field in fields:
            setattr(merged[table], field, getattr(base[table], field))
    for tag in ("GSUB", "GPOS", "GDEF"):
        buf = io.StringIO()
        merged.saveXML(buf, tables=[tag])
        fixed = _JOINER_REF.sub(lambda m: _JOINER_TARGET[m.lastindex], buf.getvalue())
        del merged[tag]
        merged.importXML(io.StringIO(fixed))
    texts = {0: NAME_COPYRIGHT, 13: NAME_LICENSE, 14: NAME_LICENSE_URL}
    for record in merged["name"].names:
        if record.nameID in texts:
            record.string = texts[record.nameID]
    for name_id, text in texts.items():
        if merged["name"].getDebugName(name_id) != text:
            sys.exit(f"base name table has no name ID {name_id} to rewrite")
    # Reproducible output: keep the base's timestamps instead of the build time.
    merged.recalcTimestamp = False
    merged["head"].created = base["head"].created
    merged["head"].modified = base["head"].modified
    return merged


def shape(path: str, text: str, keep_suffix: bool = False):
    import uharfbuzz as hb
    face = hb.Face(hb.Blob.from_file_path(path))
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(hb.Font(face), buf)
    names = TTFont(path, lazy=True).getGlyphOrder()
    out = []
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        name = names[info.codepoint] if keep_suffix else re.sub(r"\.\d+$", "", names[info.codepoint])
        out.append((_ALIAS.get(name, name), info.cluster, pos.x_advance, pos.x_offset, pos.y_offset))
    return out, face.upem


def check(base_path: Path, out_path: Path, work: Path) -> None:
    problems = []
    for script, text in SCRIPTS.items():
        src, src_upem = shape(str(noto_path(work, script)), text)
        got, got_upem = shape(str(out_path), text)
        k = got_upem / src_upem
        if [g[:2] for g in src] != [g[:2] for g in got]:
            problems.append(f"{script}: glyph sequence differs")
            continue
        err = max(abs(a[i] * k - b[i]) for a, b in zip(src, got) for i in (2, 3, 4))
        if err > 2:
            problems.append(f"{script}: positions off by {err:.1f} units")
    # Base scripts must keep the base's own glyphs: compare names with any ".N" suffix kept.
    for script, text in BASE_SAMPLES.items():
        if shape(str(base_path), text, True)[0] != shape(str(out_path), text, True)[0]:
            problems.append(f"base {script}: shaping changed")
    base, out = TTFont(base_path, lazy=True), TTFont(out_path, lazy=True)
    lost = set(base.keys()) - set(out.keys()) - DROPPED_TABLES
    if lost:
        problems.append(f"tables dropped by the merge: {sorted(lost)}")
    for table, fields in BASE_METRICS.items():
        for field in fields:
            if getattr(base[table], field) != getattr(out[table], field):
                problems.append(f"{table}.{field} differs from the base (text size would change)")
    if problems:
        sys.exit("shaping check failed:\n  " + "\n  ".join(problems))


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    base_path, out_path = Path(sys.argv[1]), Path(sys.argv[2])
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        built = work / "subfont.ttf"
        merge(base_path, work).save(built)
        check(base_path, built, work)
        shutil.copyfile(built, out_path)
    print(f"{out_path}: {out_path.stat().st_size} bytes, shaping check passed")


if __name__ == "__main__":
    main()
