#!/usr/bin/env python3
"""Build the source archive attached to every Nuvio AK release (GPL-3.0 s6, LGPL-2.1 s6).

It fetches the exact source of each GPL / LGPL binary the APK carries, and the build scripts that link them, at the
refs THIRD_PARTY_NOTICES.md names (app/src/main/assets/licenses/); keep the two lists in step. Release tarballs are
used where the project publishes one; a repo with git submodules is fetched with git (submodules included, since
GitHub / GitLab archive tarballs drop them) and any archive tarball that still holds a `.gitmodules` fails the run.
Output: builds/release/NuvioAK-<version>-gpl-sources.tar (one tarball per component, plus SOURCES.txt with each
URL, its commit for git fetches, and the SHA-256 of the tarball). Needs git. python3 scripts/gpl-source-archive.py <versionName>
"""
import hashlib
import io
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
from pathlib import Path

GH = "https://github.com/{}/archive/{}.tar.gz"
GIT = "git"  # (name, GIT, repo URL, tag or full commit): fetched with submodules
COMPONENTS = [
    # app/libs: lib-decoder-ffmpeg (LGPL FFmpeg) and nextlib-mediainfo (GPL nextlib + its static FFmpeg / mbedTLS)
    ("ffmpeg-7.0.2", "https://ffmpeg.org/releases/ffmpeg-7.0.2.tar.xz"),
    ("ffmpeg-6.0", "https://ffmpeg.org/releases/ffmpeg-6.0.tar.xz"),
    ("nextlib-v1.9.1-0.11.0", GH.format("anilbeesetti/nextlib", "v1.9.1-0.11.0")),
    ("mbedtls-3.4.1", GH.format("Mbed-TLS/mbedtls", "v3.4.1")),
    # libtorrserver.so (GPL-3.0)
    ("torrserver-MatriX.141", GH.format("YouROK/TorrServer", "MatriX.141")),
    # mpv-android-lib 0.1.12 (GPL-3.0 build): its build scripts and every library they link
    ("mpv-android-buildscripts-v0.1.12", GH.format("abdallahmehiz/mpv-android", "v0.1.12")),
    ("mpv-76a5eba99173", GH.format("mpv-player/mpv", "76a5eba991733f41310912c79c60f6c565a77cc9")),
    ("ffmpeg-git-a7522f3fefa7", GH.format("FFmpeg/FFmpeg", "a7522f3fefa719be687f4627631ac6e5e481ef4c")),
    ("dav1d-daef39627713", "https://code.videolan.org/videolan/dav1d/-/archive/"
     "daef39627713a3e09873e78df65b268386cb4c20/dav1d-daef39627713a3e09873e78df65b268386cb4c20.tar.gz"),
    ("mbedtls-3.6.5", GIT, "https://github.com/Mbed-TLS/mbedtls.git", "mbedtls-3.6.5"),
    ("libxml2-v2.13.5", "https://gitlab.gnome.org/GNOME/libxml2/-/archive/v2.13.5/libxml2-v2.13.5.tar.gz"),
    ("libplacebo-v7.360.0", GIT, "https://github.com/haasn/libplacebo.git", "v7.360.0"),
    ("libass-fadc390583f2", GH.format("libass/libass", "fadc390583f24eb5cf98f16925fd3adee50bca88")),
    ("freetype-2.14.1", GIT, "https://gitlab.freedesktop.org/freetype/freetype.git", "VER-2-14-1"),
    ("harfbuzz-12.2.0", GH.format("harfbuzz/harfbuzz", "12.2.0")),
    ("fribidi-1.0.16", GH.format("fribidi/fribidi", "v1.0.16")),
    ("libunibreak-6.1", GH.format("adah1972/libunibreak", "libunibreak_6_1")),
    ("lua-5.2.4", "https://www.lua.org/ftp/lua-5.2.4.tar.gz"),
    # ass-kt 0.4.0's libass.so: LGPL FriBidi (above) statically linked; the scripts to relink it
    ("libass-android-v0.4.0", GIT, "https://github.com/peerless2012/libass-android.git", "v0.4.0"),
    ("libass-cmake-d3f00a43", GIT, "https://github.com/peerless2012/libass-cmake.git",
     "d3f00a43ca66e42a2c34de964b1a7dbbfa9dbc8b"),
]
HEADER = """Nuvio AK {v}: corresponding source for the GPL / LGPL components the APK carries (THIRD_PARTY_NOTICES.md).
Build recipes for the parts this repository builds itself are in the repository at this release's tag:
- nextlib-mediainfo AAR: .github/workflows/build-nextlib-mediainfo.yml + .github/nextlib/nextlib-mediainfo-static.patch
- FFmpeg 7.0.2 decoder AAR: .github/workflows/build-ffmpeg-decoder.yml + the ffmpeg-decoder-downmix module (JNI glue)
"""


