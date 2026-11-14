package com.nuvio.tv.core.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.nuvio.tv.core.stream.ReleaseFactsParser
import com.nuvio.tv.domain.model.ReleaseEncode
import com.nuvio.tv.core.stream.ReleaseFacts
import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What this device does to a stream it cannot play as released (Spec 65 P8, D15). */
enum class DecodeVerdict {
    /** No hardware decoder for the codec at that resolution: it decodes in software. */
    SOFTWARE,

    /** Dolby Vision on a display without it: it plays as HDR10. */
    REDUCED
}

/** A video codec at a resolution, as the release facts name them. */
data class VideoShape(val encode: ReleaseEncode, val resolution: ReleaseResolution)

/** True when [stream]'s parsed codec and resolution are one of these shapes. */
fun Set<VideoShape>.names(stream: Stream): Boolean {
    val facts = ReleaseFactsParser.factsFor(stream)
    return VideoShape(facts.encode, facts.resolution) in this
}

/**
 * What this device cannot play as released (Spec 65 P8): [softwareOnly] = the shapes no hardware
 * decoder here takes, [dolbyVisionAsHdr10] = the display reports HDR10 but no Dolby Vision.
 * [UNKNOWN] says nothing about any stream (D2).
 */
data class DecodeCapability(
    val softwareOnly: Set<VideoShape>,
    val dolbyVisionAsHdr10: Boolean
) {
    /** True when no stream can get a verdict, so callers need not parse any. */
    val saysNothing: Boolean get() = softwareOnly.isEmpty() && !dolbyVisionAsHdr10

    /** The verdict for a stream with these [facts]; null when it plays as released, or when its codec or resolution is unknown. */
    fun verdict(facts: ReleaseFacts): DecodeVerdict? = when {
        VideoShape(facts.encode, facts.resolution) in softwareOnly -> DecodeVerdict.SOFTWARE
        dolbyVisionAsHdr10 && facts.visualTags.any { it in ReleaseFactsParser.DV_FAMILY } -> DecodeVerdict.REDUCED
        else -> null
    }

    companion object {
        val UNKNOWN = DecodeCapability(softwareOnly = emptySet(), dolbyVisionAsHdr10 = false)

        /**
         * The resolutions a verdict is given for, with the frame each is probed at. Nothing under
         * 1080p: software decode is smooth there on the devices this fork runs on.
         */
        internal val PROBED_SIZES = mapOf(
            ReleaseResolution.P1080 to (1920 to 1080),
            ReleaseResolution.P1440 to (2560 to 1440),
            ReleaseResolution.P2160 to (3840 to 2160)
        )

        /** The codecs a verdict is given for. XviD / DivX are SD and software-decoded by design. */
        private val PROBED_MIMES = mapOf(
            ReleaseEncode.AV1 to MimeTypes.VIDEO_AV1,
            ReleaseEncode.HEVC to MimeTypes.VIDEO_H265,
            ReleaseEncode.AVC to MimeTypes.VIDEO_H264
        )

        @Volatile
        private var probedSoftwareOnly: Set<VideoShape>? = null

        /**
         * The shapes no hardware decoder here takes: what auto-play reads. The decoder list is
         * queried once per process, on [dispatcher] (a binder call the first time); two first
         * callers may both query, and store the same answer.
         */
        suspend fun softwareOnly(dispatcher: CoroutineDispatcher = Dispatchers.Default): Set<VideoShape> =
            probedSoftwareOnly ?: withContext(dispatcher) {
                probeSoftwareOnly().also {
                    probedSoftwareOnly = it
                    // Once per process: a wrong amber line or auto-play skip is traced from here.
                    Log.i(TAG, "softwareOnly=" + it.joinToString { shape -> "${shape.encode}@${shape.resolution}" })
                }
            }

        /**
         * This device's capability, for the rows. The display is read on every call, since a TV or
         * AVR change moves its HDR types.
         */
        suspend fun current(
            context: Context,
            dispatcher: CoroutineDispatcher = Dispatchers.Default
        ): DecodeCapability =
            DecodeCapability(softwareOnly(dispatcher), dolbyVisionAsHdr10 = displayShowsDolbyVisionAsHdr10(context))

        /** [current] when the decoders were already queried in this process, else null; never queries. */
        fun currentIfProbed(context: Context): DecodeCapability? = probedSoftwareOnly?.let {
            DecodeCapability(it, dolbyVisionAsHdr10 = displayShowsDolbyVisionAsHdr10(context))
        }

        private const val TAG = "DecodeCapability"

        /**
         * The probed shapes no hardware decoder takes. Asked of Media3's own decoder list (its size
         * alignment and device workarounds; a mime-presence check would count a software decoder).
         * A codec whose query fails yields no shape (D2).
         */
        @OptIn(UnstableApi::class)
        private fun probeSoftwareOnly(): Set<VideoShape> = buildSet {
            for ((encode, mime) in PROBED_MIMES) {
                val hardware = runCatching {
                    MediaCodecUtil.getDecoderInfos(mime, /* secure= */ false, /* tunneling= */ false)
                        .filter { it.hardwareAccelerated }
                }.getOrNull() ?: continue
                for ((resolution, size) in PROBED_SIZES) {
                    val (width, height) = size
                    val supported = hardware.any { info ->
                        runCatching {
                            info.isVideoSizeAndRateSupportedV21(width, height, Format.NO_VALUE.toDouble())
                        }.getOrDefault(true)
                    }
                    if (!supported) add(VideoShape(encode, resolution))
                }
            }
        }

        /** True only when the display reports its HDR types, HDR10 or HDR10+ among them and no Dolby Vision. */
        private fun displayShowsDolbyVisionAsHdr10(context: Context): Boolean {
            @Suppress("DEPRECATION")
            val types = runCatching {
                context.getSystemService(DisplayManager::class.java)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)?.hdrCapabilities?.supportedHdrTypes
            }.getOrNull() ?: return false
            return dolbyVisionAsHdr10(types)
        }

        internal fun dolbyVisionAsHdr10(hdrTypes: IntArray): Boolean =
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION !in hdrTypes &&
                (Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes ||
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdrTypes)
    }
}
