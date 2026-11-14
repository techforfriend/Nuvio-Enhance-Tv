@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.diagnostics.AbnormalExit
import com.nuvio.tv.core.diagnostics.AbnormalExitKind
import com.nuvio.tv.core.diagnostics.LastExitReason
import com.nuvio.tv.core.network.StreamSweepEngine
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.core.player.PlaybackConnectionEvents
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.components.FocusMarqueeText
import com.nuvio.tv.ui.components.marquee.MarqueeFormRow
import com.nuvio.tv.ui.components.marquee.MarqueeFormRowValue
import com.nuvio.tv.ui.components.marquee.MarqueeValueRow
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface ClearCwCacheEntryPoint {
    fun cwEnrichmentCache(): com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface ClearImageCacheEntryPoint {
    fun catalogRepository(): com.nuvio.tv.domain.repository.CatalogRepository
    fun homeRefreshSignal(): com.nuvio.tv.core.util.HomeRefreshSignal

    @com.nuvio.tv.core.di.Api
    fun apiOkHttpClient(): okhttp3.OkHttpClient

    @com.nuvio.tv.core.di.Validated
    fun validatedOkHttpClient(): okhttp3.OkHttpClient
}

/** Stage 23 chips (Q3), in order. Dolby Vision shows only while ExoPlayer can play (as built). */
private enum class AdvancedSection { GENERAL, STREAM_TEST, DEVICE_ASSESSMENT, DOLBY_VISION, CACHE }

/**
 * Spec 31, Advanced pane (sandbox u11-settings-marquee v6, stages 23-24): five chips over General,
 * Stream test, Device assessment, Dolby Vision and Cache. Advanced is the advanced home itself,
 * so it has no Advanced options group. Rows wait for the stored values (see the ViewModels); the
 * shell's entry requester lands on the selected section's first row.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun AdvancedSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: AdvancedSettingsViewModel = hiltViewModel()
) {
    // Null until loaded (see the ViewModel); rows wait for it.
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    // The playback store: the engine (Dolby Vision chip), the DV conversion override and the
    // last playback's diagnostics (stream URL and headers for both tests, the DV cards).
    // Settings are null until the store emits.
    val playbackVm: PlaybackSettingsViewModel = hiltViewModel()
    val playerSettings = playbackVm.playerSettings.collectAsStateWithLifecycle(initialValue = null).value
    val diagnostics by playbackVm.lastPlaybackDiagnostics.collectAsStateWithLifecycle(
        initialValue = LastPlaybackDiagnostics.EMPTY
    )

    // Stream Speed Test States. streamTestState is "Idle"/"Done"/"Error" or
    // the label of the pass currently running; results accumulate in order as
    // a dynamic list (the greedy sweep decides later passes from earlier ones,
    // so the pass set is not fixed upfront).
    var streamTestState by remember { mutableStateOf("Idle") }
    var streamPassResults by remember { mutableStateOf(listOf<Pair<String, Double?>>()) }
    // N3d-a2: why a row has no number, keyed by row label. Held beside the
    // results rather than inside them so the label-keyed update above keeps
    // working unchanged.
    var streamPassNotes by remember {
        mutableStateOf(mapOf<String, StreamSweepEngine.PassNote>())
    }
    var streamErrorMessage by remember { mutableStateOf<String?>(null) }
    var streamVerdict by remember { mutableStateOf<String?>(null) }
    // N6 V2 follow-on: the host that actually served this TEST's bytes. The
    // tester shares PlayerPlaybackNetworking.playbackHttpClient, so the
    // NET_CONN listener captures the test's own post-redirect host at zero
    // extra network cost; updated after each measured pass (a measured pass
    // proves bytes flowed during this test, so the holder is not a stale
    // playback value). Falls back at display time to the value persisted at
    // the last playback's first frame.
    var liveServingHost by remember { mutableStateOf<String?>(null) }

    val lastStreamUrl = diagnostics.streamUrl
    val lastHeadersJson = diagnostics.headersJson

    val lastHeadersMap = remember(lastHeadersJson) {
        if (!lastHeadersJson.isNullOrBlank()) {
            runCatching {
                val json = org.json.JSONObject(lastHeadersJson)
                val map = mutableMapOf<String, String>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = json.getString(key)
                }
                map
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
    }

    var estimatedBitrate by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(diagnostics) {
        estimatedBitrate = StreamSweepEngine.estimateBitrate(diagnostics, lastHeadersMap)
    }

    // Stream sweep: the algorithm now lives in core.network.StreamSweepEngine
    // (single source of truth, shared with the Device Assessment). This wrapper
    // only owns the Compose state; pass labels, stop rules, memory gating and
    // verdict strings are produced by the engine and are unchanged.
    fun runStreamDiagnostics() {
        if (lastStreamUrl.isNullOrBlank()) return
        scope.launch {
            streamPassResults = emptyList()
            streamPassNotes = emptyMap()
            streamErrorMessage = null
            streamVerdict = null
            liveServingHost = null

            val outcome = StreamSweepEngine.run(
                context = context,
                streamUrl = lastStreamUrl,
                headers = lastHeadersMap,
                estimatedBitrate = estimatedBitrate,
                onState = { streamTestState = it },
                onPassAdded = { label ->
                    streamPassResults = streamPassResults + (label to null)
                },
                onPassResult = { label, mbps, note ->
                    streamPassResults = streamPassResults.map { if (it.first == label) label to mbps else it }
                    if (note != null) streamPassNotes = streamPassNotes + (label to note)
                    if (mbps != null && mbps > 0.0) {
                        liveServingHost = PlaybackConnectionEvents.resolvedHost()
                    }
                }
            )

            streamErrorMessage = outcome.errorText
            streamVerdict = outcome.verdictText
            streamTestState = if (outcome.errorText != null) "Error" else "Done"
        }
    }

    // Device assessment state lives at screen level so switching chips or scrolling
    // result cards out of composition can't destroy them (lazy items are disposed;
    // the screen composable is not), and a mid-run scroll can't cancel the
    // sweep (the run launches on this screen's scope).
    val assessmentState = rememberDeviceAssessmentState()

    val listState = rememberLazyListState()

    // Keep a growing results card in view: each finished pass adds a line and the card can
    // run past the bottom of the pane. The keys include the note counts because a line that
    // resolves to "Rate-limited" or "Skipped" never gains a number.
    LaunchedEffect(
        assessmentState.passRows.size,
        assessmentState.passRows.count { it.second != null },
        assessmentState.passNotes.size,
        assessmentState.running
    ) {
        if (assessmentState.passRows.isNotEmpty()) listState.revealItemBottom("assessment_passes")
    }
    LaunchedEffect(
        streamPassResults.size, streamPassResults.count { it.second != null }, streamPassNotes.size, streamVerdict
    ) {
        if (streamPassResults.isNotEmpty()) listState.revealItemBottom("stream_speed_results")
    }

    val dvAvailable = playerSettings?.internalPlayerEngine?.usesExoPlayer == true
    val sections = remember(dvAvailable) {
        AdvancedSection.entries.filter { it != AdvancedSection.DOLBY_VISION || dvAvailable }
    }
    var section by rememberSaveable { mutableStateOf(AdvancedSection.GENERAL) }
    // A saved Dolby Vision pick shows General while the engine hides that chip.
    val shownSection = section.takeIf { it in sections } ?: AdvancedSection.GENERAL
    // Each section opens at its top (the sections share one list).
    LaunchedEffect(shownSection) { listState.scrollToItem(0) }
    val loaded = uiState != null && playerSettings != null

    SettingsPane(
        title = stringResource(R.string.settings_advanced),
        subtitle = stringResource(R.string.advanced_pane_subtitle),
        listState = listState,
        chips = if (loaded) {
            {
                SettingsSectionChips(
                    items = sections,
                    selected = shownSection,
                    label = { advancedSectionLabel(it) },
                    onSelect = { section = it }
                )
            }
        } else {
            null
        }
    ) {
        if (uiState == null || playerSettings == null) return@SettingsPane
        when (shownSection) {
            AdvancedSection.GENERAL -> generalItems(uiState, viewModel::onEvent, initialFocusRequester)
            AdvancedSection.STREAM_TEST -> {
                val isStreamRunning = streamTestState != "Idle" && streamTestState != "Done" && streamTestState != "Error"
                streamTestItems(
                    diagnostics = diagnostics,
                    liveServingHost = liveServingHost,
                    testState = streamTestState,
                    runningPass = streamTestState.takeIf { isStreamRunning },
                    estimatedBitrate = estimatedBitrate,
                    passResults = streamPassResults,
                    passNotes = streamPassNotes,
                    errorMessage = streamErrorMessage,
                    verdict = streamVerdict,
                    onRun = { if (!isStreamRunning) runStreamDiagnostics() },
                    initialFocusRequester = initialFocusRequester
                )
            }
            AdvancedSection.DEVICE_ASSESSMENT -> deviceAssessmentItems(
                state = assessmentState,
                diagnostics = diagnostics,
                entryFocusRequester = initialFocusRequester,
                onRun = {
                    runDeviceAssessment(
                        scope = scope,
                        context = context,
                        state = assessmentState,
                        settings = playerSettings,
                        diagnostics = diagnostics
                    )
                },
                onApply = {
                    runApplyAssessment(scope = scope, context = context, state = assessmentState)
                },
                onApplyPreset = {
                    runApplyShieldPreset(scope = scope, context = context, state = assessmentState)
                }
            )
            AdvancedSection.DOLBY_VISION -> {
                item(key = "dv_conversion_mode") {
                    DvConversionModeRow(
                        settings = playerSettings,
                        onSelect = { value -> scope.launch { playbackVm.setDv7LibdoviModeOverride(value) } },
                        modifier = Modifier.optionalFocusRequester(initialFocusRequester)
                    )
                }
                diagnosticsCardItems(
                    diagnostics = diagnostics,
                    dvCurrentlyEnabled = playerSettings.dv7HandlingMode != Dv7HandlingMode.OFF
                )
            }
            AdvancedSection.CACHE -> cacheItems(scope, initialFocusRequester)
        }
    }
}

/** Scrolls by the overflow when the item [key] runs past the list's bottom (no-op when off screen). */
private suspend fun LazyListState.revealItemBottom(key: Any) {
    // Let the newly added line be measured before reading layout info.
    withFrameNanos { }
    withFrameNanos { }
    val item = layoutInfo.visibleItemsInfo.find { it.key == key } ?: return
    val overflow = (item.offset + item.size) - layoutInfo.viewportEndOffset
    if (overflow > 0) animateScrollBy(overflow.toFloat() + 16f)
}

/** Stage 24: the run row, then once a run started the `.l11pass` results (verdict and caveat as built). */
private fun LazyListScope.streamTestItems(
    diagnostics: LastPlaybackDiagnostics,
    liveServingHost: String?,
    /** "Idle" / "Done" / "Error" or the label of the pass being measured. */
    testState: String,
    runningPass: String?,
    estimatedBitrate: Long?,
    passResults: List<Pair<String, Double?>>,
    passNotes: Map<String, StreamSweepEngine.PassNote>,
    errorMessage: String?,
    verdict: String?,
    onRun: () -> Unit,
    initialFocusRequester: FocusRequester?
) {
    item(key = "stream_speed_test") {
        StreamTestRow(
            streamUrl = diagnostics.streamUrl,
            diagnostics = diagnostics,
            liveServingHost = liveServingHost,
            runningPass = runningPass,
            onRun = onRun,
            modifier = Modifier.optionalFocusRequester(initialFocusRequester)
        )
    }
    if (testState == "Idle") return
    item(key = "stream_speed_results") {
        SettingsResultsPanel {
            estimatedBitrate?.takeIf { it > 0 }?.let { bitrate ->
                SettingsResultLine(
                    label = stringResource(R.string.stream_test_avg_bitrate),
                    value = stringResource(R.string.stream_test_unit_mbps, bitrate / 1_000_000.0)
                )
            }
            passResults.forEach { (label, speed) ->
                StreamTestResultRow(
                    label = label,
                    speed = speed,
                    note = passNotes[label],
                    isRunning = testState == label && speed == null && passNotes[label] == null
                )
            }
            if (testState == "Error" && errorMessage != null) {
                Text(
                    text = stringResource(R.string.stream_test_error_prefix, errorMessage),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = NuvioTheme.colors.Error
                )
            }
            verdict?.let {
                Text(text = it, style = NuvioTheme.textStyles.settingsResultLabel, color = NuvioPrimitives.white)
            }
            if (testState == "Done") {
                Text(
                    text = stringResource(R.string.stream_test_caveat),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = NuvioTheme.marquee.onImage3
                )
            }
        }
    }
}

@Composable
private fun advancedSectionLabel(section: AdvancedSection): String = stringResource(
    when (section) {
        AdvancedSection.GENERAL -> R.string.advanced_chip_general
        AdvancedSection.STREAM_TEST -> R.string.advanced_chip_stream_test
        AdvancedSection.DEVICE_ASSESSMENT -> R.string.assessment_section_header
        AdvancedSection.DOLBY_VISION -> R.string.advanced_chip_dolby_vision
        AdvancedSection.CACHE -> R.string.advanced_section_cache
    }
)

/** Stage 23: the Navigation switches, then Diagnostics (Spec 70 R1, sandbox spec-70-last-close-row v3). */
private fun LazyListScope.generalItems(
    state: AdvancedSettingsUiState,
    onEvent: (AdvancedSettingsEvent) -> Unit,
    initialFocusRequester: FocusRequester?
) {
    item(key = "general_section") {
        SettingsSectionLabel(text = stringResource(R.string.advanced_section_navigation))
    }
    item(key = "fast_horizontal_navigation") {
        SettingsSwitchRow(
            title = stringResource(R.string.advanced_fast_nav_title),
            subtitle = stringResource(R.string.advanced_fast_nav_subtitle),
            checked = state.fastHorizontalNavigationEnabled,
            onCheckedChange = { onEvent(AdvancedSettingsEvent.SetFastHorizontalNavigationEnabled(it)) },
            modifier = Modifier.optionalFocusRequester(initialFocusRequester)
        )
    }
    item(key = "focus_scrolling") {
        SettingsSwitchRow(
            title = stringResource(R.string.advanced_focus_scroll_title),
            subtitle = stringResource(R.string.advanced_focus_scroll_subtitle),
            checked = state.smoothBringIntoViewEnabled,
            onCheckedChange = { onEvent(AdvancedSettingsEvent.SetSmoothBringIntoViewEnabled(it)) }
        )
    }
    item(key = "addon_health") {
        SettingsSwitchRow(
            title = stringResource(R.string.advanced_addon_health_title),
            subtitle = stringResource(R.string.advanced_addon_health_subtitle),
            checked = state.addonHealthEnabled,
            onCheckedChange = { onEvent(AdvancedSettingsEvent.SetAddonHealthEnabled(it)) }
        )
    }
    // Android keeps exit reasons from API 30 (the Shield's level); older devices have nothing to show.
    if (LastExitReason.supported) {
        item(key = "diagnostics_section") {
            SettingsSectionLabel(text = stringResource(R.string.advanced_section_diagnostics))
        }
        item(key = "last_unexpected_close") { LastUnexpectedCloseRow() }
    }
}

/** The history read's result; the row shows no value until it lands. */
private data class LastClose(val exit: AbnormalExit?)

/**
 * Spec 70 R1: the newest crash, freeze or foreground memory kill (70/D1) Android kept, with its
 * age and a status dot (red, amber for memory: the sign-in status line's colours). Read-only: OK
 * does nothing; the click only keeps the row reachable by D-pad.
 */
@Composable
private fun LastUnexpectedCloseRow() {
    val context = LocalContext.current
    val lastClose by produceState<LastClose?>(initialValue = null) {
        value = LastClose(withContext(Dispatchers.IO) { LastExitReason.latestAbnormal(context.applicationContext) })
    }
    MarqueeFormRow(
        label = stringResource(R.string.advanced_last_close_title),
        helper = stringResource(R.string.advanced_last_close_subtitle),
        onClick = {},
        minHeight = NuvioTheme.marquee.settings.rowMinHeight
    ) { lit ->
        val loaded = lastClose ?: return@MarqueeFormRow
        val exit = loaded.exit
        if (exit == null) {
            MarqueeFormRowValue(text = stringResource(R.string.advanced_last_close_none), dimmed = false, lit = lit)
            return@MarqueeFormRow
        }
        Box(
            modifier = Modifier
                .size(NuvioTheme.marquee.settings.signInStatusDot)
                .background(
                    if (exit.kind == AbnormalExitKind.MEMORY_KILL) NuvioPrimitives.warning else NuvioPrimitives.errorOnImage,
                    CircleShape
                )
        )
        val kind = stringResource(
            when (exit.kind) {
                AbnormalExitKind.CRASH, AbnormalExitKind.NATIVE_CRASH -> R.string.advanced_last_close_crash
                AbnormalExitKind.ANR -> R.string.advanced_last_close_anr
                AbnormalExitKind.MEMORY_KILL -> R.string.advanced_last_close_memory
            }
        )
        val age = DateUtils.getRelativeTimeSpanString(
            exit.timestampMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        ).toString()
        MarqueeFormRowValue(
            text = stringResource(R.string.advanced_last_close_value, kind, age),
            dimmed = false,
            lit = lit
        )
    }
}

/**
 * Stage 24: runs the sweep against the last played stream. Under the title: the server, the host
 * that served the bytes when a redirect moved them, the file (scrolls while focused) and the pass
 * being measured. Dims with the reason when nothing has played yet.
 */
@Composable
private fun StreamTestRow(
    streamUrl: String?,
    diagnostics: LastPlaybackDiagnostics,
    liveServingHost: String?,
    runningPass: String?,
    onRun: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasStream = !streamUrl.isNullOrBlank()
    MarqueeValueRow(
        label = stringResource(
            if (runningPass != null) R.string.stream_test_btn_running else R.string.stream_test_run_title
        ),
        value = null,
        onClick = onRun,
        modifier = modifier,
        helper = if (hasStream) null else stringResource(R.string.stream_test_no_stream),
        // Dims while a run measures; the helper's "Measuring" line says so.
        enabled = hasStream && runningPass == null,
        trailingIcon = null,
        minHeight = NuvioTheme.marquee.settings.rowMinHeight,
        helperContent = if (hasStream) {
            { lit ->
                val lineStyle = NuvioTheme.textStyles.panelHelper
                val lineColor = NuvioTheme.marquee.onImage3
                val redirectorHost = android.net.Uri.parse(streamUrl).host
                Text(
                    text = stringResource(
                        R.string.stream_test_server_label,
                        redirectorHost ?: stringResource(R.string.stream_quality_unknown)
                    ),
                    style = lineStyle,
                    color = lineColor
                )
                // N6 V2 follow-on: the host that actually served the bytes. Prefers the
                // live value captured during this test run, falling back to the host
                // persisted at the last playback's first frame. Suppressed when nothing
                // was captured or the redirect resolved to the Server line's host.
                (liveServingHost ?: diagnostics.resolvedServingHost)
                    ?.takeIf { !it.equals(redirectorHost, ignoreCase = true) }
                    ?.let { servingHost ->
                        Text(
                            text = stringResource(R.string.stream_test_serving_host_label, servingHost),
                            style = lineStyle,
                            color = lineColor
                        )
                    }
                diagnostics.filename?.let { name ->
                    // Long remux filenames marquee-scroll while the row is lit instead of
                    // ellipsising, at Compose's default 30.dp/s (slower than the app-wide 45)
                    // so dense release names stay readable.
                    FocusMarqueeText(
                        text = stringResource(R.string.stream_test_file_label, name),
                        focused = lit,
                        style = lineStyle,
                        color = lineColor,
                        velocity = 30.dp
                    )
                }
                if (runningPass != null) {
                    Text(
                        text = stringResource(R.string.stream_test_btn_measuring_dyn, runningPass),
                        style = lineStyle,
                        color = lineColor
                    )
                }
            }
        } else {
            null
        }
    )
}

/** The libdovi conversion-mode override; dims unless DV7 handling converts to DV8.1. */
@Composable
private fun DvConversionModeRow(
    settings: PlayerSettings,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val overrideEnabled = settings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI
    var showPicker by remember { mutableStateOf(false) }
    val modeOptions = listOf(
        SettingsPickerOption(
            -1,
            stringResource(R.string.dv7_libdovi_mode_none_title),
            stringResource(R.string.dv7_libdovi_mode_none_sub)
        ),
        SettingsPickerOption(0, stringResource(R.string.dv7_libdovi_mode_0_title), stringResource(R.string.dv7_libdovi_mode_0_sub)),
        SettingsPickerOption(1, stringResource(R.string.dv7_libdovi_mode_1_title), stringResource(R.string.dv7_libdovi_mode_1_sub)),
        SettingsPickerOption(2, stringResource(R.string.dv7_libdovi_mode_2_title), stringResource(R.string.dv7_libdovi_mode_2_sub)),
        SettingsPickerOption(3, stringResource(R.string.dv7_libdovi_mode_3_title), stringResource(R.string.dv7_libdovi_mode_3_sub)),
        SettingsPickerOption(4, stringResource(R.string.dv7_libdovi_mode_4_title), stringResource(R.string.dv7_libdovi_mode_4_sub))
    )
    // Show None whenever the row is dimmed so a stale stored override never displays as a
    // selected mode.
    val effectiveOverride = if (overrideEnabled) settings.dv7LibdoviModeOverride else -1
    val currentLabel = modeOptions.firstOrNull { it.value == effectiveOverride }?.title
        ?: modeOptions.first().title
    SettingsValueRow(
        title = stringResource(R.string.dv7_libdovi_mode_row_title),
        subtitle = stringResource(R.string.dv7_libdovi_mode_caption),
        value = currentLabel,
        enabled = overrideEnabled,
        onClick = { showPicker = true },
        modifier = modifier
    )
    if (showPicker) {
        SettingsPickerPanel(
            title = stringResource(R.string.dv7_libdovi_mode_row_title),
            caption = stringResource(R.string.dv7_libdovi_mode_caption),
            options = modeOptions,
            selectedValue = effectiveOverride,
            onSelect = onSelect,
            onDismiss = { showPicker = false }
        )
    }
}

/** Cache: two one-shot clear actions (copy as built). */
private fun LazyListScope.cacheItems(scope: CoroutineScope, initialFocusRequester: FocusRequester?) {
    item(key = "clear_cw_cache") {
        val context = LocalContext.current
        var cleared by remember { mutableStateOf(false) }
        SettingsValueRow(
            title = stringResource(R.string.advanced_clear_cw_cache),
            subtitle = stringResource(
                if (cleared) R.string.advanced_clear_cw_cache_done else R.string.advanced_clear_cw_cache_subtitle
            ),
            value = null,
            trailingIcon = null,
            onClick = {
                if (!cleared) {
                    scope.launch {
                        dagger.hilt.android.EntryPointAccessors
                            .fromApplication(context.applicationContext, ClearCwCacheEntryPoint::class.java)
                            .cwEnrichmentCache()
                            .clearAll()
                        cleared = true
                    }
                }
            },
            modifier = Modifier.optionalFocusRequester(initialFocusRequester)
        )
    }
    item(key = "clear_image_cache") {
        val context = LocalContext.current
        // 0 = idle, 1 = clearing, 2 = done
        var clearState by remember { mutableStateOf(0) }
        SettingsValueRow(
            title = stringResource(R.string.advanced_clear_image_cache),
            subtitle = when (clearState) {
                1 -> stringResource(R.string.advanced_clear_image_cache_working)
                2 -> stringResource(R.string.advanced_clear_image_cache_done)
                else -> stringResource(R.string.advanced_clear_image_cache_subtitle)
            },
            value = null,
            trailingIcon = null,
            onClick = {
                if (clearState == 0) {
                    clearState = 1
                    scope.launch {
                        val entryPoint = dagger.hilt.android.EntryPointAccessors
                            .fromApplication(context.applicationContext, ClearImageCacheEntryPoint::class.java)
                        withContext(Dispatchers.IO) {
                            // Coil 3.3.0: DiskCache.clear() and MemoryCache.clear() both
                            // exist; the memory cache is lock-guarded so an IO thread call
                            // is safe.
                            val loader = coil3.SingletonImageLoader.get(context.applicationContext)
                            runCatching { loader.memoryCache?.clear() }
                            runCatching { loader.diskCache?.clear() }
                            runCatching { entryPoint.apiOkHttpClient().cache?.evictAll() }
                            runCatching { entryPoint.validatedOkHttpClient().cache?.evictAll() }
                            runCatching { entryPoint.catalogRepository().clearCaches() }
                        }
                        entryPoint.homeRefreshSignal().requestRefresh()
                        clearState = 2
                    }
                }
            }
        )
    }
}

/** One pass's `.l11pass` line; also the Device assessment's pass rows. */
@Composable
internal fun StreamTestResultRow(
    label: String,
    speed: Double?,
    note: StreamSweepEngine.PassNote? = null,
    isRunning: Boolean
) {
    SettingsResultLine(
        label = label,
        detail = note?.detail,
        value = when {
            isRunning -> stringResource(R.string.stream_test_btn_running)
            speed != null -> stringResource(R.string.stream_test_unit_mbps, speed)
            note != null -> note.state
            else -> "---"
        },
        valueMuted = speed == null || isRunning
    )
}
