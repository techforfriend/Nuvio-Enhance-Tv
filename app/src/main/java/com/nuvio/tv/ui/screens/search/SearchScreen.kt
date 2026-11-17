@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.PanelRule
import com.nuvio.tv.ui.components.errorStateText
import com.nuvio.tv.ui.components.marquee.MarqueeGlassButton
import com.nuvio.tv.ui.components.marquee.MarqueeMessage
import com.nuvio.tv.ui.components.marquee.SUFFIX_ALPHA
import com.nuvio.tv.ui.components.trailerpreview.LocalTrailerPreviewEnabled
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.home.HeroCarouselRow
import com.nuvio.tv.ui.screens.home.HomeMessageAction
import com.nuvio.tv.ui.screens.home.LocalFastScrollActive
import com.nuvio.tv.ui.screens.home.MarqueeSkeletonRow
import com.nuvio.tv.ui.screens.home.marqueeBandCardHeight
import com.nuvio.tv.ui.screens.home.rememberMarqueeBandBringIntoViewSpec
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.util.recompositionHighlighter
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    /** Discover inside Search ("Show in Search"), scoped to this Search destination. */
    discoverViewModel: DiscoverViewModel = hiltViewModel(),
    /** Root Search under the top bar: content starts below the bar (pushed query Search has none). */
    underTopBar: Boolean = false,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (catalogId: String, addonId: String, type: String) -> Unit = { _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val strVoiceNoSpeech = stringResource(R.string.search_voice_no_speech)
    val strVoiceMicPermission = stringResource(R.string.search_voice_mic_permission)
    val strVoiceFailed = stringResource(R.string.search_voice_failed)
    val strVoiceUnavailable = stringResource(R.string.search_voice_unavailable)
    val voiceFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val recentsFirstRequester = remember { FocusRequester() }
    val retryFocusRequester = remember { FocusRequester() }
    var focusResults by remember { mutableStateOf(false) }
    var pendingFocusMoveToResultsQuery by remember { mutableStateOf<String?>(null) }
    var pendingFocusMoveSawSearching by remember { mutableStateOf(false) }
    var pendingFocusMoveHadExistingSearchRows by remember { mutableStateOf(false) }
    var isVoiceListening by remember { mutableStateOf(false) }
    var voiceRmsLevel by remember { mutableStateOf(0f) }
    val restoringSearchFocus = remember { mutableStateOf(viewModel.hasSavedSearchFocus) }
    val didRestoreSearchFocus = remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val onVoiceQueryResultState = rememberUpdatedState<(String) -> Unit> { recognized ->
        if (recognized.isNotBlank()) {
            viewModel.onEvent(SearchEvent.QueryChanged(recognized))
            viewModel.onEvent(SearchEvent.SubmitSearch)
            focusResults = false
            pendingFocusMoveToResultsQuery = recognized
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows =
                uiState.submittedQuery.trim().length >= MIN_SEARCH_QUERY_LENGTH &&
                    uiState.catalogRows.any { it.items.isNotEmpty() }
        } else {
            Toast.makeText(context, strVoiceNoSpeech, Toast.LENGTH_SHORT).show()
        }
    }
    val isVoiceSearchAvailable = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    val speechRecognizer = remember(context, isVoiceSearchAvailable) {
        if (isVoiceSearchAvailable) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }
    }
    val buildRecognizeIntent: () -> Intent = {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }
    val hasRecordAudioPermission by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var recordAudioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestAudioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        recordAudioPermissionGranted = granted
        if (granted) {
            isVoiceListening = true
            runCatching {
                speechRecognizer?.cancel()
                speechRecognizer?.startListening(buildRecognizeIntent())
            }.onFailure {
                isVoiceListening = false
                Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, strVoiceMicPermission, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(hasRecordAudioPermission) {
        recordAudioPermissionGranted = hasRecordAudioPermission
    }
    DisposableEffect(speechRecognizer) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                // Normalize RMS dB to 0..1 range. Typical values: -2 (silence) to 10 (loud).
                voiceRmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                isVoiceListening = false
                voiceRmsLevel = 0f
                Log.w("SearchScreen", "Voice recognition error: $error")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        Toast.makeText(context, strVoiceNoSpeech, Toast.LENGTH_SHORT).show()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> Unit
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        Toast.makeText(context, strVoiceMicPermission, Toast.LENGTH_SHORT).show()
                    else ->
                        Toast.makeText(context, strVoiceFailed, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                isVoiceListening = false
                voiceRmsLevel = 0f
                val recognized = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                onVoiceQueryResultState.value(recognized)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
        }

        speechRecognizer?.setRecognitionListener(listener)
        onDispose {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.destroy()
        }
    }
    val topInputFocusRequester = remember(isVoiceSearchAvailable) {
        if (isVoiceSearchAvailable) voiceFocusRequester else searchFocusRequester
    }
    val launchVoiceSearch: () -> Unit = {
        if (!isVoiceSearchAvailable || speechRecognizer == null) {
            Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
        } else if (!recordAudioPermissionGranted) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            isVoiceListening = true
            runCatching {
                speechRecognizer.cancel()
                speechRecognizer.startListening(buildRecognizeIntent())
            }.onFailure {
                isVoiceListening = false
                Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val trimmedQuery = remember(uiState.query) { uiState.query.trim() }
    val trimmedSubmittedQuery = remember(uiState.submittedQuery) { uiState.submittedQuery.trim() }

    val listState = rememberLazyListState()
    var listHasFocus by remember { mutableStateOf(false) }
    var fieldRowHasFocus by remember { mutableStateOf(false) }
    var stateLayerHasFocus by remember { mutableStateOf(false) }
    val band = rememberSearchResultsBand(
        viewModel = viewModel,
        listState = listState,
        catalogRows = uiState.catalogRows,
        showAddonName = uiState.catalogAddonNameEnabled,
        showTypeSuffix = uiState.catalogTypeSuffixEnabled,
        submittedQuery = trimmedSubmittedQuery,
        onCardFocused = {
            focusResults = false
            if (restoringSearchFocus.value) {
                restoringSearchFocus.value = false
                didRestoreSearchFocus.value = true
                viewModel.hasSavedSearchFocus = false
            }
            // A card took focus: a pending auto-move to the results must not steal it later.
            pendingFocusMoveToResultsQuery = null
        },
        onNavigateToDetail = onNavigateToDetail,
        onNavigateToSeeAll = onNavigateToSeeAll
    )

    val isDiscoverMode = remember(uiState.discoverLocation, trimmedQuery, trimmedSubmittedQuery) {
        shouldShowDiscoverInSearch(
            discoverLocation = uiState.discoverLocation,
            query = trimmedQuery,
            submittedQuery = trimmedSubmittedQuery
        )
    }
    // Stage 3: Discover under the field row while Search is idle. Its catalogs load on the first idle
    // Search of this destination (QU4-1: one Discover fetch per Search open).
    val discoverUiState by discoverViewModel.uiState.collectAsState()
    LaunchedEffect(isDiscoverMode) {
        if (isDiscoverMode) discoverViewModel.ensureDiscoverLoaded()
    }
    val discoverBand = rememberDiscoverBand(
        viewModel = discoverViewModel,
        uiState = discoverUiState,
        listState = listState,
        firstGridItem = SEARCH_DISCOVER_FIRST_GRID_ITEM,
        active = isDiscoverMode,
        onNavigateToDetail = onNavigateToDetail
    )
    var discoverHeaderHasFocus by remember { mutableStateOf(false) }
    // The grid holds focus: Discover's hero shows and the header (label, pills) hides.
    // By elimination: in Discover mode only the field row, the header and the grid rows focus.
    // Derived (not a plain val): the band's bring-into-view inset reads it outside composition.
    val isDiscoverModeState = rememberUpdatedState(isDiscoverMode)
    val discoverGridHasFocusState = remember {
        derivedStateOf { isDiscoverModeState.value && listHasFocus && !fieldRowHasFocus && !discoverHeaderHasFocus }
    }
    val discoverGridHasFocus = discoverGridHasFocusState.value
    val hasPendingUnsubmittedQuery = remember(isDiscoverMode, trimmedQuery, trimmedSubmittedQuery) {
        !isDiscoverMode &&
            trimmedQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
            trimmedQuery != trimmedSubmittedQuery
    }
    // Spec 38 stages 9-10: recent searches drop down under the field while it is focused and empty.
    // Back, or Up from the first row, returns to the field and closes them until focus leaves it.
    var fieldHasFocus by remember { mutableStateOf(false) }
    var recentsPanelHasFocus by remember { mutableStateOf(false) }
    var recentsDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(fieldHasFocus, recentsPanelHasFocus) {
        if (!fieldHasFocus && !recentsPanelHasFocus) recentsDismissed = false
    }
    val showRecentsPanel = !recentsDismissed && (fieldHasFocus || recentsPanelHasFocus) &&
        trimmedQuery.isEmpty() && uiState.recentSearches.isNotEmpty()
    // The field's window bounds and the screen's window origin: the panel hangs under the field.
    var fieldWindowBounds by remember { mutableStateOf<Rect?>(null) }
    var rootWindowOrigin by remember { mutableStateOf(Offset.Zero) }
    val canMoveToResults = remember(
        isDiscoverMode,
        trimmedSubmittedQuery,
        uiState.catalogRows
    ) {
        if (isDiscoverMode) {
            false
        } else {
            trimmedSubmittedQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
                uiState.catalogRows.any { it.items.isNotEmpty() }
        }
    }
    // No "press Done to search" hint: search runs as you type (mobile and desktop show none).
    val bodyState = searchBody(
        isDiscoverMode = isDiscoverMode,
        submittedQuery = trimmedSubmittedQuery,
        hasPendingUnsubmittedQuery = hasPendingUnsubmittedQuery,
        isSearching = uiState.isSearching,
        errorWithoutRows = uiState.error != null && uiState.catalogRows.isEmpty(),
        hasResults = band.rows.isNotEmpty()
    )
    val submitCurrentQuery: (String) -> Unit = { submittedQuery ->
        viewModel.onEvent(SearchEvent.SubmitSearch)
        focusResults = false
        if (submittedQuery.length >= MIN_SEARCH_QUERY_LENGTH) {
            pendingFocusMoveToResultsQuery = submittedQuery
            pendingFocusMoveSawSearching = false
            // Computed from the SUBMITTED argument, not the pre-submit query: a same-query
            // resubmit may never enter the searching phase (ViewModel dedupe; with search-on-Enter
            // addons it does, Spec 40, and focus then lands on the live rows), so requiring a
            // seen-searching phase strands focus in the text field forever. Trimmed on both
            // sides — "query " resubmits the same query as "query".
            pendingFocusMoveHadExistingSearchRows =
                submittedQuery.trim() != trimmedSubmittedQuery &&
                    uiState.catalogRows.any { row -> row.items.isNotEmpty() }
        } else {
            pendingFocusMoveToResultsQuery = null
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows = false
        }
    }
    val handleQueryChanged: (String) -> Unit = { nextQuery ->
        val previousQuery = uiState.query.trim()
        val trimmedNextQuery = nextQuery.trim()
        val selectedSuggestion = trimmedNextQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
            trimmedNextQuery != trimmedSubmittedQuery &&
            uiState.suggestions.any { it.equals(trimmedNextQuery, ignoreCase = true) } &&
            trimmedNextQuery.startsWith(previousQuery, ignoreCase = true) &&
            trimmedNextQuery.length - previousQuery.length > 1

        focusResults = false
        band.focus.clearPendingRowFocus()
        pendingFocusMoveToResultsQuery = null
        pendingFocusMoveSawSearching = false
        pendingFocusMoveHadExistingSearchRows = false
        viewModel.onEvent(SearchEvent.QueryChanged(nextQuery))
        if (selectedSuggestion) {
            submitCurrentQuery(trimmedNextQuery)
        }
    }
    val submitRecentSearch: (String) -> Unit = { recentQuery ->
        val trimmedRecentQuery = recentQuery.trim()
        if (trimmedRecentQuery.isNotEmpty()) {
            viewModel.onEvent(SearchEvent.QueryChanged(trimmedRecentQuery))
            submitCurrentQuery(trimmedRecentQuery)
        }
    }

    LaunchedEffect(
        pendingFocusMoveToResultsQuery,
        pendingFocusMoveSawSearching,
        pendingFocusMoveHadExistingSearchRows,
        uiState.isSearching,
        uiState.submittedQuery,
        canMoveToResults
    ) {
        val pendingQuery = pendingFocusMoveToResultsQuery ?: return@LaunchedEffect
        val currentSubmittedQuery = uiState.submittedQuery.trim()
        if (currentSubmittedQuery != pendingQuery) return@LaunchedEffect

        if (uiState.isSearching) {
            pendingFocusMoveSawSearching = true
            return@LaunchedEffect
        }

        val shouldRequireSeenSearching = pendingFocusMoveHadExistingSearchRows
        if ((shouldRequireSeenSearching && !pendingFocusMoveSawSearching) || !canMoveToResults) {
            return@LaunchedEffect
        }

        // canMoveToResults is false in Discover mode, so this is always a results move.
        // Use explicit first-item focus for deterministic landing on row 1 / column 1.
        delay(80)
        focusResults = true
        pendingFocusMoveToResultsQuery = null
        pendingFocusMoveSawSearching = false
        pendingFocusMoveHadExistingSearchRows = false
    }

    LaunchedEffect(Unit) {
        // Back from Details: the result band or the Discover grid restores its card.
        if (viewModel.hasSavedSearchFocus || discoverBand.blocksFilterFocus) return@LaunchedEffect
        topInputFocusRequester.requestFocusAfterFrames()
    }

    // ---- Result band focus (F-4/F-13 on the band's pending focus) ----
    // The first result after a submit or a Down with no card to return to.
    val firstResultRowKey = band.rows.firstOrNull()?.row?.key
    LaunchedEffect(focusResults, firstResultRowKey) {
        if (focusResults && firstResultRowKey != null) band.focus.requestPendingFocus(firstResultRowKey, 0)
    }
    // Back from Details or See All: the saved card (See All = the item count), once its row is up.
    val restoreRowPresent = restoringSearchFocus.value &&
        band.rows.any { it.row.key == viewModel.savedFocusRowKey }
    LaunchedEffect(restoreRowPresent) {
        val rowKey = viewModel.savedFocusRowKey
        if (restoreRowPresent && rowKey != null) band.focus.requestPendingFocus(rowKey, viewModel.savedFocusItemIndex)
    }

    val hero = rememberResultsHeroState(band.settledItem.value)
    // Only while the grid holds focus: no backdrop decode or accent for a grid nobody browses.
    val discoverHero = rememberResultsHeroState(discoverBand.settledItem.value.takeIf { discoverGridHasFocus })

    // Push search suggestions to the native keyboard suggestion bar
    LaunchedEffect(uiState.suggestions) {
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return@LaunchedEffect
        val completions = uiState.suggestions.mapIndexed { index, name ->
            CompletionInfo(index.toLong(), index, name)
        }.toTypedArray()
        imm.displayCompletions(view, completions)
    }

    var isScreenActive by remember { mutableStateOf(true) }
    val latestShouldKeepSearchFocus by rememberUpdatedState(
        focusResults || uiState.isSearching || isVoiceListening
    )
    val latestVoiceSearchAvailable by rememberUpdatedState(isVoiceSearchAvailable)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isScreenActive = true
                if (viewModel.hasSavedSearchFocus || didRestoreSearchFocus.value) {
                    // Returning from Details or See All (the band saved its focus): don't steal focus,
                    // the band's pending focus already restored the card or will.
                    didRestoreSearchFocus.value = false
                } else if (discoverBand.blocksFilterFocus) {
                    // Returning from a Discover card's Details: the Discover grid restores it.
                } else if (!latestShouldKeepSearchFocus) {
                    coroutineScope.launch {
                        (if (latestVoiceSearchAvailable) voiceFocusRequester else searchFocusRequester)
                            .requestFocusAfterFrames()
                    }
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                isScreenActive = false
                keyboardController?.hide()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ---- Geometry (stages 5, 6): Home's row band anchor (MarqueeHomeContent), bottom-anchored hero ----
    val marquee = NuvioTheme.marquee
    val home = marquee.home
    val density = LocalDensity.current
    val showResults = bodyState == SearchBody.RESULTS
    // Frame rule (BC-D1): real window pixels through the current (scaled) density.
    val screenHeight = with(density) { context.resources.displayMetrics.heightPixels.toDp() }
    val rowTitleHeight = with(density) { NuvioTheme.textStyles.rowTitle.lineHeight.toDp() }
    // Result rows take the Card shape row by row (Spec 26 26c): as on Home, the first row's card
    // height places row 1 under the field row and the settled row's places the hero.
    val useLandscapePosters = uiState.landscapePostersEnabled
    fun rowTitleTopOf(row: HeroCarouselRow?): Dp = screenHeight - home.focusedCardBottomInset -
        marqueeBandCardHeight(row, useLandscapePosters, continueWatchingPoster = false, home = home) -
        home.rowTitleGap - rowTitleHeight
    val rowTitleTop = rowTitleTopOf(band.rows.firstOrNull()?.row)
    val heroBottom = rowTitleTopOf(band.settledRow.value) - home.heroAboveRow
    // The Discover grid's focused row starts here (its card height follows the grid's shape).
    val discoverCardsTop = discoverCardsTop(screenHeight, discoverBand.grid.form, marquee)
    val listTop = if (underTopBar) marquee.topBar.contentTop else 0.dp
    val listPaddingTop = when {
        underTopBar -> 0.dp
        isDiscoverMode -> 10.dp
        else -> NuvioTheme.spacing.lg
    }
    val fieldRowBottom = listTop + listPaddingTop + maxOf(marquee.search.fieldHeight, marquee.glassButton.size)
    // The lead item puts the first row's title at rowTitleTop, so row 1 sits at the band anchor
    // under the field row (two item gaps: field to lead, lead to row 1).
    val bandLeadHeight = (rowTitleTop - fieldRowBottom - home.rowGap * 2).coerceAtLeast(0.dp)
    // Stage 3: the Discover header is tall enough that grid row 1's cards start at the band anchor,
    // so Down into the grid reaches stage 9's geometry without a jump (two item gaps: field to
    // header, header to row 1).
    val discoverHeaderMinHeight = (discoverCardsTop - fieldRowBottom - marquee.search.gridGap * 2).coerceAtLeast(0.dp)
    // Spec 38 Q4 B: past the first grid/results row the hero compacts to the top and the focused row
    // starts at `search.compactBandTop` (result rows keep their title above the cards). One derived
    // state per band, read by the composition (which recomposes only when focus crosses row 1) and
    // by the bring-into-view inset.
    val showResultsState = rememberUpdatedState(showResults)
    val resultsCompactState = remember(band) {
        derivedStateOf { showResultsState.value && band.activeRowIndex.value >= 1 }
    }
    val discoverCompactState = remember(discoverBand) {
        derivedStateOf { discoverGridHasFocusState.value && discoverBand.activeRow.intValue >= 1 }
    }
    val resultsCompact = resultsCompactState.value
    val discoverCompact = discoverCompactState.value
    val discoverCardHeight = discoverGridCardSize(discoverBand.grid.form, marquee).height
    val discoverCompactInset = compactBandBottomInset(screenHeight, discoverCardHeight, marquee)
    fun resultsCompactInset(row: HeroCarouselRow?): Dp = compactBandBottomInset(
        screenHeight = screenHeight,
        cardHeight = marqueeBandCardHeight(row, useLandscapePosters, continueWatchingPoster = false, home = home),
        marquee = marquee,
        rowHeadHeight = rowTitleHeight + home.rowTitleGap
    )
    // Read on every bring-into-view calculation: the Down that enters row 2 lands at the compact
    // anchor in one scroll.
    val bandInset: () -> Dp = remember(band, discoverBand, screenHeight, rowTitleHeight, useLandscapePosters, discoverCompactInset, marquee) {
        {
            when {
                discoverCompactState.value -> discoverCompactInset
                resultsCompactState.value -> resultsCompactInset(band.rows.getOrNull(band.activeRowIndex.value)?.row)
                else -> home.focusedCardBottomInset
            }
        }
    }
    // Room under the last row for the deepest anchor it can take (the compact one sits higher).
    val bandBottomPadding = maxOf(
        home.focusedCardBottomInset,
        if (isDiscoverMode) discoverCompactInset else band.rows.maxOfOrNull { resultsCompactInset(it.row) } ?: 0.dp
    )
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val bandSpec = rememberMarqueeBandBringIntoViewSpec(listState, bandInset)
    val useBand = showResults || isDiscoverMode

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootWindowOrigin = it.positionInWindow() },
        contentAlignment = Alignment.TopCenter
    ) {
        if (showResults) {
            MarqueeResultsHero(
                hero = hero,
                heroBottomInset = (screenHeight - heroBottom).coerceAtLeast(0.dp),
                heroMaxHeight = (heroBottom - fieldRowBottom - home.heroGap).coerceAtLeast(0.dp),
                drawTopScrim = !underTopBar,
                compact = resultsCompact
            )
        }
        if (discoverGridHasFocus && discoverBand.grid.items.isNotEmpty()) {
            DiscoverHeroLayer(
                uiState = discoverUiState,
                hero = discoverHero,
                screenHeight = screenHeight,
                cardsTop = discoverCardsTop,
                heroTopLimit = fieldRowBottom,
                drawTopScrim = !underTopBar,
                compact = discoverCompact
            )
        }
        // Back from the Discover grid returns to the filters (as on the Discover tab); F-4 then
        // takes the next Back.
        BackHandler(enabled = discoverGridHasFocus) { discoverBand.backToFilters() }
        // F-4: on the root Search, Back from anywhere below the field row (results, the
        // Retry button) scrolls to the top and focuses the control Search opens on. Back from the
        // field row is not handled here: the NavHost pops to Home. The pushed query Search keeps
        // one Back to its caller.
        BackHandler(
            enabled = underTopBar && (listHasFocus || stateLayerHasFocus) && !fieldRowHasFocus && !discoverGridHasFocus
        ) {
            band.focus.clearPendingRowFocus()
            // The full hero and every row show at once, not after the field takes focus.
            band.releaseActiveCard()
            coroutineScope.launch {
                listState.scrollToItem(0)
                topInputFocusRequester.requestFocusAfterFrames()
            }
        }
        // Retry takes focus when the error appears, unless the user is in the field row: live
        // search can fail as you type, and a focus jump would close the keyboard on every key.
        val retryTakesFocus = remember(bodyState == SearchBody.ERROR) { !fieldRowHasFocus }
        // Idle Search with Discover inside shows Discover instead of the start message.
        if (!isDiscoverMode) {
            SearchStateLayer(
                bodyState = bodyState,
                discoverLocation = uiState.discoverLocation,
                error = uiState.error,
                submittedQuery = trimmedSubmittedQuery,
                searchOnEnterWaits = uiState.searchOnEnterWaits,
                landscape = useLandscapePosters,
                onRetry = { viewModel.onEvent(SearchEvent.Retry) },
                retryFocusRequester = retryFocusRequester.takeIf { retryTakesFocus },
                modifier = Modifier.onFocusChanged { stateLayerHasFocus = it.hasFocus }
            )
        }
        // Results and the Discover grid: a focused card's bottom goes to the band anchor (rows
        // move as on Home). Spec 96 PD2: their cards opt into the trailer preview (the host's In the
        // card mode decides).
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides if (useBand) bandSpec else defaultBringIntoViewSpec,
            LocalTrailerPreviewEnabled provides true,
            LocalFastScrollActive provides if (isDiscoverMode) discoverBand.isFastScrolling else LocalFastScrollActive.current
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    // F-4: under the top bar the list starts below it and clips there, so the field
                    // row scrolls out at y 72 instead of passing under the tabs.
                    .padding(top = listTop)
                    .onFocusChanged { listHasFocus = it.hasFocus }
                    // Down from the top bar lands here (TopBarScaffold); Compose enters the
                    // top-left composed control. Only the tab instance: the pushed query Search
                    // has no bar.
                    .then(
                        if (underTopBar) {
                            Modifier.focusRequester(LocalContentFocusRequester.current)
                        } else {
                            Modifier
                        }
                    )
                    .recompositionHighlighter()
                    .then(
                        if (isDiscoverMode) {
                            // Fast scroll lands on the row at the anchor in use.
                            Modifier.discoverFastScroll(discoverBand, with(density) { bandInset().roundToPx() })
                        } else {
                            Modifier.dpadRepeatThrottle()
                        }
                    ),
                state = listState,
                contentPadding = PaddingValues(
                    top = listPaddingTop,
                    // Results and the grid: the last row can still reach the band anchor (compact too).
                    bottom = if (useBand) bandBottomPadding else NuvioTheme.spacing.lg
                ),
                verticalArrangement = Arrangement.spacedBy(
                    when {
                        showResults -> home.rowGap
                        isDiscoverMode -> marquee.search.gridGap
                        else -> NuvioTheme.spacing.lg
                    }
                )
            ) {
                // Item 0. The bands' items follow it (RESULT_ROWS_FIRST_ITEM and
                // SEARCH_DISCOVER_FIRST_GRID_ITEM count it).
                item(key = "search_input") {
                    SearchInputField(
                        query = uiState.query,
                        canMoveToResults = canMoveToResults,
                        voiceFocusRequester = if (isVoiceSearchAvailable) voiceFocusRequester else null,
                        searchFocusRequester = searchFocusRequester,
                        onRowFocusChanged = { focused ->
                            fieldRowHasFocus = focused
                            if (focused) band.releaseActiveCard()
                        },
                        onFieldFocusChanged = { fieldHasFocus = it },
                        onFieldPositioned = { fieldWindowBounds = it },
                        onDownToRecents = if (showRecentsPanel) {
                            { runCatching { recentsFirstRequester.requestFocus() } }
                        } else {
                            null
                        },
                        onQueryChanged = handleQueryChanged,
                        onSubmit = {
                            submitCurrentQuery(uiState.query.trim())
                        },
                        showVoiceSearch = isVoiceSearchAvailable,
                        isVoiceListening = isVoiceListening,
                        voiceRmsLevel = { voiceRmsLevel },
                        onVoiceSearch = launchVoiceSearch,
                        onMoveToResults = {
                            // D-pad down from the field row is the user's confirmation that the
                            // live-search results are useful, even before a particular card opens.
                            viewModel.onEvent(SearchEvent.RememberSearchFromTextInput)
                            // F-4: back to the last card focused under this query, else the first.
                            if (!band.restoreLastCard()) focusResults = true
                        },
                        // Stage 3: the compass jumps to the filter pills.
                        onOpenDiscover = { runCatching { discoverBand.filterFocusRequester.requestFocus() } },
                        showDiscoverButton = isDiscoverMode,
                        keyboardController = keyboardController,
                        isScreenActive = isScreenActive
                    )
                }

                if (isDiscoverMode) {
                    item(key = "search_discover_header", contentType = "search_discover_header") {
                        // The pickers' panels scroll inside: they keep the default bring-into-view.
                        CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                            SearchDiscoverHeader(
                                band = discoverBand,
                                uiState = discoverUiState,
                                minHeight = discoverHeaderMinHeight,
                                hidden = discoverGridHasFocus,
                                modifier = Modifier.onFocusChanged { discoverHeaderHasFocus = it.hasFocus }
                            )
                        }
                    }
                    when {
                        discoverBand.grid.rowCount > 0 -> discoverGridItems(discoverBand, discoverHero.accent)
                        discoverUiState.discoverLoading -> item(key = "search_discover_loading") {
                            MarqueeSkeletonRow(card = discoverGridCardSize(discoverBand.grid.form, marquee))
                        }
                        discoverUiState.discoverInitialized -> item(key = "search_discover_empty") {
                            SearchDiscoverNote(noCatalog = discoverUiState.selectedCatalog() == null)
                        }
                    }
                } else when (bodyState) {
                    SearchBody.RESULTS -> searchResultItems(
                        band = band,
                        leadHeight = bandLeadHeight,
                        useLandscapePosters = useLandscapePosters,
                        accent = hero.accent,
                        defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                        showLoadingMore = uiState.isSearching || hasPendingUnsubmittedQuery,
                        waits = uiState.searchOnEnterWaits
                    )

                    SearchBody.START, SearchBody.LOADING, SearchBody.ERROR, SearchBody.NO_RESULTS -> Unit
                }
            }
        }
        if (showRecentsPanel) {
            // The field takes focus back without its keyboard (the user was browsing, not typing).
            val returnToField = {
                runCatching { searchFocusRequester.requestFocus() }
                keyboardController?.hide()
                Unit
            }
            val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            val search = marquee.search
            RecentSearchesPanel(
                recentSearches = uiState.recentSearches.take(RECENT_SEARCHES_SHOWN),
                firstRowRequester = recentsFirstRequester,
                onSearchSelected = { query ->
                    returnToField()
                    submitRecentSearch(query)
                },
                onClearHistory = {
                    returnToField()
                    viewModel.onEvent(SearchEvent.ClearRecentSearches)
                },
                onExit = {
                    recentsDismissed = true
                    returnToField()
                },
                hasFocus = recentsPanelHasFocus,
                modifier = Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    // Bounds are read at layout only: field moves do not recompose the screen.
                    .absoluteOffset {
                        val fieldBounds = fieldWindowBounds ?: return@absoluteOffset IntOffset.Zero
                        val width = search.recentsPanelWidth.roundToPx()
                        val left = if (isRtl) fieldBounds.right - width else fieldBounds.left
                        IntOffset(
                            (left - rootWindowOrigin.x).roundToInt(),
                            (fieldBounds.bottom + search.recentsPanelOffset.toPx() - rootWindowOrigin.y).roundToInt()
                        )
                    }
                    .onFocusChanged { recentsPanelHasFocus = it.hasFocus }
            )
        }
    }

    val posterOptionsState by viewModel.posterOptions.state.collectAsState()
    com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = { id, type, addonBaseUrl ->
            val clickedItem = uiState.catalogRows
                .flatMap { it.items }
                .firstOrNull { it.id == id }
            HeroBackdropState.update(clickedItem?.backdropUrl)
            onNavigateToDetail(id, type, addonBaseUrl)
        }
    )
    DiscoverPosterOptions(viewModel = discoverViewModel, band = discoverBand, onNavigateToDetail = onNavigateToDetail)
}

