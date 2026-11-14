package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor as DvMatroskaExtractor
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * App-level Dolby Vision Profile 7 to 8.1 conversion that needs no forked Media3.
 *
 * It wraps a stock [ExtractorsFactory] and, for HEVC video tracks of containers
 * whose RPU rides in-band as NAL units (MP4 / fMP4 / TS, all Annex-B at the
 * TrackOutput), intercepts the sample stream at the [TrackOutput] level and rewrites the
 * Dolby Vision RPU NAL (type 62) via [DoviBridge], drops the enhancement-layer
 * NAL units, and rewrites the codec string (dvhe.07 becomes dvhe.08).
 *
 * Matroska is special for two reasons:
 * 1. Its samples are rewritten while still length-delimited, inside the vendored
 *    [com.nuvio.tv.core.player.dvmkv.MatroskaExtractor] (via
 *    [DolbyVisionMatroskaTransformer]); the extractor also exposes the DV track
 *    as HEVC (P7/P8 tagged HDR10) when HDR10-base-layer mode is active.
 * 2. That same vendored extractor sniffs the first DTS sample to distinguish
 *    core DTS vs DTS-HD MA / DTS:X (mkvmerge tags every DTS variant as A_DTS;
 *    stock media3 maps that to core DTS with no inspection). The Matroska swap
 *    is therefore unconditional — even when DV conversion is inactive — so
 *    native-DV / policy-OFF boxes still get correct HD audio mime for bitstream.
 *
 * For any non-DV7 content (or when [config] is inactive) every non-Matroska
 * wrapper is a strict pass-through, so normal playback of all formats is unaffected.
 *
 * [hdr10BaseLayerMode] (HDR10 base-layer / strip modes) makes each Matroska extractor
 * this factory creates expose a DV track as its HEVC base layer.
 */
@UnstableApi
internal class DolbyVisionExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val config: DolbyVisionConversionConfig,
    private val stripDvRpu: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false,
    private val injectHdr10Sei: Boolean = false,
    private val hdr10BaseLayerMode: Boolean = false
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor {
        // Matroska is swapped unconditionally (audio review F1): the vendored
        // extractor carries the DTS-HD MA / DTS:X first-sample sniff (mkvmerge
        // writes A_DTS for every DTS variant; stock media3 1.8 maps that to core
        // DTS with no inspection). Gating the swap behind DV features meant the
        // devices most likely to bitstream HD audio - native-DV7 boxes where the
        // policy resolves to OFF - never got the sniff and negotiated core DTS
        // for DTS-HD content. With an inactive config the transformer's
        // shouldTransform() returns false, so non-DV HEVC samples stream through
        // the stock write path at no extra cost.
        // The MKV HDR10-base-layer colorInfo (nt26) is set inside the vendored
        // extractor where it maps the DV track to HEVC.
        if (extractor.javaClass.name == STOCK_MATROSKA_EXTRACTOR) {
            // libass off: ASS drawings are filtered (Spec 87 T2). The libass path swaps this
            // extractor for NuvioAssMatroskaExtractor with its own parsers.
            return DvMatroskaExtractor(
                RenderTimeSubtitles.extractionParsers(AssDrawingFilter.parserFactory()),
                /* flags= */ 0,
                DolbyVisionMatroskaTransformer(
                    config = config,
                    stripRpuOnly = stripDvRpu && !config.active,
                    stripHdr10PlusSei = stripHdr10PlusSei,
                    injectHdr10Sei = injectHdr10Sei,
                    hdr10BaseLayerMode = hdr10BaseLayerMode,
                )
            )
        }
        if (!config.active && !stripDvRpu && !stripHdr10PlusSei) return extractor
        if (!emitsAnnexBInBandNals(extractor)) return extractor
        return DolbyVisionExtractor(extractor, config, stripDvRpu, stripHdr10PlusSei)
    }

    // The RPU rides in-band in the sample for these containers, and all of them hand
    // Annex-B to the TrackOutput: TS natively, and media3 1.8.0 Mp4Extractor /
    // FragmentedMp4Extractor replace each NAL length prefix with a 00 00 00 01 start
    // code in readSample(). ("Mp4Extractor" also matches FragmentedMp4Extractor.)
    private fun emitsAnnexBInBandNals(extractor: Extractor): Boolean {
        val name = extractor.javaClass.name
        return name.contains("Mp4Extractor") || name.contains("TsExtractor")
    }

    private companion object {
        const val STOCK_MATROSKA_EXTRACTOR = "androidx.media3.extractor.mkv.MatroskaExtractor"
    }
}