def fetch(url):
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "nuvio-ak-release"})
            with urllib.request.urlopen(req, timeout=300) as r:
                return r.read()
        except Exception as e:  # transient network errors: retry, then fail the whole archive
            if attempt == 2:
                sys.exit(f"FAILED {url}: {e}")
            time.sleep(5)


def git_tarball(name, repo, ref):
    """(tar.gz bytes of the work tree at ref with every submodule, the commit)."""
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / name

        def git(*args):
            r = subprocess.run(["git", *args], cwd=src, capture_output=True, text=True)
            if r.returncode:
                sys.exit(f"{name}: git {' '.join(args[:2])} failed: {r.stderr.strip()[-500:]}")

        src.mkdir()
        git("init", "-q")
        git("fetch", "-q", "--depth", "1", repo, ref)
        git("checkout", "-q", "FETCH_HEAD")
        git("submodule", "update", "-q", "--init", "--recursive", "--depth", "1")
        commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=src, check=True, capture_output=True,
                                text=True).stdout.strip()
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as tar:
            # drop only git's own metadata (.git dirs, and the .git files submodules leave); .github/, .gitmodules,
            # .gitignore and .gitattributes are part of the source
            tar.add(src, arcname=name, filter=lambda ti: None if ".git" in ti.name.split("/") else ti)
        return buf.getvalue(), commit


def add_bytes(tar, name, data, mtime):
    info = tarfile.TarInfo(name)
    info.size, info.mtime = len(data), mtime
    tar.addfile(info, io.BytesIO(data))


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out_dir = Path(__file__).resolve().parent.parent / "builds" / "release"
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"NuvioAK-{sys.argv[1]}-gpl-sources.tar"
    part = out.with_suffix(".tar.part")  # renamed only when every component is in
    mtime = int(time.time())
    lines = [HEADER.format(v=sys.argv[1])]
    with tarfile.open(part, "w") as tar:
        for name, kind, *rest in COMPONENTS:
            if kind == GIT:
                repo, ref = rest
                data, commit = git_tarball(name, repo, ref)
                fname, where = f"{name}.tar.gz", f"{repo} {ref} (commit {commit}, submodules included)"
            else:
                data = fetch(kind)
                fname = f"{name}{'.tar.xz' if kind.endswith('.tar.xz') else '.tar.gz'}"
                if fname.endswith(".tar.gz"):
                    with tarfile.open(fileobj=io.BytesIO(data)) as t:
                        if any(m.name.endswith("/.gitmodules") for m in t.getmembers()):
                            sys.exit(f"{name}: the archive has submodules it does not contain: fetch it with git")
                where = kind
            add_bytes(tar, fname, data, mtime)
            lines.append(f"{fname}  {hashlib.sha256(data).hexdigest()}  {where}")
            print(f"{len(data) / 1e6:7.1f} MB  {name}", flush=True)
        add_bytes(tar, "SOURCES.txt", ("\n".join(lines) + "\n").encode(), mtime)
    part.replace(out)
    print(f"wrote {out} ({out.stat().st_size / 1e6:.0f} MB)")


if __name__ == "__main__":
    main()