/** List items before the Discover grid's first row: the field row and the Discover header. */
private const val SEARCH_DISCOVER_FIRST_GRID_ITEM = 2

/** Stage 9: the panel lists the newest five (the store keeps more). */
private const val RECENT_SEARCHES_SHOWN = 5

/**
 * Stage 3's Discover header (one list item after the field row; Spec 38 stage 8): the DISCOVER
 * label `discoverLabelTop` under the field row and the filter pills. At least [minHeight] tall,
 * so grid row 1 starts at the band anchor. [hidden] while the grid holds focus (Discover's hero
 * takes this space); still focusable, so Up from grid row 1 returns to the pills.
 */
@Composable
private fun SearchDiscoverHeader(
    band: DiscoverBand,
    uiState: DiscoverUiState,
    minHeight: Dp,
    hidden: Boolean,
    modifier: Modifier = Modifier
) {
    val search = NuvioTheme.marquee.search
    // The list's item gap already sits above this item.
    val topGap = (search.discoverLabelTop - search.gridGap).coerceAtLeast(0.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
    ) {
        Spacer(modifier = Modifier.height(topGap))
        Text(
            text = stringResource(R.string.discover_title).uppercase(),
            style = NuvioTheme.textStyles.eyebrow,
            color = NuvioTheme.marquee.onImage3,
            modifier = Modifier.padding(horizontal = NuvioTheme.layout.tvSafeHorizontal)
        )
        Spacer(modifier = Modifier.height(search.discoverLabelGap))
        DiscoverFilterPills(
            band = band,
            uiState = uiState,
            modifier = Modifier.padding(horizontal = NuvioTheme.layout.tvSafeHorizontal)
        )
    }
}