/**
 * Per-playback DV7 to 8.1 conversion diagnostics, fed by the factory/transformer and read
 * by the player diagnostics. Reset per playback alongside the DoviBridge counters.
 */
internal object DolbyVisionConversionStats {
    private val codecStringRewriteCount = AtomicLong(0)
    // DV7 review F5: RPUs dropped because conversion failed (never forwarded raw).
    private val rpuDropCount = AtomicLong(0)
    @Volatile private var lastSourceProfile: Int? = null
    // DV7 F3: EL type of the current stream (DoviBridge.EL_TYPE_* / -1 / -2).
    @Volatile private var lastElType: Int? = null
    // Item 2: static HDR metadata read from the current stream's first RPU.
    @Volatile private var lastRpuMetadata: DoviBridge.RpuStaticMetadata? = null

    fun reset() {
        codecStringRewriteCount.set(0)
        rpuDropCount.set(0)
        lastSourceProfile = null
        lastElType = null
        lastRpuMetadata = null
    }

    fun recordElType(code: Int) {
        lastElType = code
    }

    fun getLastElType(): Int? = lastElType

    fun recordRpuMetadata(metadata: DoviBridge.RpuStaticMetadata?) {
        if (metadata != null) lastRpuMetadata = metadata
    }

    fun getLastRpuMetadata(): DoviBridge.RpuStaticMetadata? = lastRpuMetadata

    fun recordRpuDrop() {
        rpuDropCount.incrementAndGet()
    }

    fun getRpuDropCount(): Long = rpuDropCount.get()

    /** Records the SOURCE DV profile (pre-conversion), e.g. 7. */
    fun recordSourceProfile(profile: Int?) {
        if (profile != null) lastSourceProfile = profile
    }

    fun recordCodecStringRewrite() {
        codecStringRewriteCount.incrementAndGet()
    }

    fun getCodecStringRewriteCount(): Long = codecStringRewriteCount.get()
    fun getLastSourceProfile(): Int? = lastSourceProfile
}

/**
 * Drives the per-stream conversion decision. Mirrors the auto-pick used by the
 * extractor hook installer: profile-7 AUTO = mode 1 (ToMel); profile-7 manual
 * Convert-to-DV8.1 = mode 2; profile 5 = mode 2; a [forcedMode] in 0..4
 * overrides all of it.
 */
internal data class DolbyVisionConversionConfig(
    val active: Boolean,
    val forcedMode: Int = -1,
    val dv5Enabled: Boolean = false,
    /** True when the user explicitly chose "Convert to DV8.1" (not AUTO). */
    val manualDv81: Boolean = false
) {
    /**
     * DV5 via the toggle is signal-only (codec rewritten to 8.1, profile-5 RPU kept). A
     * libdovi RPU rewrite runs only when a mode is forced in Advanced, OR when the user
     * explicitly chose Convert to DV8.1 with DV5 enabled. DV7 always converts.
     */
    val convertDv5Rpu: Boolean get() = forcedMode in 0..4 || (dv5Enabled && manualDv81)

    /** True when a track of [profile] should be converted. */
    fun shouldConvert(profile: Int?): Boolean {
        if (!active) return false
        return when (profile) {
            7 -> true
            // DV5 is already single-layer; only convert it when the user explicitly chose
            // Convert to DV8.1. AUTO leaves DV5 alone (converting it breaks colors).
            5 -> dv5Enabled && manualDv81
            else -> false
        }
    }

    /** libdovi conversion mode to use for [profile].
     *
     * Verified against the bundled libdovi 3.3.2 source (dolby_vision tag
     * libdovi-3.3.2, rpu/mod.rs `From<u8> for ConversionMode`): the C API
     * maps 0 -> Lossless, 1 -> ToMel, 2 AND 3 -> To81, 4 -> To84 (static
     * 8.4), and anything >= 5 -> Lossless. To81 accepts source profiles 5
     * (p5_to_p81), 7 and 8. The header's per-mode doc comment ("3 = static
     * 8.4", "4 = 8.1 preserve-mapping") is stale and contradicts the shipped
     * code; To81MappingPreserved is unreachable through the C API.
     *
     * P5 uses mode 2: mode 3 is only a legacy alias of To81 that the header
     * documents as 8.4, so mode 2 is the one value where the documented
     * contract and the code agree, and it stays correct if upstream libdovi
     * ever aligns the code with its docs (which would turn 3 into 8.4).
     *
     * Preserve-mapping (To81MappingPreserved) is deliberately never
     * requested. It is unreachable through the C API in every libdovi 3.x
     * release including master, and executing modes 1/2/5 against upstream's
     * own P7 FEL fixtures shows it retains the source composer curves --
     * the pre-2.0 behaviour upstream moved away from in commit b45e20e,
     * because those curves assume an enhancement layer this pipeline strips.
     * dovi_bridge.cpp maps any value outside 0..4 to mode 2.
     */
    fun conversionMode(profile: Int?): Int {
        if (forcedMode in 0..4) return forcedMode
        return when {
            profile == 5 -> 2
            manualDv81 -> 2 // manual Convert to DV8.1 uses mode 2
            else -> 1       // AUTO convert stays on mode 1
        }
    }
}

