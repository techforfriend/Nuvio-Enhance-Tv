package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.core.stream.PreferredSource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream

object StreamAutoPlaySelector {
    fun orderAddonStreams(
        streams: List<AddonStreams>,
        installedOrder: List<String>
    ): List<AddonStreams> {
        if (streams.isEmpty()) return streams

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        if (installedOrder.isEmpty()) return streams
        val (addonEntries, otherEntries) = streams.partition { it.addonName in addonRankByName }
        val orderedAddons = addonEntries.sortedBy { addonRankByName.getValue(it.addonName) }
        return orderedAddons + otherEntries
    }

    /**
     * Whether auto-play may pick [stream]: the rule the Stream screen's press opens by
     * (`StreamPlaybackInfo.hasTarget`, Spec 84 S2; Spec 86 R8): a url, or a torrent with its info
     * hash (a magnet or `torrent:` link with no readable hash opens nothing). External links (error
     * pages, web links) never auto-play.
     */
    private fun isPlayable(stream: Stream): Boolean {
        if (stream.isExternal()) return false
        return stream.getStreamUrl() != null || (stream.isTorrent() && stream.getEffectiveInfoHash() != null)
    }

    fun selectAutoPlayStream(
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        filmSizeFloors: Boolean = false,
        demoted: ((Stream) -> Boolean)? = null,
        continuation: ContinuationTarget? = null,
        preferred: PreferredSource? = null,
        softwareDecoded: ((Stream) -> Boolean)? = null
    ): Stream? {
        if (streams.isEmpty()) return null

        val sourceScopedStreams = when (source) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.addonName in installedAddonNames }
        }
        // The addon allow-list only narrows installed-addon streams; groups under
        // other names are not subject to it.
        // Spec 65 P5 / Spec 86 R7: a demoted stream (recently failed, or reported uncached) goes
        // behind the rest in every step (binge group, first stream, regex, and after the quality
        // rank), never out.
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            !isAddonStream || selectedAddons.isEmpty() || stream.addonName in selectedAddons
        }.demotedLast(demoted)
        if (candidateStreams.isEmpty()) return null

        // Spec 65 P6 (D20): the source the viewer prefers for the show comes before the playing
        // source and the binge group, in every mode (MANUAL too) and whatever the binge settings:
        // they asked for it by hand. The order the list shows ([PreferredSource.matchesIn]), a
        // demoted match behind the others.
        if (preferred != null) {
            preferred.matchesIn(candidateStreams.filter { isPlayable(it) }).demotedLast(demoted)
                .firstOrNull()?.let { return it }
        }

        // Binge group matching takes priority over mode — even in MANUAL mode,
        // a persisted binge group should auto-play without showing the picker.
        // A group that names no release (BingeGroups) counts as absent.
        val targetBingeGroup = preferredBingeGroup?.trim()
            ?.takeIf(BingeGroups::namesRelease).orEmpty()
        if (continuation != null) {
            // Spec 65 P7: the player's episode press keeps the playing source (StreamContinuation);
            // it replaces the group match, so preferredBingeGroup is not read then.
            StreamContinuation.pick(candidateStreams.filter { isPlayable(it) }, continuation)?.let { return it }
        } else if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            val bingeGroupMatch = candidateStreams.firstOrNull { stream ->
                stream.behaviorHints?.bingeGroup == targetBingeGroup && isPlayable(stream)
            }
            if (bingeGroupMatch != null) return bingeGroupMatch
        }

        // When bingeGroupOnly is set (MANUAL mode with only binge-group
        // preference enabled), don't fall back to a non-matching stream —
        // return null so the caller shows the stream picker instead.
        if (bingeGroupOnly) return null

        // Spec 65 P8 (D15): in the first-stream and regex steps a stream this device decodes in
        // software ([softwareDecoded]) plays only when no other candidate of the step does. The
        // steps above follow a choice the viewer made by hand and are not second-guessed (D2); the
        // quality rank has its own encode exclusion (AV1) and fallback, left as they are.
        return when (mode) {
            StreamAutoPlayMode.MANUAL -> null
            StreamAutoPlayMode.FIRST_STREAM ->
                candidateStreams.filter { isPlayable(it) }.firstHardwareDecoded(softwareDecoded, demoted)
            StreamAutoPlayMode.QUALITY_RANK ->
                StreamQualityRank.rank(candidateStreams.filter { isPlayable(it) }, filmSizeFloors)
                    .demotedLast(demoted).firstOrNull()
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()
 
                // Try to compile the user regex
                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                if (userRegex == null) return null

                // Auto-extract exclusion patterns from negative lookaheads
                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                // Exclusion words are extracted from the user's pattern, so they are
                // escaped before joining: a metachar word ("(", "+") must degrade to
                // literal matching, not make the whole alternation uncompilable and
                // silently drop EVERY exclusion. The runCatching stays as a last
                // resort (batch-1 C).
                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    runCatching {
                        Regex("\\b(${exclusionWords.joinToString("|") { Regex.escape(it) }})\\b", RegexOption.IGNORE_CASE)
                    }.getOrNull()
                } else null

                // 1. Build list of ALL regex‑matching streams
                val matchingStreams = candidateStreams.filter { stream ->
                    if (!isPlayable(stream)) return@filter false

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.title.orEmpty()).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(stream.getStreamUrl().orEmpty())
                        if (stream.isTorrent()) append(' ').append(stream.infoHash.orEmpty())
                        // The release name often lives only here (upstream `b4a70e6f0` reads it
                        // for media servers; addons set it too).
                        stream.behaviorHints?.filename?.let { append(' ').append(it) }
                    }

                    // Must match include pattern
                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    // Must NOT match exclusion pattern
                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }

                matchingStreams.firstHardwareDecoded(softwareDecoded, demoted)
            }

        }
    }

    /**
     * The first stream neither [softwareDecoded] nor demoted ([demoted]: recently failed or reported
     * uncached), else the first of all: a working software-decoded stream comes before a demoted
     * one, as [demotedLast] left them. Asked stream by stream, front first: the usual pick costs one
     * answer (a parse of that stream's facts).
     */
    private fun List<Stream>.firstHardwareDecoded(
        softwareDecoded: ((Stream) -> Boolean)?,
        demoted: ((Stream) -> Boolean)?
    ): Stream? {
        if (softwareDecoded == null) return firstOrNull()
        return firstOrNull { demoted?.invoke(it) != true && !softwareDecoded(it) } ?: firstOrNull()
    }

    /** [this] with the [demoted] streams moved behind the rest, order otherwise kept. */
    private fun List<Stream>.demotedLast(demoted: ((Stream) -> Boolean)?): List<Stream> {
        if (demoted == null || isEmpty()) return this
        val (late, early) = partition(demoted)
        return if (late.isEmpty()) this else early + late
    }
}