/**
 * Discover inside Search found nothing (stage 12's as-built copy, sized as a row title over a meta
 * line under the pills: the full-screen message would overlap the pills).
 */
@Composable
private fun SearchDiscoverNote(noCatalog: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.layout.tvSafeHorizontal),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Text(
            text = stringResource(
                if (noCatalog) R.string.discover_empty_no_catalog_title else R.string.discover_empty_no_content_title
            ),
            style = NuvioTheme.textStyles.rowTitle,
            color = NuvioTheme.colors.TextPrimary
        )
        Text(
            text = stringResource(
                if (noCatalog) R.string.discover_empty_no_catalog_subtitle else R.string.discover_empty_no_content_subtitle
            ),
            style = NuvioTheme.textStyles.meta,
            color = NuvioTheme.marquee.onImage3
        )
    }
}

/**
 * Search's state layer (sandbox `u4-search-discover-marquee` stages 2, 4, 7, 8): the start, no
 * results or error message, or the searching skeleton, full screen behind the list at Home's
 * positions, so the field row stays the list's first item (F-4 B). Results draw nothing here;
 * nor does the start message before [discoverLocation] loads (it names Discover).
 * [landscape]: the Card shape the searching skeleton draws.
 * [retryFocusRequester]: when set, Retry takes focus as the error appears.
 * [searchOnEnterWaits]: no results yet, but search-on-Enter catalogs wait for a submit (Spec 40):
 * their waiting lines replace the spelling hint.
 */