// nt26: fixed HDR10 (BT.2020 / limited-range / PQ) colour signalling. Applied to
// the stripped base layer's Format so the Android display framework switches the
// sink into HDR mode. The stripped stream is genuine HDR10 in-band (the SPS VUI
// is preserved by the strip), but Format.colorInfo is usually null on DV
// containers, and some sinks (e.g. Amlogic AM9 Pro) act on the framework's
// colorInfo, not the in-band SPS - leaving the display in SDR (PQ shown as SDR
// reads as a purple tint) until this is set.
@UnstableApi
private fun hdr10ColorInfo(): ColorInfo = ColorInfo.Builder()
    .setColorSpace(C.COLOR_SPACE_BT2020)
    .setColorRange(C.COLOR_RANGE_LIMITED)
    .setColorTransfer(C.COLOR_TRANSFER_ST2084)
    .build()

/** Wraps an [Extractor] to inject a DV-rewriting [ExtractorOutput]. */
@UnstableApi
private class DolbyVisionExtractor(
    private val delegate: Extractor,
    private val config: DolbyVisionConversionConfig,
    private val stripDvRpu: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false
) : Extractor {

    private var output: DolbyVisionExtractorOutput? = null

    override fun init(output: ExtractorOutput) {
        val wrapped = DolbyVisionExtractorOutput(output, config, stripDvRpu, stripHdr10PlusSei)
        this.output = wrapped
        delegate.init(wrapped)
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        // A load cancelled mid-sample leaves uncommitted bytes buffered; they must not
        // reach the first post-seek sample. seek() runs on the loader thread, like read().
        output?.discardPendingSampleData()
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

/** Wraps an [ExtractorOutput] to swap video [TrackOutput]s for DV-rewriting ones. */
@UnstableApi
private class DolbyVisionExtractorOutput(
    private val delegate: ExtractorOutput,
    private val config: DolbyVisionConversionConfig,
    private val stripDvRpu: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false
) : ExtractorOutput {

    private val videoTracks = HashMap<Int, NativeOptimizedVideoTrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput {
        if (type != C.TRACK_TYPE_VIDEO) return delegate.track(id, type)
        return videoTracks.getOrPut(id) {
            NativeOptimizedVideoTrackOutput(
                delegate = delegate.track(id, type),
                config = config,
                stripDvRpu = stripDvRpu,
                stripHdr10PlusSei = stripHdr10PlusSei
            )
        }
    }

    fun discardPendingSampleData() {
        videoTracks.values.forEach { it.discardPendingSampleData() }
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

@UnstableApi
private class NativeOptimizedVideoTrackOutput(
    private val delegate: TrackOutput,
    private val config: DolbyVisionConversionConfig,
    private val stripDvRpu: Boolean = false,
    private val stripHdr10PlusSei: Boolean = false
) : TrackOutput {

    private var pendingBuf = ByteArray(0)
    private var pendingLen = 0
    private var inputScratch = ByteArray(0)
    private val scratch = ParsableByteArray()

    // This stream's native output buffer (rewritten sample); loader thread only.
    private val nativeOut = DoviBridge.OutBuffer()

    private var converting = false
    private var shouldProcess = false
    private var profile: Int? = null
    private var codecs: String? = null
    // Item 2 + DV7 F3: once-per-stream first-RPU probe state (diagnostics only).
    private var metadataProbed = false
    private var metadataProbeAttempts = 0

    private fun ensurePendingCapacity(extra: Int) {
        val need = pendingLen + extra
        if (pendingBuf.size < need) {
            var newSize = if (pendingBuf.isEmpty()) 16 * 1024 else pendingBuf.size
            while (newSize < need) newSize = newSize shl 1
            pendingBuf = pendingBuf.copyOf(newSize)
        }
    }

    private fun ensureInputScratch(size: Int) {
        if (inputScratch.size < size) {
            var newSize = if (inputScratch.isEmpty()) 16 * 1024 else inputScratch.size
            while (newSize < size) newSize = newSize shl 1
            inputScratch = ByteArray(newSize)
        }
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        profile = DvCodecString.profile(format.codecs)
        converting = config.active && config.shouldConvert(profile)

        val strippedCodecs = if (stripDvRpu) stripDvCodecString(format.codecs) else null
        val codecsToUse = strippedCodecs
            ?: (if (converting) DvCodecString.rewriteToProfile8(format.codecs) else null)
            ?: format.codecs

        var outFormat = format
        // Source DV profile for the HUD, on the same paths as the MKV transformer: convert,
        // or strip of a DV track (re-signalled as HEVC, so the first-frame codec fallback
        // finds no profile; F-11).
        if (converting || strippedCodecs != null) {
            DolbyVisionConversionStats.recordSourceProfile(profile)
        }
        if (codecsToUse != null && codecsToUse != format.codecs) {
            outFormat = format.buildUpon().setCodecs(codecsToUse).build()
            // F-1: as the MKV transformer does; the track selector reads it to keep a
            // converted DV7 start out of tunnel mode.
            if (strippedCodecs == null) DolbyVisionConversionStats.recordCodecStringRewrite()
        }
        if (stripDvRpu && strippedCodecs != null) {
            outFormat = outFormat.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).build()
            // nt26: the stripped base layer is HDR10, but the container/DV Format
            // rarely carries colorInfo, so on some sinks (e.g. Amlogic AM9 Pro)
            // the Android display framework never learns the content is HDR and
            // leaves the display in SDR - PQ shown as SDR reads as a purple tint.
            // Populate colorInfo so the framework drives the HDR mode switch.
            // P7/P8.1 only (P5's base is IPT-PQ, not BT.2020).
            if ((profile == 7 || profile == 8) &&
                outFormat.colorInfo?.colorTransfer != C.COLOR_TRANSFER_ST2084
            ) {
                outFormat = outFormat.buildUpon().setColorInfo(hdr10ColorInfo()).build()
            }
        }

        val rewriteSamples = converting && !(profile == 5 && !config.convertDv5Rpu)
        val shouldStripDovi = stripDvRpu && strippedCodecs != null && strippedCodecs != format.codecs
        
        shouldProcess = isHevc(format) && (rewriteSamples || shouldStripDovi || stripHdr10PlusSei)
        codecs = outFormat.codecs
        delegate.format(outFormat)
    }

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        if (!shouldProcess) return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        ensureInputScratch(length)
        val read = input.read(inputScratch, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        if (read <= 0) return read
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
            ensurePendingCapacity(read)
            System.arraycopy(inputScratch, 0, pendingBuf, pendingLen, read)
            pendingLen += read
        } else {
            scratch.reset(inputScratch, read)
            delegate.sampleData(scratch, read, sampleDataPart)
        }
        return read
    }

    override fun sampleData(
        data: ParsableByteArray,
        length: Int,
        sampleDataPart: Int
    ) {
        if (!shouldProcess || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN || length <= 0) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        ensurePendingCapacity(length)
        data.readBytes(pendingBuf, pendingLen, length)
        pendingLen += length
    }

    /** Drops buffered bytes of a sample that will never be committed (extractor seek). */
    fun discardPendingSampleData() {
        pendingLen = 0
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (!shouldProcess || pendingLen == 0) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }

        // TrackOutput contract: the sample is the `size` bytes ending `offset` bytes before
        // the last byte written; the trailing `offset` bytes belong to the next sample.
        if (size < 0 || offset < 0 || offset > pendingLen) {
            // Malformed metadata: pass everything through untouched.
            scratch.reset(pendingBuf, pendingLen)
            delegate.sampleData(scratch, pendingLen)
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            pendingLen = 0
            return
        }
        if (size > pendingLen - offset) {
            // Part of the sample never passed through this buffer (TS H265Reader writes
            // the first access unit's head before format() enables buffering, or
            // encryption data was forwarded directly): pass this sample through untouched
            // but keep the next sample's head buffered, so the next sample is sliced.
            val tail = pendingLen - offset
            scratch.reset(pendingBuf, tail)
            delegate.sampleData(scratch, tail)
            delegate.sampleMetadata(timeUs, flags, size, 0, cryptoData)
            if (offset > 0) System.arraycopy(pendingBuf, tail, pendingBuf, 0, offset)
            pendingLen = offset
            return
        }
        val sampleStart = pendingLen - offset - size
        if (sampleStart > 0) {
            // Bytes written before this sample that belong to no sample (e.g. a TS stream
            // joined mid-access-unit after a seek): drop them, as SampleQueue would.
            System.arraycopy(pendingBuf, sampleStart, pendingBuf, 0, pendingLen - sampleStart)
            pendingLen -= sampleStart
        }
        // The sample now occupies pendingBuf[0, size).

        // Item 2: read the RPU's static HDR metadata once per stream for
        // Diagnostics. Covers MP4/fMP4/TS on both the convert and strip paths
        // (both reach here with shouldProcess). Best-effort and read-only — it
        // never alters the sample or the output below. DV7 F3: on the convert
        // path the same first RPU also gives the EL type (FEL/MEL), as on MKV.
        if (!metadataProbed && metadataProbeAttempts < METADATA_PROBE_ATTEMPT_LIMIT &&
            DoviBridge.isAvailable()
        ) {
            metadataProbeAttempts++
            val rpu = HevcDvRpuStripper.findRpuNalAnnexB(pendingBuf, size)
            if (rpu != null) {
                metadataProbed = true
                val meta = DoviBridge.getRpuStaticMetadata(pendingBuf, rpu.first, rpu.second)
                DolbyVisionConversionStats.recordRpuMetadata(meta)
                if (converting) {
                    DolbyVisionConversionStats.recordElType(
                        DoviBridge.detectRpuElType(pendingBuf, rpu.first, rpu.second)
                    )
                }
                android.util.Log.i(
                    "DVMetaProbe",
                    "hookB rpuLen=${rpu.second} meta=${meta?.toDiagnosticLine() ?: "null"}"
                )
            }
        }

        val mode = if (converting) config.conversionMode(profile) else 1

        // Process in native C++ layer
        val written = DoviBridge.processVideoSampleNonAllocating(
            sample = pendingBuf,
            sampleLen = size,
            out = nativeOut,
            convertDovi = converting,
            doviMode = mode,
            stripDoviRpu = stripDvRpu,
            stripHdr10Plus = stripHdr10PlusSei
        )

        val useRewritten = written > 0
        val outputData = if (useRewritten) nativeOut.bytes else pendingBuf
        val outputLen = if (useRewritten) written else size

        scratch.reset(outputData, outputLen)
        delegate.sampleData(scratch, outputLen)
        delegate.sampleMetadata(timeUs, flags, outputLen, 0, cryptoData)

        if (offset > 0) {
            System.arraycopy(pendingBuf, size, pendingBuf, 0, offset)
        }
        pendingLen = offset
    }

    private companion object {
        // Item 2: bound the diagnostics RPU probe so a mis-signalled stream that
        // never yields an RPU can't walk NALs on every sample indefinitely.
        const val METADATA_PROBE_ATTEMPT_LIMIT = 30

        fun stripDvCodecString(codecs: String?): String? {
            if (codecs.isNullOrBlank()) return null
            return Regex("(?i)(dvhe|dvh1)\\.[0-9]+\\.[0-9]+")
                .replace(codecs.trim()) { "hvc1.2.4.L153.B0" }
                .takeIf { it != codecs }
        }

        // The native walker parses HEVC NAL headers; AVC / AV1 samples must not reach it.
        fun isHevc(format: Format): Boolean = when (format.sampleMimeType) {
            MimeTypes.VIDEO_H265 -> true
            MimeTypes.VIDEO_DOLBY_VISION -> format.codecs?.trim()?.lowercase()
                ?.let { it.startsWith("dvhe") || it.startsWith("dvh1") } == true
            else -> false
        }
    }
}
