#!/usr/bin/env python3
"""Name and check a public build's APKs for a GitHub release.

Run after `./gradlew assembleFullRelease -PnuvioPublic=true`. Reads the build's output-metadata.json and copies each
ABI's APK to builds/release/NuvioAK-<versionName>-<versionCode>-<abi>.apk, the name the in-app update check parses
(core/update/UpdateRelease.kt). Names are never typed by hand. Fails, writing nothing, when any APK entry contains
the TMDB key from local.properties (a public APK ships without one) or the build is not a two-ABI release.
stdlib only: python3 scripts/release-assets.py
"""
import json
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "app" / "build" / "outputs" / "apk" / "full" / "release"
OUT = ROOT / "builds" / "release"
ABIS = {"armeabi-v7a", "arm64-v8a"}


def local_tmdb_key():
    """Every TMDB_API_KEY value in local.properties (java.util.Properties, which the build reads, keeps the last)."""
    keys = []
    for line in (ROOT / "local.properties").read_text().splitlines():
        key, _, value = line.partition("=")
        if key.strip() == "TMDB_API_KEY" and value.strip():
            keys.append(value.strip().encode())
    if not keys:
        sys.exit("local.properties has no TMDB_API_KEY: nothing to check the APKs against")
    return keys


def main():
    meta = json.loads((BUILD / "output-metadata.json").read_text())
    apks = {}
    for el in meta["elements"]:
        abi = next((f["value"] for f in el.get("filters", []) if f["filterType"] == "ABI"), None)
        apks[abi] = (BUILD / el["outputFile"], el["versionName"], el["versionCode"])
    if set(apks) != ABIS:
        sys.exit(f"expected the ABIs {sorted(ABIS)}, the build has {sorted(map(str, apks))}")
    keys = local_tmdb_key()
    for abi, (apk, _, _) in apks.items():
        with zipfile.ZipFile(apk) as z:
            leaked = [n for n in z.namelist() if any(k in z.read(n) for k in keys)]
        if leaked:
            sys.exit(f"{apk.name}: the local TMDB key is in {leaked[:3]}: not a public build (-PnuvioPublic=true)")
        print(f"{apk.name}: no TMDB key in any entry")
    OUT.mkdir(parents=True, exist_ok=True)
    for abi, (apk, name, code) in sorted(apks.items()):
        target = OUT / f"NuvioAK-{name}-{code}-{abi}.apk"
        shutil.copyfile(apk, target)
        print(f"wrote {target.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