@Composable
private fun SearchStateLayer(
    bodyState: SearchBody,
    discoverLocation: DiscoverLocation?,
    error: String?,
    submittedQuery: String,
    searchOnEnterWaits: List<SearchOnEnterWait>,
    landscape: Boolean,
    onRetry: () -> Unit,
    retryFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        val eyebrow = stringResource(R.string.nav_search)
        when (bodyState) {
            SearchBody.START -> if (discoverLocation != null) {
                MarqueeMessage(
                    eyebrow = eyebrow,
                    title = stringResource(R.string.search_start_title),
                    body = AnnotatedString(
                        stringResource(
                            if (discoverLocation == DiscoverLocation.OFF) {
                                R.string.search_start_subtitle_no_discover
                            } else {
                                R.string.search_start_subtitle
                            }
                        )
                    )
                )
            }
            // Stage 4 draws Discover's band width (7 posters or 4 landscape cards).
            SearchBody.LOADING -> GridBandSkeleton(gridFormOf(landscape))
            SearchBody.ERROR -> MarqueeMessage(
                eyebrow = eyebrow,
                title = stringResource(R.string.search_error_failed),
                body = error?.let { errorStateText(it) },
                actionLabel = stringResource(HomeMessageAction.RETRY.label),
                actionIcon = HomeMessageAction.RETRY.icon,
                onAction = onRetry,
                focusRequester = retryFocusRequester
            )
            SearchBody.NO_RESULTS -> {
                val key = stringResource(R.string.search_on_enter_key).uppercase()
                val hint = if (searchOnEnterWaits.isEmpty()) {
                    stringResource(R.string.search_no_results_hint)
                } else {
                    searchOnEnterWaits.map { wait ->
                        "${wait.title} \u00b7 ${searchOnEnterWaitingText(wait)} $key"
                    }.joinToString("\n")
                }
                MarqueeMessage(
                    eyebrow = eyebrow,
                    title = stringResource(R.string.search_no_results_query, submittedQuery),
                    body = AnnotatedString(hint)
                )
            }
            SearchBody.RESULTS -> Unit
        }
    }
}

