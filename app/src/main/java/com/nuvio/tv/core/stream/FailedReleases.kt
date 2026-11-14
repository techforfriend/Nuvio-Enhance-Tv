package com.nuvio.tv.core.stream

import android.content.Context
import com.nuvio.tv.data.local.localStoreJson
import com.nuvio.tv.domain.model.Stream
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Why a playback was abandoned (Spec 65 P5, D8). Only [WRONG_FILE] is caused by the release
 * itself (Spec 35's "Pick another stream"), so only it is remembered by release and excluded from
 * autoplay; the rest are about the link or the network (a debrid URL expired, an error page, lost
 * chunks, no first frame after every retry, a not-cached placeholder), remembered by URL only and
 * ranked last for a day, which is also Spec 70 R4's marking window (D3).
 */
enum class FailureReason(val horizon: Duration, val releaseCaused: Boolean) {
    WRONG_FILE(30.days, releaseCaused = true),
    DEAD_LINK(1.days, releaseCaused = false),
    NO_START(1.days, releaseCaused = false),
    CORRUPT(1.days, releaseCaused = false),
    PLACEHOLDER(1.days, releaseCaused = false)
}

/**
 * Spec 75 S1: what went wrong, in the plain terms the error box, the failover line and the Sources
 * marking speak (D1), finer than [FailureReason], which keeps deciding ranking and exclusion. A
 * cause never changes how long an entry lives or whether it excludes.
 */
enum class FailureCause {
    LINK_EXPIRED, BLOCKED, REMOVED, RATE_LIMITED, SERVER_DOWN, OTHER_HTTP, NO_CONNECTION,
    WEB_PAGE, TINY_FILE, TEXT, LOGIN, UNREADABLE, TOO_SLOW, NO_DATA, PLACEHOLDER,
    VIDEO_UNSUPPORTED, DECODER_STUCK, CORRUPT, FROZEN, AUDIO_FAILED, TORRENT, ENGINE, NO_URL,
    WRONG_FILE, UNKNOWN
}

/**
 * The cause of one failure plus the speed facts a TOO_SLOW mark shows ("18 of 70 Mbit/s"): what
 * arrived and what the file needs, both Mbit/s, null when not measured.
 */
data class FailureMark(
    val cause: FailureCause,
    val measuredMbps: Double? = null,
    val neededMbps: Double? = null
)

/** A failure under [FailedReleases.MARK_WINDOW] old, for the Sources marking: its mark and when (epoch ms). */
data class RecentFailure(val reason: FailureReason, val mark: FailureMark?, val atEpochMs: Long)

/**
 * The store's marks as of one moment, [readAtEpochMs] (Spec 75 S1): every entry younger than
 * [FailedReleases.MARK_WINDOW], by key. Read once per list, then [markFor] per row; a mark's age
 * counts to [readAtEpochMs].
 */
class RecentFailures internal constructor(
    private val byKey: Map<String, RecentFailure>,
    val readAtEpochMs: Long
) {
    /** The URL key first, then the release keys; the newest when several match. */
    fun markFor(stream: Stream): RecentFailure? {
        if (byKey.isEmpty()) return null
        val byUrl = FailedReleases.urlKey(stream.getStreamUrl())?.let(byKey::get)
        val byRelease = FailedReleases.releaseKeysOf(stream).mapNotNull(byKey::get)
        return (listOfNotNull(byUrl) + byRelease).maxByOrNull { it.atEpochMs }
    }

    fun isEmpty(): Boolean = byKey.isEmpty()

    companion object {
        val EMPTY = RecentFailures(emptyMap(), readAtEpochMs = 0L)
    }
}

/**
 * What the playing stream was, for [FailedReleases.record]: its list entry when the player has
 * one, plus the player's own facts (a stream opened from the Stream screen has no list entry
 * until the sources panel loads). [filename] and [size] must be the facts a candidate is keyed by
 * (the addon's filename hint, the size from the listing), not a URL-derived name or the hasher's
 * exact size, or the release key never matches.
 */
data class FailedPlayback(
    val urls: List<String?>,
    val listEntry: Stream? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val filename: String? = null,
    val size: Long? = null
)

/**
 * The store as of one moment, by key: what autoplay never picks ([excluded]) and what it ranks
 * last ([demoted]). A plain value, so a prefetch snapshot carrying it differs from the press's
 * once a failure is recorded in between, and the gate re-ranks live (PrefetchedSelectionGate).
 */
data class FailureSnapshot(val excluded: Set<String>, val demoted: Set<String>) {
    /** The URL key first; the release keys (a text size parse, a second hash) only on a miss. */
    fun excludes(stream: Stream): Boolean = excluded.isNotEmpty() &&
        (FailedReleases.urlKey(stream.getStreamUrl()) in excluded || FailedReleases.releaseKeysOf(stream).any { it in excluded })

    /** Demotions are keyed by URL only (link-side reasons, D8), so only the URL is hashed. */
    fun demotes(stream: Stream): Boolean = demoted.isNotEmpty() && FailedReleases.urlKey(stream.getStreamUrl()) in demoted

    /**
     * The streams of [candidates] this snapshot demotes, by identity, or null when none: one hash
     * per stream for a whole select instead of one per check.
     */
    fun demotedAmong(candidates: List<Stream>): Set<Stream>? {
        if (demoted.isEmpty()) return null
        val found = Collections.newSetFromMap(IdentityHashMap<Stream, Boolean>())
        candidates.filterTo(found, ::demotes)
        return found.takeIf { it.isNotEmpty() }
    }

    companion object {
        val EMPTY = FailureSnapshot(emptySet(), emptySet())
    }
}

/** Where [FailedReleases] persists: one JSON string. */
interface FailedReleaseStorage {
    fun load(): String?
    fun save(value: String)
}

/** App-private SharedPreferences, device-wide: a failed file fails for every profile. */
private class SharedPreferencesFailedReleaseStorage(context: Context) : FailedReleaseStorage {
    // Opened at injection so the file loads in the background before the first select reads it.
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): String? = preferences.getString(KEY, null)

    override fun save(value: String) {
        preferences.edit().putString(KEY, value).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "failed_releases"
        const val KEY = "entries"
    }
}

