package com.nuvio.tv.core.player

import com.nuvio.tv.core.stream.limitKey
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2's safety argument, as executable assertions.
 *
 * Deliberately pure JVM: no Robolectric, no ViewModel harness. Five of the 27
 * failures in the recorded baseline are UncompletedCoroutinesError from
 * ViewModel-harness tests, and the gate is the last thing that should be
 * verified by a flaky mechanism.
 */
class PrefetchedSelectionGateTest {

    private fun inputs(
        mode: StreamAutoPlayMode = StreamAutoPlayMode.QUALITY_RANK,
        bingeGroup: String? = null,
        hiddenKeys: Set<String> = emptySet()
    ) = AutoPlaySelection.Inputs(
        mode = mode,
        regexPattern = "",
        source = StreamAutoPlaySource.ALL_SOURCES,
        installedAddonNames = setOf("Torrentio"),
        selectedAddons = emptySet(),
        preferredBingeGroup = bingeGroup,
        hiddenKeys = hiddenKeys
    )

    private fun snapshot(
        mode: StreamAutoPlayMode = StreamAutoPlayMode.QUALITY_RANK,
        order: List<String> = listOf("Torrentio"),
        hiddenKeys: Set<String> = emptySet()
    ) = SelectionSnapshot(
        inputs = inputs(mode, hiddenKeys = hiddenKeys),
        installedAddonOrder = order
    )

    private fun stream(name: String) = Stream(
        name = name,
        title = null,
        description = null,
        url = "https://example.invalid/$name",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Torrentio",
        addonLogo = null
    )