/**
 * Recent searches (Spec 38 stages 9-10, `.l38rp`): the newest [recentSearches] under a RECENT
 * SEARCHES eyebrow, a rule, then Clear history. OK on a query runs it; Up from the first row or
 * Back calls [onExit]. The panel floats over the page, so Down stops at Clear history and
 * Left/Right stay put. [firstRowRequester]: Down from the field lands there; [hasFocus]: focus
 * is in the panel (the caller tracks it in [modifier]), so Back closes it.
 */
@Composable
private fun RecentSearchesPanel(
    recentSearches: List<String>,
    firstRowRequester: FocusRequester,
    onSearchSelected: (String) -> Unit,
    onClearHistory: () -> Unit,
    onExit: () -> Unit,
    hasFocus: Boolean,
    modifier: Modifier = Modifier
) {
    val search = NuvioTheme.marquee.search
    val shape = RoundedCornerShape(search.recentsPanelRadius)
    BackHandler(enabled = hasFocus) { onExit() }
    val stayInPanel = Modifier.focusProperties {
        left = FocusRequester.Cancel
        right = FocusRequester.Cancel
    }
    Column(
        modifier = modifier
            .width(search.recentsPanelWidth)
            .shadow(search.recentsPanelShadow, shape)
            .clip(shape)
            .background(search.recentsPanelFill)
            .padding(search.recentsPanelPadding)
            .focusGroup()
    ) {
        PanelEyebrow(text = stringResource(R.string.search_recent_title))
        recentSearches.forEachIndexed { index, query ->
            RecentSearchRow(
                label = query,
                icon = Icons.Default.History,
                onClick = { onSearchSelected(query) },
                modifier = stayInPanel.then(
                    if (index == 0) {
                        Modifier
                            .focusRequester(firstRowRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_DPAD_UP) return@onPreviewKeyEvent false
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) onExit()
                                true
                            }
                    } else {
                        Modifier
                    }
                )
            )
        }
        Spacer(modifier = Modifier.height(RECENTS_RULE_GAP))
        PanelRule()
        RecentSearchRow(
            label = stringResource(R.string.search_recent_clear),
            icon = null,
            onClick = onClearHistory,
            modifier = stayInPanel.focusProperties { down = FocusRequester.Cancel }
        )
    }
}

