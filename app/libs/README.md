## Bundled native components: versions and provenance (2026-11-01)

User-facing licences and source links: `app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md`. How each version
below was identified (read from the binaries and their upstream repos, spot-checked; the Media3 entry by the
2026-11-02 rebuild it records):

- Media3 AARs here: base 1.8.0 (`MediaLibraryInfo` in lib-common). Modified source: https://github.com/halibiram/media
  branch `feature/nuvio-engine-config-api` (both commits below are on it). lib-exoplayer =
  `d8a9c7f58fa321e5f389a877b3bc495ac52f8ade` (a child of `b5a11f5f9c581064ef64c098fc05133e36a331b4`), ysosrs
  328bb05c0, then the audio splice below. lib-common, -extractor, -datasource, -datasource-okhttp and -exoplayer-hls =
  `4bb1cb1f4751fff095c3b8a3993caee5e39bcc55` (2026-06-25; arrived with ysosrs's 0.7.17-beta-nt1 squash), rebuild-verified
  2026-11-02: `git fetch --depth 1 https://github.com/halibiram/media <that commit>` and check out
  `FETCH_HEAD`; halibiram/media's own wrapper (Gradle 8.10.2, AGP 8.8.1) on JDK 21 (Temurin 21.0.13), SDK platform 35
  + build-tools 35.0.0 (NDK 28.2.13676358 installed by the build); `./gradlew :lib-<name>:assembleRelease` for the
  five, output `libraries/<dir>/buildout/outputs/aar/lib-<name>-release.aar`. Compared with the shipped AAR entry by
  entry, nested `classes.jar` and `annotations.zip` included, ignoring zip timestamps, order and compression and
  reading CRLF as LF in `.xml` / `.txt` / `.properties` / `.pro` entries (the shipped ones are CRLF, a Windows
  checkout): every entry identical. JDK 17 javac does not reproduce 6 datasource and 2 okhttp classes. lib-decoder-av1 =
  unmodified androidx/media 1.8.0 (`b7bbc6e2bc`) decoder_av1 + libgav1, built by NuvioTV upstream (project commit
  378042016) with JDK 17 and NDK 27.0.12077973; its 6 classes match 1.8.0 compiled with JDK 17 (javac-level check,
  no Gradle rebuild); the libgav1 / abseil / cpu_features revisions are not embedded, so `libgav1JNI.so` is
  unverified.
- `nextlib-mediainfo-local.aar`: nextlib 1.9.1-0.11.0 (`302adbfa91`) mediainfo classes + one `libmediainfo.so` per
  ABI (armeabi-v7a, arm64-v8a) with FFmpeg 6.0 and mbedTLS 3.4.1 linked statically, so its FFmpeg does not clash
  with mpv-android-lib's libav*.so. Built by `.github/workflows/build-nextlib-mediainfo.yml` with
  `.github/nextlib/nextlib-mediainfo-static.patch` (build changes only); it replaced the private build JFeverish
  f66e3b2b4 introduced upstream (same classes.jar byte for byte, the same JNI and exported symbols). Used only by the
  AFR preflight's second stage (`AfrMediaProbe.detectFrameRateFromNextLib`).
- `../src/main/jniLibs/*/libtorrserver.so`: YouROK TorrServer MatriX.141 release binaries (vcs.revision d266990f,
  Go 1.25.6), UPX-packed in fa59f6479 (CrissZollo) from 3ab8af4b7; `upx -d` gives the upstream release assets
  byte for byte. Only v7a + arm64 ship (ABI splits).
- `../../DV7/libdovi/*/lib/libdovi.a` (linked into `libdovi_bridge.so` when `DOVI_ENABLE_LIBDOVI` is on, as in the
  release config): dolby_vision C API 3.3.2 from dovi_tool `main` between 53c80739cf and d1abe0e27f (bitstream-io
  4.9.0, rustc 1.93.0); exact commit unrecorded.
- FFmpeg in `lib-decoder-ffmpeg-release.aar`: n7.0.2, LGPL build (section below).

## Fork modification: lib-exoplayer-release.aar audio classes (2026-10-18)

SHA-256 `a09431c3ff12424f251250ad4ac9cb07948eb6ee69163a949ae6d82fcf34652b` (previous
`b4a9e7052a2d9d06fa164f0232cd9bb714ca8fc77ba7f03a2fb4f951fbf374e7`, from NuvioMedia upstream commit
`b9172d13f`).

The 13 differing classes `DefaultAudioSink` (+ `$Api23`, `$Api31`, `$Configuration`,
`$MediaPositionParameters`, `$OnRoutingChangedListenerApi24`, `$PendingExceptionHolder`,
`$PositionTrackerListener`, `$StreamEventCallbackV29`(+`$1`)), `AudioTrackPositionTracker` and
`AudioTimestampPoller` (+ `$AudioTimestampWrapper`) are byte copies from ysosrs `5d23cce09`'s AAR
(SHA-256 `4c3047e76a9cb64f03c95762a696ad2aba0e200431fefe4d8c5b3803ac409cca`; its own README there
gives the source build): the pre-syncframe drop
resync (halibiram/media #2: a dropped TrueHD chunk re-reads the media-time anchor instead of leaving
audio 13-107 ms ahead) and media3 `bc0652cb54` + `8ca4ef9db0` (clock holds during an underrun; new
timestamp on output-latency change). Every other entry is byte-identical to the previous AAR: the
`VideoFrameReleaseHelper`/`Control` classes stay 1.8.0 (an upstream change there is deferred) and every
`libmedia3_exoplayer_jni.so` stays pre-calloc (deferred likewise). Splice: copy those `classes.jar`
entries over ours, rewriting both zips in place with entry order and timestamps kept.

## lib-decoder-ffmpeg-release.aar: provenance and rebuild (2026-10-06)

`jni/*/libffmpegJNI.so` = `ffmpeg-decoder-downmix` source (JNI on the module's NDK 29) linked
against FFmpeg n7.0.2 static libs built with NDK 27.0.12077973 by the configure step in
`.github/workflows/build-ffmpeg-decoder.yml` (its decoder list + the AC-3 encoder; no
`--enable-gpl`/`--enable-nonfree`). Verified 2026-10-06: that step on the signed 7.0.2 release
tarball (the workflow itself clones the git tag) plus the source before the 2026-10-06 port reproduced the previous
`.so` files byte-for-byte except the GNU build-id. `classes.jar` is NOT
the module's: it carries the modified renderer below, which the app needs (`setDeniedTranscodeMimes`).

Rebuild: build FFmpeg with the workflow's configure step (the 2026-10-06 port used the signed release tarball), run
`FFMPEG_SOURCE_DIR=<ffmpeg src> FFMPEG_BUILD_DIR=<root with <abi>/lib*.a> ./gradlew
:ffmpeg-decoder-downmix:assembleRelease`, then replace ONLY the four `jni/<abi>/libffmpegJNI.so`
entries of this AAR with the module AAR's (`zip -X`); never replace the whole AAR (drops the modified renderer).
The ak22 upstream port spliced in the multi-frame output-growth fix this way; AAR SHA-256
`64ae858046b7611cbfd17912cb672463f28603ab738cb973ca0621ba86a0c2a7`.

## Fork modification: lib-decoder-ffmpeg-release.aar (2026-08)

`FfmpegAudioRenderer.class` inside `classes.jar` was rebuilt from the reconstruction
source at `ffmpeg-aar-src/FfmpegAudioRenderer.java` (CFR 0.152 decompilation of the
original class, plus one change: `setDeniedTranscodeMimes` — the AC-3 transcode
decision is now per-MIME, consulting an app-supplied denied set as well as the global
`forceOpticalPassthrough` flag). With the set empty the behaviour is equivalent to the
original. All other AAR entries were byte-identical to the previous AAR at that change (the `.so`
files changed later, the 2026-10-06 port above). The transcode/downmix pipeline is the upstream NuvioTV
developer's work; its native and other Java source is the `ffmpeg-decoder-downmix` module
(an earlier claim that it existed only in this binary was wrong, see the 2026-10-06 section above). The
reconstruction source is provided as corresponding source for the modified class.

Rebuild recipe (JDK 21, CFR 0.152, media3-decoder 1.8.0 sources, android.jar API 34):
compile `ffmpeg-aar-src/FfmpegAudioRenderer.java` with `--release 11 -g` against
{android.jar, lib-common classes.jar, lib-exoplayer classes.jar, this AAR's
classes.jar, media3-decoder 1.8.0 classes, androidx-annotation, guava}; `jar uf` the
resulting class into `classes.jar`; `zip` it back into the AAR.