    @Test
    fun `a matching snapshot resolves to the winner from the live list`() {
        val winner = stream("4K TB Instant")
        val live = listOf(stream("1080p WEB"), stream("4K TB Instant"))
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), winner, live),
            snapshot = snapshot(),
            streams = live
        )
        assertTrue(outcome is SelectionOutcome.Hit)
        assertEquals("4K TB Instant", (outcome as SelectionOutcome.Hit).stream.name)
    }

    @Test
    fun `the live instance is returned, not the cached twin`() {
        // applySuccess badge-merges via copy() before the ranking pass, so the
        // cached winner can be a badge-less twin. The gate must hand back the
        // instance that is actually in the presented list.
        val cachedTwin = stream("4K TB Instant")
        val liveInstance = stream("4K TB Instant").copy(badges = listOf(StreamBadge(name = "DV")))
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), cachedTwin, listOf(cachedTwin)),
            snapshot = snapshot(),
            streams = listOf(liveInstance)
        )
        assertTrue(outcome is SelectionOutcome.Hit)
        assertTrue((outcome as SelectionOutcome.Hit).stream === liveInstance)
    }

    @Test
    fun `no prefetched entry falls back to a live rank`() {
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = null,
            snapshot = snapshot(),
            streams = listOf(stream("4K TB Instant"))
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_NO_ENTRY,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    @Test
    fun `a changed setting falls back to a live rank`() {
        val winner = stream("4K TB Instant")
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), winner, listOf(winner)),
            snapshot = snapshot(mode = StreamAutoPlayMode.FIRST_STREAM),
            streams = listOf(winner)
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_INPUTS_CHANGED,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    // Spec 65 P5: a failure recorded between the prefetch and the press changes the snapshot.
    @Test
    fun `a failure recorded after the rank falls back to a live rank`() {
        val winner = stream("4K TB Instant")
        val failed = snapshot().let {
            it.copy(
                inputs = it.inputs.copy(
                    failures = com.nuvio.tv.core.stream.FailureSnapshot(excluded = emptySet(), demoted = setOf("u:1"))
                )
            )
        }
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), winner, listOf(winner)),
            snapshot = failed,
            streams = listOf(winner)
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_INPUTS_CHANGED,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    // Spec 65 P6: a source preferred (or no longer) between the prefetch and the press changes the snapshot.
    @Test
    fun `a preferred source set after the rank falls back to a live rank`() {
        val winner = stream("4K TB Instant")
        val preferring = snapshot().let {
            it.copy(
                inputs = it.inputs.copy(
                    preferred = com.nuvio.tv.core.stream.PreferredSource(
                        "Torrentio", com.nuvio.tv.domain.model.ReleaseResolution.P1080
                    )
                )
            )
        }
        val setAfter = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), winner, listOf(winner)),
            snapshot = preferring,
            streams = listOf(winner)
        )
        assertEquals(PrefetchedSelectionGate.REASON_INPUTS_CHANGED, (setAfter as SelectionOutcome.Live).reason)
        val clearedAfter = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(preferring, winner, listOf(winner)),
            snapshot = snapshot(),
            streams = listOf(winner)
        )
        assertEquals(PrefetchedSelectionGate.REASON_INPUTS_CHANGED, (clearedAfter as SelectionOutcome.Live).reason)
        // Unchanged: the prefetched winner stands.
        val same = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(preferring, winner, listOf(winner)),
            snapshot = preferring,
            streams = listOf(winner)
        )
        assertEquals(SelectionOutcome.Hit(winner), same)
    }

    @Test
    fun `a reordered addon list falls back even though the name set is equal`() {
        // installedAddonNames is a Set, so a reorder compares equal there.
        // orderAddonStreams consumes the ordered list and rank() is a stable
        // sort, so a reorder can change which stream wins a tie.
        val winner = stream("4K TB Instant")
        val prefetched = PrefetchedSelection(
            snapshot(order = listOf("Torrentio", "Comet")),
            winner,
            listOf(winner)
        )
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = prefetched,
            snapshot = snapshot(order = listOf("Comet", "Torrentio")),
            streams = listOf(winner)
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_INPUTS_CHANGED,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    @Test
    fun `a winner absent from the live list falls back to a live rank`() {
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), stream("4K TB Instant"), listOf(stream("1080p WEB"))),
            snapshot = snapshot(),
            streams = listOf(stream("1080p WEB"))
        )
        assertEquals(
            PrefetchedSelectionGate.REASON_KEY_MISS,
            (outcome as SelectionOutcome.Live).reason
        )
    }

    // -- Spec 36/S8 (B1-18): the winner holds only for the list it was ranked from --

    private fun liveReason(ranked: List<Stream>, live: List<Stream>): String? {
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(), ranked.last(), ranked),
            snapshot = snapshot(),
            streams = live
        )
        return (outcome as? SelectionOutcome.Live)?.reason
    }

    @Test
    fun `a stream that arrived after the rank forces a live rank`() {
        val ranked = listOf(stream("1080p WEB"), stream("4K TB Instant"))
        assertEquals(
            PrefetchedSelectionGate.REASON_CANDIDATES_CHANGED,
            liveReason(ranked, ranked + stream("4K REMUX Instant"))
        )
    }

    @Test
    fun `a reordered list forces a live rank`() {
        val ranked = listOf(stream("1080p WEB"), stream("4K TB Instant"))
        assertEquals(PrefetchedSelectionGate.REASON_CANDIDATES_CHANGED, liveReason(ranked, ranked.reversed()))
    }

    @Test
    fun `a stream whose fields changed forces a live rank`() {
        val ranked = listOf(stream("1080p WEB"), stream("4K TB Instant"))
        val live = listOf(ranked[0], ranked[1].copy(title = "now cached"))
        assertEquals(PrefetchedSelectionGate.REASON_CANDIDATES_CHANGED, liveReason(ranked, live))
    }

    @Test
    fun `badges added after the rank still hit`() {
        val ranked = listOf(stream("1080p WEB"), stream("4K TB Instant"))
        val live = ranked.map { it.copy(badges = listOf(StreamBadge(name = "HDR"))) }
        assertEquals(null, liveReason(ranked, live))
    }

    // -- Spec 51 P5: the stream list limit's hidden set is part of the snapshot --

    private fun limitReason(
        rankedHidden: Set<String>,
        liveHidden: Set<String>,
        ranked: List<Stream>,
        live: List<Stream>,
        liveMode: StreamAutoPlayMode = StreamAutoPlayMode.QUALITY_RANK
    ): String? {
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snapshot(hiddenKeys = rankedHidden), ranked.last(), ranked),
            snapshot = snapshot(mode = liveMode, hiddenKeys = liveHidden),
            streams = live
        )
        return (outcome as? SelectionOutcome.Live)?.reason
    }

    @Test
    fun `the same hidden set over the same candidates hits`() {
        // A prefetch and a press that computed one set (whatever runtime each knew) agree.
        val ranked = listOf(stream("2160p REMUX"), stream("4K TB Instant"))
        val hidden = setOf(ranked.first().limitKey())
        assertEquals(null, limitReason(hidden, hidden, ranked, ranked))
    }

    @Test
    fun `only the hidden set differing over the same candidates names hidden-changed`() {
        val ranked = listOf(stream("2160p REMUX"), stream("4K TB Instant"))
        assertEquals(
            PrefetchedSelectionGate.REASON_HIDDEN_CHANGED,
            limitReason(emptySet(), setOf(ranked.first().limitKey()), ranked, ranked)
        )
    }

    @Test
    fun `a late addon moving the candidates and the hidden set names candidates-changed`() {
        val ranked = listOf(stream("1080p WEB"), stream("4K TB Instant"))
        val late = stream("4K REMUX")
        assertEquals(
            PrefetchedSelectionGate.REASON_CANDIDATES_CHANGED,
            limitReason(emptySet(), setOf(late.limitKey()), ranked, ranked + late)
        )
    }

    @Test
    fun `a hit returns the winner, not an earlier copy of the same torrent file on another service`() {
        // Same addon, infoHash and file: one badgeMergeKey. The first copy is the one the limit hides.
        val hiddenCopy = stream("f").copy(url = "https://real-debrid.com/d/f.mkv", infoHash = "abc")
        val winner = stream("f").copy(url = "https://premiumize.me/d/f.mkv", infoHash = "abc")
        val live = listOf(hiddenCopy, winner)
        val snap = snapshot(hiddenKeys = setOf(hiddenCopy.limitKey()))
        val outcome = PrefetchedSelectionGate.resolve(
            prefetched = PrefetchedSelection(snap, winner, live),
            snapshot = snap,
            streams = live
        )
        assertTrue((outcome as SelectionOutcome.Hit).stream === winner)
    }

    @Test
    fun `another setting changing beside the hidden set stays inputs-changed`() {
        val ranked = listOf(stream("1080p WEB"), stream("4K TB Instant"))
        assertEquals(
            PrefetchedSelectionGate.REASON_INPUTS_CHANGED,
            limitReason(emptySet(), setOf("k"), ranked, ranked, liveMode = StreamAutoPlayMode.FIRST_STREAM)
        )
    }

    // Spec 65 S3: the press's identity is not compared; the guard checks the cached winner instead.
    @Test
    fun `identity is checked against the winner, not compared`() {
        fun named(filename: String) = stream(filename).copy(
            behaviorHints = com.nuvio.tv.domain.model.StreamBehaviorHints(
                notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null, filename = filename
            )
        )
        val wrong = named("Show.S01E07.2160p.WEB.mkv")
        val right = named("Show.S01E06.1080p.WEB.mkv")
        val live = listOf(wrong, right)
        val identity = com.nuvio.tv.core.stream.ContentIdentity(
            title = "Show", year = 2020, season = 1, episode = 6, film = false
        )
        val press = snapshot().copy(inputs = snapshot().inputs.copy(identity = identity))

        val rightWinner = PrefetchedSelection(snapshot(), right, live)
        assertEquals(SelectionOutcome.Hit(right), PrefetchedSelectionGate.resolve(rightWinner, press, live))

        val wrongWinner = PrefetchedSelection(snapshot(), wrong, live)
        assertEquals(
            SelectionOutcome.Live(PrefetchedSelectionGate.REASON_IDENTITY_REJECTED),
            PrefetchedSelectionGate.resolve(wrongWinner, press, live)
        )
        // A list of only wrong files: the winner is still rejected (D7, no escape).
        val onlyWrong = listOf(wrong)
        val wrongOnly = PrefetchedSelection(snapshot(), wrong, onlyWrong)
        assertEquals(
            SelectionOutcome.Live(PrefetchedSelectionGate.REASON_IDENTITY_REJECTED),
            PrefetchedSelectionGate.resolve(wrongOnly, press, onlyWrong)
        )
        // Another title string at the press is not an inputs change.
        val otherTitle = press.copy(inputs = press.inputs.copy(identity = identity.copy(title = "Show (US)")))
        assertEquals(SelectionOutcome.Hit(right), PrefetchedSelectionGate.resolve(rightWinner, otherTitle, live))
    }

    @Test
    fun `the film flag is compared`() {
        val a = stream("a")
        val prefetched = PrefetchedSelection(snapshot(), a, listOf(a))
        val film = com.nuvio.tv.core.stream.ContentIdentity(title = "a", year = 2020, season = null, episode = null, film = true)
        val press = snapshot().copy(inputs = snapshot().inputs.copy(identity = film, filmSizeFloors = true))
        assertEquals(
            SelectionOutcome.Live(PrefetchedSelectionGate.REASON_INPUTS_CHANGED),
            PrefetchedSelectionGate.resolve(prefetched, press, listOf(a))
        )
        val prefetchedFilm = PrefetchedSelection(snapshot().copy(inputs = snapshot().inputs.copy(filmSizeFloors = true)), a, listOf(a))
        assertEquals(SelectionOutcome.Hit(a), PrefetchedSelectionGate.resolve(prefetchedFilm, press, listOf(a)))
    }
}