/** `.l38rp .rule` margin: 6 dp above the rule over Clear history (PanelRule adds its own gap below). */
private val RECENTS_RULE_GAP = 6.dp

/**
 * One `.l38rr` row: the history [icon] then the query; without one, the dimmer "Clear history".
 * Focused: white fill, black text, no scale (the compact panels' rows).
 */
@Composable
private fun RecentSearchRow(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val marquee = NuvioTheme.marquee
    val search = marquee.search
    val white = NuvioPrimitives.white
    val black = NuvioPrimitives.black
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(search.recentsRowHeight)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(search.recentsRowRadius)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (icon == null) marquee.onImage2 else white,
            focusedContainerColor = white,
            focusedContentColor = black,
            pressedContainerColor = white,
            pressedContentColor = black
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = search.recentsRowPadding),
            horizontalArrangement = Arrangement.spacedBy(search.recentsRowGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (focused) black.copy(alpha = SUFFIX_ALPHA) else marquee.onImage3,
                    modifier = Modifier.size(search.iconSize)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Search field row (stage 1): optional compass and mic as glass circles, then the `search.fieldHeight`
 * glass field (white ring when focused) with its search icon and an inline clear circle.
 */
@Composable
private fun SearchInputField(
    query: String,
    canMoveToResults: Boolean,
    voiceFocusRequester: FocusRequester?,
    searchFocusRequester: FocusRequester,
    onRowFocusChanged: (Boolean) -> Unit,
    onFieldFocusChanged: (Boolean) -> Unit,
    onFieldPositioned: (Rect) -> Unit,
    onDownToRecents: (() -> Unit)?,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    showVoiceSearch: Boolean,
    isVoiceListening: Boolean,
    voiceRmsLevel: () -> Float,
    onVoiceSearch: () -> Unit,
    onMoveToResults: () -> Unit,
    onOpenDiscover: () -> Unit,
    showDiscoverButton: Boolean,
    keyboardController: SoftwareKeyboardController?,
    isScreenActive: Boolean = true
) {
    val marquee = NuvioTheme.marquee
    val search = marquee.search
    var isFieldFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.layout.tvSafeHorizontal)
            .onFocusChanged { onRowFocusChanged(it.hasFocus) }
            // Spec 38 stage 9: Down from the field enters the recent searches panel while it shows.
            // F-4: else Down from any control in the row (not only the text field) enters the
            // results through onMoveToResults: the last card for this query, else the first result.
            .onPreviewKeyEvent { keyEvent ->
                val down = keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                if (down && onDownToRecents != null) {
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) onDownToRecents()
                    true
                } else if (down && canMoveToResults) {
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        onMoveToResults()
                    }
                    true
                } else {
                    false
                }
            }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(search.fieldRowGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDiscoverButton) {
            MarqueeGlassButton(
                icon = Icons.Default.Explore,
                contentDescription = stringResource(R.string.cd_open_discover),
                onClick = onOpenDiscover
            )
        }

        if (showVoiceSearch) {
            Box(contentAlignment = Alignment.Center) {
                MarqueeGlassButton(
                    icon = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.cd_voice_search),
                    onClick = onVoiceSearch,
                    modifier = if (voiceFocusRequester != null) Modifier.focusRequester(voiceFocusRequester) else Modifier
                )
                if (isVoiceListening) {
                    VoiceListeningRings(rmsLevel = voiceRmsLevel, modifier = Modifier.matchParentSize())
                }
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .height(search.fieldHeight)
                .background(if (isFieldFocused) search.fieldFocusedFill else search.fieldFill, CircleShape)
                .border(
                    width = if (isFieldFocused) marquee.ringWidth else NuvioTheme.spacing.hairline,
                    color = if (isFieldFocused) marquee.ring else search.fieldEdge,
                    shape = CircleShape
                )
                // The pill's outer edge (before its padding): the panel's start lines up with it.
                .onGloballyPositioned { onFieldPositioned(it.boundsInWindow()) }
                .padding(start = search.fieldPaddingStart, end = search.fieldPaddingEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = marquee.onImage3,
                modifier = Modifier.size(search.iconSize)
            )
            Spacer(modifier = Modifier.width(search.fieldIconGap))
            // BasicTextField: the Material field's 56 dp minimum height cannot make the 44 dp pill.
            BasicTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocusRequester)
                    .focusProperties {
                        canFocus = isScreenActive
                    }
                    .onFocusChanged {
                        isFieldFocused = it.isFocused
                        onFieldFocusChanged(it.isFocused)
                    }
                    .onPreviewKeyEvent { keyEvent ->
                        // Down is the row's (above). Back is not handled (F-4, Arshad 2026-10-09):
                        // the open keyboard consumes the first Back, and Back from the field
                        // leaves Search, as on Library and Settings.
                        val code = keyEvent.nativeKeyEvent.keyCode
                        if (code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
                            return@onPreviewKeyEvent false
                        }
                        if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) onSubmit()
                        true
                    },
                textStyle = MaterialTheme.typography.titleMedium.copy(color = NuvioPrimitives.white),
                cursorBrush = SolidColor(marquee.ring),
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default.copy(
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        onSubmit()
                        keyboardController?.hide()
                    }
                ),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search_placeholder),
                                style = MaterialTheme.typography.titleMedium,
                                color = marquee.onImage3,
                                maxLines = 1
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // Inside the pill, but its own focus stop: reachable with the D-pad like the mic.
            if (query.isNotEmpty()) {
                Spacer(modifier = Modifier.width(search.fieldIconGap))
                IconButton(
                    onClick = { onQueryChanged("") },
                    modifier = Modifier.size(search.clearSize),
                    scale = IconButtonDefaults.scale(focusedScale = 1f),
                    shape = IconButtonDefaults.shape(shape = CircleShape),
                    colors = IconButtonDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = marquee.onImage2,
                        focusedContainerColor = NuvioPrimitives.white,
                        focusedContentColor = NuvioPrimitives.black,
                        pressedContainerColor = NuvioPrimitives.white,
                        pressedContentColor = NuvioPrimitives.black
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_clear_search),
                        modifier = Modifier.size(search.iconSize)
                    )
                }
            }
        }
    }
}

/**
 * Voice listening feedback drawn inside the mic circle (the list clips at the field row's top,
 * F-4 B, so rings outside it would be cut): a steady accent edge, a pulse every 800 ms and a ring
 * that follows the mic level. Composed only while listening; animations are read when drawing.
 */
@Composable
private fun VoiceListeningRings(rmsLevel: () -> Float, modifier: Modifier = Modifier) {
    val accent = NuvioTheme.colors.Secondary
    val pulse = rememberInfiniteTransition(label = "voicePulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )
    val level = animateFloatAsState(targetValue = rmsLevel(), animationSpec = tween(100), label = "rmsRing")
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val edge = 2.dp.toPx()
        drawCircle(color = accent, radius = radius - edge / 2f, style = Stroke(width = edge))
        val p = pulse.value
        drawCircle(color = accent.copy(alpha = (1f - p) * 0.2f), radius = radius * (0.55f + 0.45f * p))
        val rms = level.value
        if (rms > 0.01f) {
            drawCircle(
                color = accent.copy(alpha = 0.25f + rms * 0.25f),
                radius = radius * (0.5f + rms * 0.4f),
                style = Stroke(width = (1.5f + rms * 2f).dp.toPx())
            )
        }
    }
}
