package com.nuvio.tv.core.cloudstream

/**
 * Decides whether a CloudStream plugin can run on this TV build.
 *
 * Ported from Nuvio Enhanced-2's `CloudStreamCompatibilityResolver`. The TV app ships no
 * precompiled cross-platform adapters (no KickTR equivalent), so every standard .cs3
 * package runs through the Android Dex runtime — exactly the path the full flavour of
 * Enhanced-2 uses on Android.
 */
object CloudStreamCompatibilityResolver {
    fun resolve(
        metadata: CloudStreamPluginMetadata,
        supportsAndroidDex: Boolean = true,
    ): CloudStreamCompatibility {
        return if (supportsAndroidDex) {
            CloudStreamCompatibility(
                runtimeKind = CloudStreamRuntimeKind.AndroidDex,
                platformSupport = CloudStreamPlatformSupport.AndroidOnly,
                reason = "Android TV builds execute this standard CloudStream .cs3 package with the embedded CloudStream runtime.",
            )
        } else {
            CloudStreamCompatibility(
                runtimeKind = CloudStreamRuntimeKind.UnsupportedAndroidDex,
                platformSupport = CloudStreamPlatformSupport.Unsupported,
                reason = "This standard .cs3 package contains Android DEX code, which cannot run here.",
            )
        }
    }
}

fun sortCloudStreamEpisodes(episodes: List<CloudStreamEpisode>): List<CloudStreamEpisode> =
    episodes.sortedWith(
        compareBy<CloudStreamEpisode>(
            { it.season ?: Int.MAX_VALUE },
            { it.episode ?: Int.MAX_VALUE },
            { it.name.lowercase() },
        ),
    )
