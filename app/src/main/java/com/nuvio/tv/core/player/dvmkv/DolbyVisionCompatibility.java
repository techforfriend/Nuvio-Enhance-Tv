/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nuvio.tv.core.player.dvmkv;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;

/**
 * Dolby Vision decisions made while building a track {@link Format}: whether a container DV config
 * is stale (vendored {@link MatroskaExtractor}), and the colour a DV track played as its HEVC base
 * layer carries (MKV here, MP4/TS in DolbyVisionExtractorsFactory). Whether a DV track is exposed
 * as its base layer is per extractor, via
 * {@link MatroskaExtractor.DolbyVisionSampleTransformer#isHdr10BaseLayerModeActive()}.
 */
@UnstableApi
public final class DolbyVisionCompatibility {

  /** No bl_signal_compatibility_id: no config, or a config too short to hold one. */
  public static final int COMPATIBILITY_UNKNOWN = -1;

  public static boolean isStaleContainerDolbyVisionConfig(
      boolean hasColorInfo, @C.ColorTransfer int colorTransfer) {
    return hasColorInfo && colorTransfer == C.COLOR_TRANSFER_SDR;
  }

  /** bl_signal_compatibility_id of a dvcC/dvvC payload (box body): the high nibble of byte 4. */
  public static int compatibilityId(@Nullable byte[] dolbyVisionConfig) {
    if (dolbyVisionConfig == null || dolbyVisionConfig.length < 5) {
      return COMPATIBILITY_UNKNOWN;
    }
    return (dolbyVisionConfig[4] & 0xFF) >>> 4;
  }

  /**
   * Spec 93 P2 (ysosrs 7abbc5ee3): the transfer the decoded base layer carries, or {@link
   * Format#NO_VALUE} to leave it unlabelled. Profile 7 and 8.1 carry HDR10 (PQ), 8.4 HLG, 8.2 SDR.
   * {@code bitstreamTransfer} (the SPS VUI, or the format's own transfer) decides only when the
   * compatibility id does not; with nothing to go on profile 8 stays PQ, as before. Profile 5's
   * base is IPT-PQ, not BT.2020, so it is never labelled.
   */
  public static @C.ColorTransfer int baseLayerTransfer(
      int profile, int compatibilityId, @C.ColorTransfer int bitstreamTransfer) {
    if (profile == 7) {
      return C.COLOR_TRANSFER_ST2084;
    }
    if (profile != 8) {
      return Format.NO_VALUE;
    }
    switch (compatibilityId) {
      case 1:
        return C.COLOR_TRANSFER_ST2084;
      case 2:
        return Format.NO_VALUE;
      case 4:
        return C.COLOR_TRANSFER_HLG;
      default:
        if (bitstreamTransfer == C.COLOR_TRANSFER_HLG) {
          return C.COLOR_TRANSFER_HLG;
        }
        return bitstreamTransfer == C.COLOR_TRANSFER_SDR
            ? Format.NO_VALUE
            : C.COLOR_TRANSFER_ST2084;
    }
  }

  /**
   * {@code existing} with the base layer's {@code transfer} set, as BT.2020 / limited range (a
   * P7/P8.1/8.4 base layer is always that; a container BT.709 or full-range label under PQ would
   * contradict it). A declared PQ or HLG colour info is kept as it is; the container's static
   * metadata and bit depth are kept. nt26: some sinks (e.g. Amlogic AM9 Pro) act on
   * Format.colorInfo, not the in-band SPS, and stay in SDR without it (a purple tint).
   */
  @Nullable
  public static ColorInfo baseLayerColorInfo(
      @Nullable ColorInfo existing, @C.ColorTransfer int transfer) {
    if (transfer == Format.NO_VALUE) {
      return existing;
    }
    if (existing != null
        && (existing.colorTransfer == C.COLOR_TRANSFER_ST2084
            || existing.colorTransfer == C.COLOR_TRANSFER_HLG)) {
      return existing;
    }
    ColorInfo.Builder builder = existing != null ? existing.buildUpon() : new ColorInfo.Builder();
    return builder
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setColorTransfer(transfer)
        .build();
  }

  private DolbyVisionCompatibility() {}
}