/**
 * One stored failure. [cause] and the speeds arrived with Spec 75 (absent in older entries); the
 * store's Json coerces a cause this build does not know (a downgrade) to null rather than dropping
 * every entry.
 */
@Serializable
internal data class FailedEntry(
    val reason: FailureReason,
    val at: Long,
    val cause: FailureCause? = null,
    val measuredMbps: Double? = null,
    val neededMbps: Double? = null
) {
    fun mark(): FailureMark? = cause?.let { FailureMark(it, measuredMbps, neededMbps) }
}

/**
 * Spec 65 P5: the one failure memory (D3). Autoplay (AutoPlaySelection) never picks a stream
 * recorded [FailureReason.WRONG_FILE] in the last 30 days and ranks one recorded for another
 * reason in the last day below the rest. Manual picks are never blocked (D2).
 *
 * Keys: every URL of the failed play, sha256-hashed (no debrid token is stored), and for a
 * release-caused failure the release itself, so another link to the same file counts too: the
 * public torrent infoHash + file index in plain text, and a hash of filename + size. An infoHash
 * alone is no key: a season pack shares it across episodes. Capped at [MAX_ENTRIES], oldest out.
 * Release keys are not scoped to a title (deferred: Spec 65 S4 review).
 *
 * Shape after haaihond/NuvioTV `UnplayableReleases` (`69214fef3`, GPL-3.0), which likewise keeps
 * only failures the release repeats.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
@Singleton
class FailedReleases internal constructor(
    private val storage: FailedReleaseStorage,
    private val clock: Clock
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(SharedPreferencesFailedReleaseStorage(context), Clock.System)

    // Read only under the instance lock (every public method is @Synchronized).
    private val entries: MutableMap<String, FailedEntry> by lazy(LazyThreadSafetyMode.NONE) {
        decode(storage.load()).toMutableMap()
    }

    /** [mark] (Spec 75) is what the Sources list later shows for this failure; null keeps none. */
    @Synchronized
    fun record(reason: FailureReason, playback: FailedPlayback, mark: FailureMark? = null) {
        val keys = buildSet {
            playback.urls.forEach { url -> urlKey(url)?.let(::add) }
            playback.listEntry?.getStreamUrl()?.let { url -> urlKey(url)?.let(::add) }
            if (reason.releaseCaused) {
                playback.listEntry?.let { addAll(releaseKeysOf(it)) }
                addAll(releaseKeys(playback.infoHash, playback.fileIdx, playback.filename, playback.size))
            }
        }
        if (keys.isEmpty()) return
        val now = clock.now().toEpochMilliseconds()
        val all = entries
        keys.forEach { key ->
            val previous = all[key]
            if (!keepsLiveExclusion(previous, reason, now)) {
                all[key] = FailedEntry(reason, now, mark?.cause, mark?.measuredMbps.finiteSpeed(), mark?.neededMbps.finiteSpeed())
            }
        }
        all.entries.removeAll { (_, entry) -> expired(entry, now) }
        if (all.size > MAX_ENTRIES) {
            all.entries.sortedBy { it.value.at }.take(all.size - MAX_ENTRIES).map { it.key }.forEach(all::remove)
        }
        storage.save(storeJson.encodeToString(serializer, all.toMap()))
    }

    /** [url] is excluded now (a wrong file): one lookup, no snapshot. */
    @Synchronized
    fun isExcludedUrl(url: String): Boolean {
        val entry = urlKey(url)?.let { entries[it] } ?: return false
        return entry.reason.releaseCaused && !expired(entry, clock.now().toEpochMilliseconds())
    }

    /** The live entries as a [FailureSnapshot]; [FailureSnapshot.EMPTY] when there are none. */
    @Synchronized
    fun snapshot(): FailureSnapshot {
        val all = entries
        if (all.isEmpty()) return FailureSnapshot.EMPTY
        val now = clock.now().toEpochMilliseconds()
        val excluded = HashSet<String>()
        val demoted = HashSet<String>()
        all.forEach { (key, entry) ->
            if (expired(entry, now)) return@forEach
            if (entry.reason.releaseCaused) excluded += key else demoted += key
        }
        return if (excluded.isEmpty() && demoted.isEmpty()) FailureSnapshot.EMPTY else FailureSnapshot(excluded, demoted)
    }

    /** The entries under [MARK_WINDOW] old, for the Sources marking (Spec 75 D1); [RecentFailures.EMPTY] when none. */
    @Synchronized
    fun recent(): RecentFailures {
        val all = entries
        if (all.isEmpty()) return RecentFailures.EMPTY
        val now = clock.now().toEpochMilliseconds()
        val window = MARK_WINDOW.inWholeMilliseconds
        val found = HashMap<String, RecentFailure>()
        all.forEach { (key, entry) ->
            if (entry.at > now || now - entry.at > window) return@forEach
            found[key] = RecentFailure(entry.reason, entry.mark(), entry.at)
        }
        return if (found.isEmpty()) RecentFailures.EMPTY else RecentFailures(found, now)
    }

    /** A link-side failure never shortens a live wrong-file exclusion on the same key. */
    private fun keepsLiveExclusion(previous: FailedEntry?, reason: FailureReason, now: Long): Boolean =
        previous != null && previous.reason.releaseCaused && !reason.releaseCaused && !expired(previous, now)

    private fun expired(entry: FailedEntry, now: Long): Boolean =
        now - entry.at > entry.reason.horizon.inWholeMilliseconds || entry.at > now

    private fun decode(raw: String?): Map<String, FailedEntry> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            storeJson.decodeFromString(serializer, raw)
        } catch (_: SerializationException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }
    }

    companion object {
        internal const val MAX_ENTRIES = 500

        /** How long a failure is marked in the Sources list (Spec 75 D1), whatever its horizon. */
        val MARK_WINDOW: Duration = 1.days

        private val storeJson = Json(localStoreJson) { coerceInputValues = true }

        /** A speed the store can write: JSON has no NaN or Infinity (encoding one throws), nor a negative speed. */
        private fun Double?.finiteSpeed(): Double? = this?.takeIf { it.isFinite() && it >= 0.0 }

        private val serializer = MapSerializer(String.serializer(), FailedEntry.serializer())

        internal fun releaseKeysOf(stream: Stream): List<String> {
            val filename = stream.behaviorHints?.filename?.takeIf { it.isNotBlank() }
            return releaseKeys(
                infoHash = stream.getEffectiveInfoHash(),
                fileIdx = stream.getEffectiveFileIdx(),
                filename = filename,
                // The text parse only when it can form a key.
                size = filename?.let { StreamTextSizeParser.effectiveSizeBytes(stream) }
            )
        }

        internal fun releaseKeys(infoHash: String?, fileIdx: Int?, filename: String?, size: Long?): List<String> =
            listOfNotNull(
                infoHash?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && fileIdx != null }
                    ?.let { "h:$it:$fileIdx" },
                filename?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && size != null && size > 0 }
                    ?.let { "n:" + sha256("$it\n$size") }
            )

        internal fun urlKey(url: String?): String? = url?.trim()?.takeIf { it.isNotEmpty() }?.let { "u:" + sha256(it) }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHexString()
    }
}
