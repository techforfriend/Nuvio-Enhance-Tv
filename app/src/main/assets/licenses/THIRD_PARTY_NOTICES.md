# Third-party notices

Nuvio AK (`com.nuvio.tv.ak`) is free software under the GNU General Public License v3.0 (`GPL-3.0.txt`). It is a
fork of NuvioTV (https://github.com/NuvioMedia/NuvioTV) by way of ysosrs123/NuvioTV-Fork. The components below are
bundled in the app. Every licence text named here is in this folder (in the APK: `assets/licenses/`).

Source code: the source of Nuvio AK is at https://github.com/Arshad-Kamal/Nuvio-AK, where the APKs are published:
the app, the `ffmpeg-decoder-downmix` module and the rebuilt FFmpeg audio renderer class. The modified Media3 source
is in a separate repository, named in its entry below. The mpv player libraries (mpv-android-lib 0.1.12) are built
from the upstream versions named in their entries, with the build scripts at
https://github.com/abdallahmehiz/mpv-android/tree/v0.1.12/buildscripts. Each other entry links its upstream source.

Facts verified 2026-11-01 and 2026-11-02 against the shipped files (version strings, configure lines, ELF build IDs,
font name tables, headers) and the upstream repositories.

---

## Fonts

**Inter** 4.001 (git 66647c0bb), variable
Copyright 2016 The Inter Project Authors (https://github.com/rsms/inter)
SIL Open Font License 1.1 (`OFL-1.1_Inter.txt`). Unmodified.

**Open Sans** 3.003, variable
Copyright 2020 The Open Sans Project Authors (https://github.com/googlefonts/opensans)
SIL Open Font License 1.1 (`OFL-1.1_OpenSans.txt`). Unmodified.

**DM Sans** 4.004, variable
Copyright 2014 The DM Sans Project Authors (https://github.com/googlefonts/dm-fonts)
SIL Open Font License 1.1 (`OFL-1.1_DMSans.txt`). Unmodified.

**Droid Sans Fallback** 2.56, merged with Droid Sans, Droid Sans Arabic, Droid Sans Devanagari, Droid Sans Hebrew,
Droid Sans Tamil and Droid Sans Thai (`assets/subfont.ttf`, the mpv subtitle fallback font)
Digitized data copyright Google Corporation © 2006; Droid is a trademark of Google.
Apache-2.0 (`Apache-2.0.txt`). Modified: the mpv-android project merged these fonts into one file with FontForge
(2024-10-18, commit `1faf835` of https://github.com/mpv-android/mpv-android). Shipped unchanged from
mpv-android-lib 0.1.12.

**Roboto Medium** 2.137 (numerals subset, `roboto_medium_numbers`, from AndroidX Media3 UI 1.8.0)
Copyright 2011 Google Inc. All Rights Reserved.
Apache-2.0 (`Apache-2.0.txt`). Source: https://github.com/androidx/media

## Native libraries

**libdovi** (the `dolby_vision` crate's C API) 3.3.2, built from dovi_tool `main` after the `libdovi-3.3.2` tag
Copyright (c) 2026 quietvoid
MIT (`MIT_libdovi.txt`). Source: https://github.com/quietvoid/dovi_tool
Unmodified; statically linked into `libdovi_bridge.so`.
Rust crates linked into it: bitvec 1.0.1, funty 2.0.0 and wyz 0.5.1 (myrrlyn (Alexander Payne)); radium 0.7.0
(kneecaw (Nika Layzell)); tap 1.0.1 (Elliot Linder); bitvec_helpers 4.0 (quietvoid; licence text from 4.0.1): MIT, copyright lines in
`MIT_libdovi-rust-dependencies.txt`. anyhow, bitstream-io, crc, crc-catalog, libc, tinyvec and the Rust standard
library are used under Apache-2.0 (`Apache-2.0.txt`), as their dual licences allow.

**FFmpeg** 7.0.2 (libavcodec, libavutil, libswresample)
Copyright the FFmpeg developers
LGPL-2.1-or-later (`LGPL-2.1_FFmpeg.txt`). Built without `--enable-gpl` or `--enable-nonfree`: audio decoders and
the AC-3 encoder only (configure line: `.github/workflows/build-ffmpeg-decoder.yml` in the source repository).
Unmodified; statically linked into `libffmpegJNI.so`.
Source: https://ffmpeg.org/releases/ffmpeg-7.0.2.tar.xz (signature: https://ffmpeg.org/releases/ffmpeg-7.0.2.tar.xz.asc)

**ffmpeg-decoder-downmix** (the JNI layer of `libffmpegJNI.so` and the `androidx.media3.decoder.ffmpeg` classes)
Based on AndroidX Media3 decoder_ffmpeg, Copyright (C) 2016 The Android Open Source Project (Apache-2.0); downmix
behaviour adapted from Kodi (GPL-2.0-or-later). Distributed under GPL-3.0-only (`GPL-3.0.txt`).
Modified: AC-3 transcoding, downmix, per-format transcode denial. Source: `ffmpeg-decoder-downmix/` and
`app/libs/ffmpeg-aar-src/` in the source repository.

**TorrServer** MatriX.141
Copyright YouROK and the TorrServer contributors
GPL-3.0 (`GPL-3.0.txt`). Source: https://github.com/YouROK/TorrServer/tree/MatriX.141
The official release binaries, unmodified apart from UPX compression; shipped as `libtorrserver.so`.

**nextlib-mediainfo** (from nextlib 1.9.1-0.11.0)
Copyright Anil Beesetti and the nextlib contributors
GPL-3.0 (`GPL-3.0.txt`). Upstream source: https://github.com/anilbeesetti/nextlib (tag `v1.9.1-0.11.0`).
This build is one `libmediainfo.so` with FFmpeg linked statically, built by this repository's
`.github/workflows/build-nextlib-mediainfo.yml` with `.github/nextlib/nextlib-mediainfo-static.patch` (build
changes only).
It contains:
- FFmpeg 6.0 (libavformat, libavcodec, libavutil, libswscale, libswresample), Copyright the FFmpeg developers,
  LGPL-3.0-or-later (`LGPL-3.0_FFmpeg.txt`, read with `GPL-3.0.txt`). Source: https://ffmpeg.org/releases/ffmpeg-6.0.tar.xz
- Mbed TLS 3.4.1, Copyright The Mbed TLS Contributors, Apache-2.0 (`Apache-2.0.txt`).
  Source: https://github.com/Mbed-TLS/mbedtls/tree/v3.4.1

**AndroidX Media3** 1.8.0, modified (lib-common, lib-exoplayer, lib-extractor, lib-datasource,
lib-datasource-okhttp, lib-exoplayer-hls)
Copyright (C) The Android Open Source Project
Apache-2.0 (`Apache-2.0.txt`). Upstream: https://github.com/androidx/media
Modified by the NuvioTV contributors; modified source: https://github.com/halibiram/media (branch
`feature/nuvio-engine-config-api`): lib-common, lib-extractor, lib-datasource, lib-datasource-okhttp and
lib-exoplayer-hls at commit `4bb1cb1f4751fff095c3b8a3993caee5e39bcc55`, lib-exoplayer at commit
`d8a9c7f58fa321e5f389a877b3bc495ac52f8ade` (a child of `b5a11f5f9c581064ef64c098fc05133e36a331b4`).
lib-exoplayer also carries audio-sink classes rebuilt by ysosrs123 (a resync fix) with two later Media3 commits
(`bc0652cb54`, `8ca4ef9db0`); details in `app/libs/README.md` in the source repository. lib-decoder-av1 is
unmodified Media3 1.8.0 (tag `1.8.0`). Also bundled unmodified from Maven Central: media3-ui 1.8.0 and media3-effect
1.8.0 (its GLSL shaders in `assets/shaders/`).

**libgav1** (inside lib-decoder-av1, `libgav1JNI.so`)
Copyright 2019 The libgav1 Authors
Apache-2.0 (`Apache-2.0.txt`). Source: https://chromium.googlesource.com/codecs/libgav1

**mpv-android-lib** 0.1.12 (`libmpv.so`, `libplayer.so`, `libavcodec.so`, `libavdevice.so`, `libavfilter.so`,
`libavformat.so`, `libavutil.so`, `libswresample.so`, `libswscale.so`, `libxml2.so`, `libc++_shared.so`, and the
`is.xyz.mpv` classes). Unmodified binaries (native symbols stripped when the APK is packaged) from Maven Central
(`io.github.abdallahmehiz:mpv-android-lib:0.1.12`),
built by the project's CI from https://github.com/abdallahmehiz/mpv-android/tree/v0.1.12 (build scripts:
`buildscripts/`). Its parts:

- **mpv-android** (`libplayer.so` and the Java classes), Copyright (c) 2016 Ilya Zhuravlev, Copyright (c) 2016 sfan5.
  MIT (`MIT_mpv-android.txt`). Source: https://github.com/abdallahmehiz/mpv-android/tree/v0.1.12
- **mpv** v0.41.0-174-g76a5eba99 (`libmpv.so`), Copyright the mpv/MPlayer/mplayer2 projects (2000-2026).
  Built with the `gpl` feature: GPL-2.0-or-later, used here under GPL-3.0 (`GPL-3.0.txt`); licensing details in
  `mpv-Copyright.txt`. Source: https://github.com/mpv-player/mpv/tree/76a5eba99173
  It contains fzy.lua, Copyright (c) 2020 Seth Warn, MIT (`MIT_fzy-lua.txt`).
- **FFmpeg** git N-122742-ga7522f3fef (libavcodec 62.23.103, libavformat 62.9.101, libavfilter, libavutil,
  libavdevice, libswresample, libswscale), Copyright the FFmpeg developers. Built with `--enable-gpl --enable-version3`:
  GPL-3.0-or-later (`GPL-3.0.txt`). Source: https://github.com/FFmpeg/FFmpeg/tree/a7522f3fefa7
  (configure line embedded in each library and in `buildscripts/scripts/ffmpeg.sh`).
- **dav1d** 1.5.3-18-gdaef3962 (static in `libavcodec.so`), Copyright © 2018-2025, VideoLAN and dav1d authors.
  BSD-2-Clause (`BSD-2-Clause_dav1d.txt`). Source: https://code.videolan.org/videolan/dav1d (commit `daef3962`)
- **Mbed TLS** 3.6.5 (static in `libavformat.so`), Copyright The Mbed TLS Contributors. Apache-2.0
  (`Apache-2.0.txt`). Source: https://github.com/Mbed-TLS/mbedtls/tree/mbedtls-3.6.5
- **libxml2** 2.13.5 (`libxml2.so`), Copyright (C) 1998-2012 Daniel Veillard. MIT (`MIT_libxml2.txt`).
  Source: https://gitlab.gnome.org/GNOME/libxml2/-/tree/v2.13.5
- **libplacebo** v7.360.0 (static in `libmpv.so`), Copyright Niklas Haas and the libplacebo contributors.
  LGPL-2.1-or-later (`LGPL-2.1_FFmpeg.txt`, the same licence text). Source: https://github.com/haasn/libplacebo/tree/v7.360.0
  It contains fast_float, Copyright (c) 2021 The fast_float authors, MIT (`MIT_fast_float.txt`), and an OpenGL
  loader generated by glad (generated code: public domain, WTFPL or CC0; Khronos registry data: Apache-2.0).
- **libass** 0.17.4-21-gfadc3905 (static in `libmpv.so`), Copyright (C) 2006-2016 libass contributors. ISC
  (`ISC_libass.txt`). Source: https://github.com/libass/libass/tree/fadc390583f24eb5cf98f16925fd3adee50bca88
- **FreeType** 2.14.1 (static in `libmpv.so`). FreeType License (`FTL_FreeType.txt`).
  Portions of this software are copyright © 2025 The FreeType Project (https://freetype.org). All rights reserved.
  Source: https://gitlab.freedesktop.org/freetype/freetype/-/tree/VER-2-14-1
- **HarfBuzz** 12.2.0 (static in `libmpv.so`), Copyright Google, Inc., Red Hat, Inc. and the HarfBuzz authors
  (full list in the licence file). Old MIT (`MIT_HarfBuzz.txt`). Source: https://github.com/harfbuzz/harfbuzz/tree/12.2.0
- **GNU FriBidi** 1.0.16 (static in `libmpv.so`), Copyright the FriBidi authors. LGPL-2.1-or-later
  (`LGPL-2.1_FFmpeg.txt`). Source: https://github.com/fribidi/fribidi/tree/v1.0.16
- **libunibreak** 6.1 (static in `libmpv.so`), Copyright Wu Yongwei, Tom Hacohen, Petr Filipsky and Andreas Röver.
  zlib licence (`Zlib_libunibreak.txt`).
  Source: https://github.com/adah1972/libunibreak/tree/libunibreak_6_1
- **Lua** 5.2.4 (static in `libmpv.so`), Copyright (C) 1994-2015 Lua.org, PUC-Rio. MIT (`MIT_Lua.txt`).
  Source: https://www.lua.org/ftp/lua-5.2.4.tar.gz
- **LLVM libc++** (`libc++_shared.so`, from the Android NDK; the APK keeps mpv-android-lib's copy), Copyright the
  LLVM Project contributors. Apache-2.0 with LLVM Exceptions (`Apache-2.0-with-LLVM-exception_libcxx.txt`).
  Source: https://github.com/llvm/llvm-project/tree/main/libcxx

**libass-android** 0.4.0 (`ass-kt` and `ass-media`: `libass.so`, `libasskt.so` and the `io.github.peerless2012.ass`
classes). Unmodified binaries from Maven Central (`io.github.peerless2012:ass-media:0.4.0`, `ass-kt:0.4.0`).
Copyright (c) 2025 peerless2012. MIT (`MIT_libass-android.txt`).
Source: https://github.com/peerless2012/libass-android/tree/v0.4.0 (native build:
https://github.com/peerless2012/libass-cmake, commit `d3f00a43`). `libass.so` contains, statically linked:
- libass 0.17.4, Copyright (C) 2006-2016 libass contributors, ISC (`ISC_libass.txt`).
- FreeType 2.13.3, FreeType License (`FTL_FreeType.txt`). Portions of this software are copyright © 2024 The
  FreeType Project (https://freetype.org). All rights reserved.
- HarfBuzz 11.3.3, Old MIT (`MIT_HarfBuzz.txt`).
- GNU FriBidi 1.0.16, LGPL-2.1-or-later (`LGPL-2.1_FFmpeg.txt`).
- libunibreak 6.1, zlib licence (`Zlib_libunibreak.txt`).
- Fontconfig 2.16.2-105-gdaa175d2, Copyright Keith Packard, Red Hat, Inc. and others (years and full list in
  the licence file), MIT-style (`fontconfig-COPYING.txt`). Source: https://gitlab.freedesktop.org/fontconfig/fontconfig
  (commit `daa175d2`).
- Expat 2.7.1, Copyright (c) 1998-2000 Thai Open Source Software Center Ltd and Clark Cooper, Copyright (c) 2001-2025
  Expat maintainers, MIT (`MIT_expat.txt`). Source: https://github.com/libexpat/libexpat/tree/R_2_7_1

**AndroidX Graphics Path** 1.0.1 (`libandroidx.graphics.path.so`) and **AndroidX DataStore** 1.1.7
(`libdatastore_shared_counter.so`)
Copyright (C) The Android Open Source Project
Apache-2.0 (`Apache-2.0.txt`). Source: https://android.googlesource.com/platform/frameworks/support

## Source files

**Media3 Matroska extractor** (`app/src/main/java/com/nuvio/tv/core/player/dvmkv/`)
Copyright (C) 2016, 2019 The Android Open Source Project
Apache-2.0 (`Apache-2.0.txt`). Upstream: https://github.com/androidx/media
Modified: relocated into the app and extended (Dolby Vision sample rewriting, chapters); each file taken from Media3
says so in its header.

## Java libraries

**Jakarta Dependency Injection** 2.0.1 (`jakarta.inject-api`, via Dagger/Hilt)
Copyright the Eclipse Jakarta Dependency Injection project authors.
Apache-2.0 (`Apache-2.0.txt`). Its NOTICE file is in the APK as `META-INF/NOTICE.md`.
Source: https://github.com/eclipse-ee4j/injection-api

## Images

**Fluent Emoji** (the bundled 3D avatar heads, `creature_*.webp`)
Copyright (c) Microsoft Corporation
MIT (`fluentui-emoji-LICENSE.txt`). Source: https://github.com/microsoft/fluentui-emoji

**Tears of Steel** still (`settings_subtitle_preview.jpg`, the subtitle preview background)
(CC) Blender Foundation | mango.blender.org
Creative Commons Attribution 3.0 (https://creativecommons.org/licenses/by/3.0/). Modified: cropped to 1280 x 232
from "Tears of Steel frame 01 2a" (https://commons.wikimedia.org/wiki/File:Tears_of_Steel_frame_01_2a.jpg).

## Data

**TMDB.** This product uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB.
https://www.themoviedb.org

**Mozilla CA certificate bundle** (`assets/cacert.pem`), certificate data from Mozilla as of 2025-12-02, converted by
curl (https://curl.se/docs/caextract.html). Mozilla Public License 2.0 (`MPL-2.0.txt`). The file is its own source
form. Source: https://hg.mozilla.org/projects/nss (certdata.txt).

**Public Suffix List** (`assets/PublicSuffixDatabase.list`, as bundled in OkHttp 5.3.2)
Mozilla Public License 2.0 (`MPL-2.0.txt`). Source: https://publicsuffix.org/list/public_suffix_list.dat
(the list), https://github.com/square/okhttp (the conversion).
