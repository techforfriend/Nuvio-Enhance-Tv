package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.TmdbEntityKind
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuvio.tv.ui.util.localizedGenreLabel
import com.nuvio.tv.ui.util.recompositionHighlighter
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.DetailImdbRatingsVisibility
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.playRequestVideoId
import com.nuvio.tv.domain.model.resolveContentLanguage
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.rememberAmbientAccentProvider
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PanelActionRow
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.marquee.titleTypeEyebrow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.nuvio.tv.ui.util.requestFocusAfterFrames

private enum class RestoreTarget {
    HERO,
    EPISODE,
    CAST_MEMBER,
    MORE_LIKE_THIS,
    COLLECTION,
    COMPANY_OR_NETWORK
}

private data class DetailReturnEpisodeFocusRequest(
    val season: Int?,
    val episode: Int?
)

private fun resolveDetailReturnEpisodeFocusTarget(
    meta: Meta,
    request: DetailReturnEpisodeFocusRequest?
): Video? {
    val requestedSeason = request?.season ?: return null
    val requestedEpisode = request.episode ?: return null

    val orderedEpisodes = meta.videos
        .filter { it.season != null && it.episode != null }
        .sortedWith(compareBy({ it.season }, { it.episode }))
    if (orderedEpisodes.isEmpty()) return null

    val matchedIndex = orderedEpisodes.indexOfFirst {
        it.season == requestedSeason && it.episode == requestedEpisode
    }
    if (matchedIndex < 0) return null

    return orderedEpisodes[matchedIndex]
}

private const val USER_INTERACTION_DISPATCH_DEBOUNCE_MS = 120L

/** With neither an episodes band nor a section tab the first frame fills the 960x540 canvas. */
private val DETAIL_FIRST_FRAME_HEIGHT = 540.dp


private fun formatDetailYearRange(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return releaseInfo.trim()
}

/** Details' Play entries (play, play manually, start from beginning): what the stream route needs. */
typealias DetailPlayHandler = (
    videoId: String,
    contentType: String,
    contentId: String,
    title: String,
    poster: String?,
    backdrop: String?,
    logo: String?,
    season: Int?,
    episode: Int?,
    episodeName: String?,
    genres: String?,
    year: String?,
    runtime: Int?,
    contentLanguage: String?,
    streamRequestId: String?
) -> Unit

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MetaDetailsScreen(
    viewModel: MetaDetailsViewModel = hiltViewModel(),
    returnFocusSeason: Int? = null,
    returnFocusEpisode: Int? = null,
    heroRestoreToken: Int = 0,
    heroBackdropUrl: String? = null,
    onBackPress: () -> Unit,
    onReturnFocusConsumed: () -> Unit = {},
    onNavigateToCastDetail: (personId: Int, personName: String, department: String, sourceTitle: String, sectionLabel: String) -> Unit = { _, _, _, _, _ -> },
    onNavigateToSearch: (query: String) -> Unit = {},
    onNavigateToDiscoverGenre: (type: String, genre: String) -> Unit = { _, _ -> },
    onNavigateToTmdbEntityBrowse: (entityKind: String, entityId: Int, entityName: String, sourceType: String, sourceTitle: String, sectionLabel: String) -> Unit = { _, _, _, _, _, _ -> },
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit = { _, _, _ -> },
    onPlayClick: DetailPlayHandler = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onPlayManuallyClick: DetailPlayHandler = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onPlayStartFromBeginningClick: DetailPlayHandler = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    /** Who's watching, from the rating-limit block (Spec 64 D13). */
    onSwitchProfile: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Re-entered with the page already loaded (back from the player or a pushed screen): the
    // page shows at once; only a load seen on this screen fades the page in.
    val contentLoadedOnEntry = remember { uiState.meta != null && !uiState.isLoading && uiState.error == null }
    val heroSourceSignal by viewModel.heroSourceSignal.collectAsStateWithLifecycle()
    val effectiveAutoplayEnabled by viewModel.effectiveAutoplayEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )
    var restorePlayFocusAfterTrailerBackToken by rememberSaveable { mutableIntStateOf(0) }
    var restoreSharedTrailerFocusToken by rememberSaveable { mutableIntStateOf(0) }
    var isTrailerPaused by remember { mutableStateOf(false) }

    BackHandler {
        if (uiState.isSharedTrailerOverlayVisible) {
            restoreSharedTrailerFocusToken += 1
            viewModel.onEvent(MetaDetailsEvent.OnDismissSharedTrailer)
        } else if (uiState.isTrailerPlaying) {
            restorePlayFocusAfterTrailerBackToken += 1
            isTrailerPaused = false
            viewModel.onEvent(MetaDetailsEvent.OnTrailerEnded)
        } else {
            onBackPress()
        }
    }

    val currentIsTrailerPlaying by rememberUpdatedState(uiState.isTrailerPlaying)
    val currentShowTrailerControls by rememberUpdatedState(uiState.showTrailerControls)
    var trailerSeekOverlayVisible by remember { mutableStateOf(false) }
    val trailerSeekOverlayState = remember { TrailerSeekOverlayState() }
    var trailerSeekToken by remember { mutableIntStateOf(0) }
    var trailerSeekDeltaMs by remember { mutableLongStateOf(0L) }
    var lastUserInteractionDispatchMs by remember { mutableLongStateOf(0L) }
    val onTrailerProgressChanged = remember(trailerSeekOverlayState) {
        { position: Long, duration: Long ->
            trailerSeekOverlayState.positionMs = position
            trailerSeekOverlayState.durationMs = duration
        }
    }

    LaunchedEffect(uiState.userMessage) {
        if (uiState.userMessage != null) {
            delay(2500)
            viewModel.onEvent(MetaDetailsEvent.OnClearMessage)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE ->
                    viewModel.onEvent(MetaDetailsEvent.OnLifecyclePause)
                Lifecycle.Event.ON_RESUME ->
                    viewModel.onEvent(MetaDetailsEvent.OnLifecycleResume)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                if (currentIsTrailerPlaying) {
                    if (currentShowTrailerControls) {
                        if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                            return@onPreviewKeyEvent false
                        }
                        // Handled keys fall through to the consume-all return below (all but Back/Esc).
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                isTrailerPaused = !isTrailerPaused
                                trailerSeekOverlayVisible = true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                isTrailerPaused = !isTrailerPaused
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                isTrailerPaused = true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                isTrailerPaused = false
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                trailerSeekOverlayVisible = true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                trailerSeekOverlayVisible = false
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                                val delta = -trailerSeekStepMs(repeatCount)
                                trailerSeekDeltaMs = delta
                                trailerSeekToken += 1
                                trailerSeekOverlayVisible = true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                                val delta = trailerSeekStepMs(repeatCount)
                                trailerSeekDeltaMs = delta
                                trailerSeekToken += 1
                                trailerSeekOverlayVisible = true
                            }
                            else -> {}
                        }
                    }
                    // During auto trailer preview, consume all keys except back/ESC so content doesn't scroll.
                    val keyCode = keyEvent.nativeKeyEvent.keyCode
                    return@onPreviewKeyEvent keyCode != KeyEvent.KEYCODE_BACK &&
                            keyCode != KeyEvent.KEYCODE_ESCAPE
                }
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    val nativeEvent = keyEvent.nativeKeyEvent
                    val shouldDispatch =
                        nativeEvent.repeatCount == 0 &&
                            (nativeEvent.eventTime - lastUserInteractionDispatchMs) >=
                            USER_INTERACTION_DISPATCH_DEBOUNCE_MS
                    if (shouldDispatch) {
                        lastUserInteractionDispatchMs = nativeEvent.eventTime
                        viewModel.onEvent(MetaDetailsEvent.OnUserInteraction)
                    }
                }
                false
            }
    ) {
        when {
            uiState.ratingBlock != null -> DetailRatingBlockState(
                block = uiState.ratingBlock!!,
                onBack = onBackPress,
                onSwitchProfile = onSwitchProfile
            )
            // Loading: the handed-over Home backdrop under a first-frame skeleton (DetailStates.kt).
            uiState.isLoading -> DetailLoadingState(backdropUrl = heroBackdropUrl)
            uiState.error != null -> DetailErrorState(
                message = uiState.error.orEmpty(),
                onRetry = { viewModel.onEvent(MetaDetailsEvent.OnRetry) }
            )
            uiState.meta != null -> DetailContentEnter(
                animate = !contentLoadedOnEntry,
                backdropUrl = heroBackdropUrl
            ) {
                val meta = uiState.meta!!
                val context = LocalContext.current
                val genresString = remember(meta.genres) {
                    meta.genres.takeIf { it.isNotEmpty() }
                        ?.joinToString(" • ") { localizedGenreLabel(context, it) }
                }
                val yearString = remember(meta.releaseInfo) {
                    formatDetailYearRange(meta.releaseInfo)
                }

                MetaDetailsContent(
                    heroBackdropUrl = heroBackdropUrl,
                    meta = meta,
                    detailReturnEpisodeFocusRequest = DetailReturnEpisodeFocusRequest(
                        season = returnFocusSeason,
                        episode = returnFocusEpisode
                    ),
                    onDetailReturnEpisodeFocusConsumed = onReturnFocusConsumed,
                    lastFocusedEpisodeIdBySeason = viewModel.lastFocusedEpisodeIdBySeason,
                    onEpisodeFocusedForPrefetch = viewModel::onEpisodeFocusedForPrefetch,
                    heroRestoreToken = heroRestoreToken,
                    seasons = uiState.seasons,
                    selectedSeason = uiState.selectedSeason,
                    episodesForSeason = uiState.episodesForSeason,
                    isInLibrary = uiState.isInLibrary,
                    nextToWatch = uiState.nextToWatch,
                    episodeProgressMap = uiState.episodeProgressMap,
                    watchedEpisodes = uiState.watchedEpisodes,
                    episodeWatchedPendingKeys = uiState.episodeWatchedPendingKeys,
                    blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                    showFullReleaseDate = uiState.showFullReleaseDate,
                    overallRatingsVisibility = uiState.overallRatingsVisibility,
                    detailImdbRatingsVisibility = uiState.detailImdbRatingsVisibility,
                    isMovieWatched = uiState.isMovieWatched,
                    isMovieWatchedPending = uiState.isMovieWatchedPending,
                    moreLikeThis = uiState.moreLikeThis,
                    moreLikeThisSource = uiState.moreLikeThisSource,
                    collection = uiState.collection,
                    collectionName = uiState.collectionName,
                    aboutExtras = uiState.about,
                    onAboutOpened = { viewModel.onEvent(MetaDetailsEvent.OnAboutOpened) },
                    relatedWatchedStatus = uiState.relatedWatchedStatus,
                    episodeImdbRatings = uiState.episodeImdbRatings,
                    isEpisodeRatingsLoading = uiState.isEpisodeRatingsLoading,
                    episodeRatingsError = uiState.episodeRatingsError,
                    heroSourceSignal = heroSourceSignal,
                    onSeasonSelected = { viewModel.onEvent(MetaDetailsEvent.OnSeasonSelected(it)) },
                    onEpisodeClick = { video ->
                        onPlayClick(
                            video.id,
                            meta.apiType,
                            meta.id,
                            meta.name,
                            video.thumbnail ?: meta.poster,
                            meta.backdropUrl,
                            meta.logo,
                            video.season,
                            video.episode,
                            video.title,
                            null,
                            null,
                            video.runtime,
                            meta.resolveContentLanguage(),
                            null
                        )
                    },
                    onEpisodeManualPlayClick = { video ->
                        onPlayManuallyClick(
                            video.id,
                            meta.apiType,
                            meta.id,
                            meta.name,
                            video.thumbnail ?: meta.poster,
                            meta.backdropUrl,
                            meta.logo,
                            video.season,
                            video.episode,
                            video.title,
                            null,
                            null,
                            video.runtime,
                            meta.resolveContentLanguage(),
                            null
                        )
                    },
                    // Meta-level play: videoId (meta.id) stays the progress identity;
                    // the trailing arg only redirects the stream request (defaultVideoId).
                    onPlayClick = { videoId ->
                        onPlayClick(
                            videoId,
                            meta.apiType,
                            meta.id,
                            meta.name,
                            meta.poster,
                            meta.backdropUrl,
                            meta.logo,
                            null,
                            null,
                            null,
                            genresString,
                            yearString,
                            null,
                            meta.resolveContentLanguage(),
                            meta.playRequestVideoId()
                        )
                    },
                    onPlayManuallyClick = { videoId ->
                        onPlayManuallyClick(
                            videoId,
                            meta.apiType,
                            meta.id,
                            meta.name,
                            meta.poster,
                            meta.backdropUrl,
                            meta.logo,
                            null,
                            null,
                            null,
                            genresString,
                            yearString,
                            null,
                            meta.resolveContentLanguage(),
                            meta.playRequestVideoId()
                        )
                    },
                    onEpisodeStartFromBeginningClick = { video ->
                        onPlayStartFromBeginningClick(
                            video.id,
                            meta.apiType,
                            meta.id,
                            meta.name,
                            video.thumbnail ?: meta.poster,
                            meta.backdropUrl,
                            meta.logo,
                            video.season,
                            video.episode,
                            video.title,
                            null,
                            null,
                            video.runtime,
                            meta.resolveContentLanguage(),
                            null
                        )
                    },
                    onPlayStartFromBeginningClick = { videoId ->
                        onPlayStartFromBeginningClick(
                            videoId,
                            meta.apiType,
                            meta.id,
                            meta.name,
                            meta.poster,
                            meta.backdropUrl,
                            meta.logo,
                            null,
                            null,
                            null,
                            genresString,
                            yearString,
                            null,
                            meta.resolveContentLanguage(),
                            meta.playRequestVideoId()
                        )
                    },
                    showManualPlayOption = effectiveAutoplayEnabled,
                    onPlayButtonFocused = { viewModel.onEvent(MetaDetailsEvent.OnPlayButtonFocused) },
                    onToggleLibrary = { viewModel.onEvent(MetaDetailsEvent.OnToggleLibrary) },
                    onLibraryLongPress = { viewModel.onEvent(MetaDetailsEvent.OnLibraryLongPress) },
                    onToggleMovieWatched = { viewModel.onEvent(MetaDetailsEvent.OnToggleMovieWatched) },
                    onToggleEpisodeWatched = { video ->
                        viewModel.onEvent(MetaDetailsEvent.OnToggleEpisodeWatched(video))
                    },
                    onMarkSeasonWatched = { season ->
                        viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonWatched(season))
                    },
                    onMarkSeasonUnwatched = { season ->
                        viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonUnwatched(season))
                    },
                    onMarkPreviousEpisodesWatched = { video ->
                        viewModel.onEvent(MetaDetailsEvent.OnMarkPreviousEpisodesWatched(video))
                    },
                    onMarkPreviousSeasonsWatched = { season ->
                        viewModel.onEvent(MetaDetailsEvent.OnMarkPreviousSeasonsWatched(season))
                    },
                    isSeasonFullyWatched = { season ->
                        viewModel.isSeasonFullyWatched(season)
                    },
                    trailerUrl = uiState.trailerUrl,
                    trailerAudioUrl = uiState.trailerAudioUrl,
                    isTrailerPlaying = uiState.isTrailerPlaying,
                    isTrailerPaused = isTrailerPaused,
                    showTrailerControls = uiState.showTrailerControls,
                    hideLogoDuringTrailer = uiState.hideLogoDuringTrailer,
                    trailerButtonEnabled = uiState.trailerButtonEnabled,
                    isSharedTrailerOverlayVisible = uiState.isSharedTrailerOverlayVisible,
                    isSharedTrailerLoading = uiState.isSharedTrailerLoading,
                    sharedTrailerUrl = uiState.sharedTrailerUrl,
                    sharedTrailerAudioUrl = uiState.sharedTrailerAudioUrl,
                    sharedTrailerErrorMessage = uiState.sharedTrailerErrorMessage,
                    selectedSharedTrailer = uiState.selectedSharedTrailer,
                    trailerSeekToken = trailerSeekToken,
                    trailerSeekDeltaMs = trailerSeekDeltaMs,
                    onTrailerControlKey = { keyCode, action, repeatCount ->
                        if (!uiState.showTrailerControls || !uiState.isTrailerPlaying) {
                            false
                        } else if (action != KeyEvent.ACTION_DOWN) {
                            false
                        } else {
                            val seekStepMs = trailerSeekStepMs(repeatCount)
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                    isTrailerPaused = !isTrailerPaused
                                    trailerSeekOverlayVisible = true
                                    true
                                }
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                    isTrailerPaused = !isTrailerPaused
                                    true
                                }
                                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                    isTrailerPaused = true
                                    true
                                }
                                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                    isTrailerPaused = false
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    trailerSeekOverlayVisible = true
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    trailerSeekOverlayVisible = false
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    trailerSeekDeltaMs = -seekStepMs
                                    trailerSeekToken += 1
                                    trailerSeekOverlayVisible = true
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    trailerSeekDeltaMs = seekStepMs
                                    trailerSeekToken += 1
                                    trailerSeekOverlayVisible = true
                                    true
                                }
                                else -> false
                            }
                        }
                    },
                    onTrailerProgressChanged = onTrailerProgressChanged,
                    onTrailerEnded = { viewModel.onEvent(MetaDetailsEvent.OnTrailerEnded) },
                    onTrailerButtonClick = { viewModel.onEvent(MetaDetailsEvent.OnTrailerButtonClick) },
                    onSharedTrailerSelected = { viewModel.onEvent(MetaDetailsEvent.OnSharedTrailerSelected(it)) },
                    onDismissSharedTrailer = { viewModel.onEvent(MetaDetailsEvent.OnDismissSharedTrailer) },
                    onRetrySharedTrailer = { viewModel.onEvent(MetaDetailsEvent.OnRetrySharedTrailer) },
                    restorePlayFocusAfterTrailerBackToken = restorePlayFocusAfterTrailerBackToken,
                    restoreSharedTrailerFocusToken = restoreSharedTrailerFocusToken,
                    onSharedTrailerFocusRestored = { restoreSharedTrailerFocusToken = 0 },
                    onNavigateToCastDetail = onNavigateToCastDetail,
                    onNavigateToSearch = onNavigateToSearch,
                    onNavigateToDiscoverGenre = onNavigateToDiscoverGenre,
                    onNavigateToTmdbEntityBrowse = onNavigateToTmdbEntityBrowse,
                    onNavigateToDetail = onNavigateToDetail,
                    onPosterLongPress = { item -> viewModel.posterOptions.show(item, null) },
                    linksLimited = uiState.ratingLimit != null,
                    onLimitedLinkClick = { viewModel.onEvent(MetaDetailsEvent.OnRatingLimitedLink) }
                )
            }
        }

        if (uiState.showListPicker) {
            // Long-press picker: the app's own library is the only list.
            val nuvioListTab = LibraryListTab(
                key = LOCAL_LIBRARY_LIST_KEY,
                title = stringResource(R.string.trakt_library_source_nuvio),
                type = LibraryListTab.Type.WATCHLIST
            )
            LibraryListPickerDialog(
                title = uiState.meta?.name ?: stringResource(R.string.detail_lists_fallback),
                tabs = listOf(nuvioListTab),
                membership = uiState.pickerMembership,
                isPending = uiState.pickerPending,
                error = uiState.pickerError,
                onToggle = { key ->
                    viewModel.onEvent(MetaDetailsEvent.OnPickerMembershipToggled(key))
                },
                onSave = { viewModel.onEvent(MetaDetailsEvent.OnPickerSave) },
                onDismiss = { viewModel.onEvent(MetaDetailsEvent.OnPickerDismiss) }
            )
        }

        val message = uiState.userMessage
        if (!message.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = NuvioTheme.spacing.xl)
                    .background(
                        color = if (uiState.userMessageIsError) {
                            Color(0xFF5A1C1C)
                        } else {
                            NuvioTheme.colors.BackgroundElevated
                        },
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
        }

        TrailerSeekOverlayHost(
            visible = uiState.isTrailerPlaying && uiState.showTrailerControls && trailerSeekOverlayVisible,
            overlayState = trailerSeekOverlayState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    LaunchedEffect(trailerSeekOverlayVisible, uiState.isTrailerPlaying, uiState.showTrailerControls, trailerSeekToken) {
        if (trailerSeekOverlayVisible && uiState.isTrailerPlaying && uiState.showTrailerControls) {
            delay(TRAILER_SEEK_OVERLAY_AUTO_HIDE_MS)
            trailerSeekOverlayVisible = false
        }
    }

    LaunchedEffect(uiState.isTrailerPlaying, uiState.showTrailerControls) {
        if (!uiState.isTrailerPlaying || !uiState.showTrailerControls) {
            trailerSeekOverlayVisible = false
        }
    }

    val posterOptionsState by viewModel.posterOptions.state.collectAsStateWithLifecycle()
    com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = { id, type, addonBaseUrl ->
            onNavigateToDetail(id, type, addonBaseUrl.takeIf { it.isNotBlank() })
        }
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun MetaDetailsContent(
    heroBackdropUrl: String? = null,
    meta: Meta,
    detailReturnEpisodeFocusRequest: DetailReturnEpisodeFocusRequest? = null,
    onDetailReturnEpisodeFocusConsumed: () -> Unit,
    lastFocusedEpisodeIdBySeason: MutableMap<Int, String>,
    onEpisodeFocusedForPrefetch: (Video) -> Unit = {},
    heroRestoreToken: Int = 0,
    seasons: List<Int>,
    selectedSeason: Int,
    episodesForSeason: List<Video>,
    isInLibrary: Boolean,
    nextToWatch: NextToWatch?,
    episodeProgressMap: Map<Pair<Int, Int>, WatchProgress>,
    watchedEpisodes: Set<Pair<Int, Int>>,
    episodeWatchedPendingKeys: Set<String>,
    blurUnwatchedEpisodes: Boolean,
    showFullReleaseDate: Boolean,
    overallRatingsVisibility: HomeImdbRatingsVisibility,
    detailImdbRatingsVisibility: DetailImdbRatingsVisibility,
    isMovieWatched: Boolean,
    isMovieWatchedPending: Boolean,
    moreLikeThis: List<MetaPreview>,
    moreLikeThisSource: MoreLikeThisSource?,
    collection: List<MetaPreview>,
    collectionName: String?,
    aboutExtras: DetailAboutExtras,
    onAboutOpened: () -> Unit,
    relatedWatchedStatus: Map<String, Boolean> = emptyMap(),
    episodeImdbRatings: Map<Pair<Int, Int>, Double>,
    isEpisodeRatingsLoading: Boolean,
    episodeRatingsError: String?,
    heroSourceSignal: com.nuvio.tv.core.stream.SourcePrefetchSignal?,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeClick: (Video) -> Unit,
    onEpisodeManualPlayClick: (Video) -> Unit,
    onEpisodeStartFromBeginningClick: (Video) -> Unit = {},
    onPlayClick: (String) -> Unit,
    onPlayManuallyClick: (String) -> Unit,
    onPlayStartFromBeginningClick: (String) -> Unit = {},
    showManualPlayOption: Boolean,
    onPlayButtonFocused: () -> Unit,
    onToggleLibrary: () -> Unit,
    onLibraryLongPress: () -> Unit,
    onToggleMovieWatched: () -> Unit,
    onToggleEpisodeWatched: (Video) -> Unit,
    onMarkSeasonWatched: (Int) -> Unit,
    onMarkSeasonUnwatched: (Int) -> Unit,
    onMarkPreviousEpisodesWatched: (Video) -> Unit,
    onMarkPreviousSeasonsWatched: (Int) -> Unit,
    isSeasonFullyWatched: (Int) -> Boolean,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    isTrailerPlaying: Boolean,
    isTrailerPaused: Boolean = false,
    showTrailerControls: Boolean,
    hideLogoDuringTrailer: Boolean,
    trailerButtonEnabled: Boolean,
    isSharedTrailerOverlayVisible: Boolean,
    isSharedTrailerLoading: Boolean,
    sharedTrailerUrl: String?,
    sharedTrailerAudioUrl: String?,
    sharedTrailerErrorMessage: String?,
    selectedSharedTrailer: MetaTrailer?,
    trailerSeekToken: Int,
    trailerSeekDeltaMs: Long,
    onTrailerControlKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean,
    onTrailerProgressChanged: (Long, Long) -> Unit,
    onTrailerEnded: () -> Unit,
    onTrailerButtonClick: () -> Unit,
    onSharedTrailerSelected: (MetaTrailer) -> Unit,
    onDismissSharedTrailer: () -> Unit,
    onRetrySharedTrailer: () -> Unit,
    restorePlayFocusAfterTrailerBackToken: Int,
    restoreSharedTrailerFocusToken: Int,
    onSharedTrailerFocusRestored: () -> Unit,
    onNavigateToCastDetail: (personId: Int, personName: String, department: String, sourceTitle: String, sectionLabel: String) -> Unit = { _, _, _, _, _ -> },
    onNavigateToSearch: (query: String) -> Unit = {},
    onNavigateToDiscoverGenre: (type: String, genre: String) -> Unit = { _, _ -> },
    onNavigateToTmdbEntityBrowse: (entityKind: String, entityId: Int, entityName: String, sourceType: String, sourceTitle: String, sectionLabel: String) -> Unit = { _, _, _, _, _, _ -> },
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit = { _, _, _ -> },
    onPosterLongPress: (MetaPreview) -> Unit = {},
    // Spec 64 D12: under a rating limit OK on a cast member, studio or About person chip calls
    // [onLimitedLinkClick] (a toast) instead: no page to open, so no focus restore is marked.
    linksLimited: Boolean = false,
    onLimitedLinkClick: () -> Unit = {}
) {
    val isSeries = remember(meta.type, meta.videos) {
        meta.type == ContentType.SERIES || meta.videos.isNotEmpty()
    }
    val defaultSeriesVideo = remember(meta.behaviorHints?.defaultVideoId, meta.videos) {
        val defaultVideoId = meta.behaviorHints?.defaultVideoId
        meta.videos.firstOrNull { it.id == defaultVideoId && it.available != false }
    }
    val nextEpisode = remember(episodesForSeason) { episodesForSeason.firstOrNull() }
    val heroVideo = remember(meta.videos, nextToWatch, nextEpisode, defaultSeriesVideo, isSeries) {
        if (!isSeries) return@remember null
        val byId = nextToWatch?.nextVideoId?.let { id ->
            meta.videos.firstOrNull { it.id == id }
        }
        val bySeasonEpisode = if (byId == null && nextToWatch?.nextSeason != null && nextToWatch.nextEpisode != null) {
            meta.videos.firstOrNull { it.season == nextToWatch.nextSeason && it.episode == nextToWatch.nextEpisode }
        } else {
            null
        }
        byId ?: bySeasonEpisode ?: defaultSeriesVideo ?: nextEpisode
    }
    val nestedPrefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 2) }
    val listState = rememberLazyListState(prefetchStrategy = nestedPrefetchStrategy)
    // ---- Section band content (tab per section with content) ----
    val castMembersToShow = remember(meta.castMembers, meta.cast) {
        if (meta.castMembers.isNotEmpty()) {
            meta.castMembers
        } else {
            meta.cast.map { name -> MetaCastMember(name = name) }
        }
    }
    // Lead credits (creators, else directors, else writers) lead the cast row, as built.
    val directorWriterMembers = remember(castMembersToShow) {
        val creators = castMembersToShow.filter { it.character.equals("Creator", ignoreCase = true) }
        val directors = castMembersToShow.filter { it.character.equals("Director", ignoreCase = true) }
        val writers = castMembersToShow.filter { it.character.equals("Writer", ignoreCase = true) }
        when {
            creators.isNotEmpty() -> creators
            directors.isNotEmpty() -> directors
            else -> writers
        }
    }
    val normalCastMembers = remember(castMembersToShow, directorWriterMembers) {
        fun creditKey(member: MetaCastMember) = listOf(
            member.tmdbId?.toString().orEmpty(),
            member.name.trim().lowercase(),
            member.character.orEmpty().trim().lowercase()
        ).joinToString("|")
        val leadingKeys = directorWriterMembers.mapTo(mutableSetOf(), ::creditKey)
        castMembersToShow.filterNot { isLeadCreditRole(it.character) && creditKey(it) in leadingKeys }
    }
    val isTvShow = remember(meta.type, meta.apiType) {
        meta.type == ContentType.SERIES ||
            meta.type == ContentType.TV ||
            meta.apiType in listOf("series", "tv")
    }
    val showStandardOverallRatings = overallRatingsVisibility.showRatings
    val companyItems = remember(meta.networks, meta.productionCompanies, isTvShow) {
        val networks = meta.networks.map { DetailCompanyItem(it, TmdbEntityKind.NETWORK.routeValue) }
        val studios = meta.productionCompanies.map { DetailCompanyItem(it, TmdbEntityKind.COMPANY.routeValue) }
        if (isTvShow) networks + studios else studios + networks
    }
    val strCast = stringResource(R.string.detail_section_cast)
    val strMoreLikeThis = stringResource(R.string.detail_tab_more_like_this)
    val strRatings = stringResource(R.string.detail_tab_ratings)
    val strTrailers = stringResource(R.string.detail_section_trailers)
    val strCollection = collectionName ?: stringResource(R.string.tmdb_collections_title)
    val strStudios = stringResource(R.string.detail_section_studios)
    val strNetwork = stringResource(R.string.detail_section_network)
    val strCompanies = if (isTvShow && meta.networks.isNotEmpty()) strNetwork else strStudios
    val resources = LocalResources.current
    val sectionTabs = remember(
        castMembersToShow, moreLikeThis, isTvShow, detailImdbRatingsVisibility, meta.trailers, collection, companyItems,
        strCast, strMoreLikeThis, strRatings, strTrailers, strCollection, strCompanies
    ) {
        buildList {
            if (castMembersToShow.isNotEmpty()) add(DetailSectionTab(DetailSection.CAST, strCast))
            if (moreLikeThis.isNotEmpty()) add(DetailSectionTab(DetailSection.MORE_LIKE_THIS, strMoreLikeThis))
            if (isTvShow && detailImdbRatingsVisibility.showEpisodeRatings) add(DetailSectionTab(DetailSection.RATINGS, strRatings))
            if (meta.trailers.any { !it.ytId.isNullOrBlank() }) add(DetailSectionTab(DetailSection.TRAILERS, strTrailers))
            if (collection.isNotEmpty()) add(DetailSectionTab(DetailSection.COLLECTION, strCollection))
            if (companyItems.isNotEmpty()) add(DetailSectionTab(DetailSection.COMPANIES, strCompanies))
        }
    }
    val sectionTabRequesters = remember { DetailSection.entries.associateWith { FocusRequester() } }
    var activeSection by rememberSaveable(meta.id) { mutableStateOf(DetailSection.CAST) }

    // ---- Bands + scrolled state (Marquee spec §Screens "Detail as decided at the U2 slice") ----
    val showSeasonTabs = isSeries && seasons.isNotEmpty() && !(seasons.size == 1 && meta.apiType.equals("other", ignoreCase = true))
    val showEpisodesRow = isSeries && seasons.isNotEmpty()
    val showSections = sectionTabs.isNotEmpty()
    val sectionsItemIndex = 1 + (if (showSeasonTabs) 1 else 0) + (if (showEpisodesRow) 1 else 0)
    val bandGeometry = rememberDetailBandGeometry(
        hasEpisodes = showEpisodesRow,
        hasChips = showSeasonTabs,
        hasSections = showSections,
        noBandFirstFrameHeight = DETAIL_FIRST_FRAME_HEIGHT
    )
    // Scrolled fraction: 0 on the first frame, 1 once the band sits at its anchor (or the page
    // scrolled that far). The info column fades out and the scrims swap with it, at draw time.
    val scrolledFraction: () -> Float = remember(listState, bandGeometry) {
        {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / bandGeometry.scrollTravelPx).coerceIn(0f, 1f)
        }
    }
    // Written by the bands' focus callbacks, read only by the block, the overlays' draw phase and
    // the effect below, so a band's own focus state never recomposes this screen.
    val bandFocus = remember { mutableStateOf<DetailBandFocus?>(null) }
    val bandFocusReader = remember(bandFocus) { { bandFocus.value } }
    val sectionsFocused = remember(bandFocus) { derivedStateOf { bandFocus.value?.inSections == true } }
    // Focus entering a band anchors it (band items opt out of bring-into-view): the episodes
    // band's first item (list item 1) goes to episodesTopPx, the section band to sectionsTopPx.
    LaunchedEffect(listState, bandGeometry, sectionsItemIndex) {
        snapshotFlow { bandFocus.value?.inSections }.collectLatest { inSections ->
            when (inSections) {
                false -> listState.animateScrollToItem(1, -bandGeometry.episodesTopPx)
                true -> listState.animateScrollToItem(sectionsItemIndex, -bandGeometry.sectionsTopPx)
                null -> Unit
            }
        }
    }
    val selectedSeasonFocusRequester = remember { FocusRequester() }
    val heroPlayFocusRequester = remember { FocusRequester() }
    var pendingRestoreType by rememberSaveable { mutableStateOf<RestoreTarget?>(null) }
    var pendingRestoreEpisodeId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRestoreCastPersonId by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingRestoreMoreLikeItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRestoreCollectionItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRestoreCompanyId by rememberSaveable { mutableStateOf<Int?>(null) }
    var restoreFocusToken by rememberSaveable { mutableIntStateOf(0) }
    // Bumped only when a company click's restore fires on return: the logo row restores on it
    // alone (as built).
    var companyRestoreToken by rememberSaveable { mutableIntStateOf(0) }
    // A restore marked by a click, fired on return (fireDeferredRestore below).
    var restoreOnReturn by rememberSaveable { mutableStateOf(false) }
    // The last heroRestoreToken handled: the savedState token is never reset, so it must not
    // re-mark a hero restore on every re-entry (it would override a section restore).
    var handledHeroRestoreToken by rememberSaveable { mutableIntStateOf(0) }
    var consumeReturnEpisodeFocusOnClear by rememberSaveable(meta.id) { mutableStateOf(false) }
    var initialHeroFocusRequested by rememberSaveable(meta.id) { mutableStateOf(false) }
    var showHeroPlayOptionsDialog by rememberSaveable(meta.id) { mutableStateOf(false) }
    var showAbout by rememberSaveable(meta.id) { mutableStateOf(false) }
    var initialDetailReturnFocusHandled by rememberSaveable(
        meta.id,
        detailReturnEpisodeFocusRequest?.season,
        detailReturnEpisodeFocusRequest?.episode
    ) {
        mutableStateOf(false)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    fun clearPendingRestore() {
        val shouldConsumeReturnFocus = consumeReturnEpisodeFocusOnClear
        pendingRestoreType = null
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
        pendingRestoreCollectionItemId = null
        pendingRestoreCompanyId = null
        companyRestoreToken = 0
        restoreOnReturn = false
        consumeReturnEpisodeFocusOnClear = false
        if (shouldConsumeReturnFocus) {
            onDetailReturnEpisodeFocusConsumed()
        }
    }

    /**
     * Records where focus returns to (one target; the id for [type], the rest cleared). The token
     * restarts at 0, so the target's restore runs only when [fireDeferredRestore] (or the caller)
     * bumps it: a token left from an earlier restore would re-launch the target row's restore on
     * the departing screen and pull focus back from the next one. [onReturn] false: the caller
     * fires the restore itself.
     */
    fun markRestore(
        type: RestoreTarget,
        episodeId: String? = null,
        castPersonId: Int? = null,
        moreLikeItemId: String? = null,
        collectionItemId: String? = null,
        companyId: Int? = null,
        onReturn: Boolean = true
    ) {
        restoreOnReturn = onReturn && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        restoreFocusToken = 0
        pendingRestoreType = type
        pendingRestoreEpisodeId = episodeId
        pendingRestoreCastPersonId = castPersonId
        pendingRestoreMoreLikeItemId = moreLikeItemId
        pendingRestoreCollectionItemId = collectionItemId
        pendingRestoreCompanyId = companyId
    }

    LaunchedEffect(heroRestoreToken) {
        if (heroRestoreToken > handledHeroRestoreToken) {
            handledHeroRestoreToken = heroRestoreToken
            markRestore(RestoreTarget.HERO)
            restoreFocusToken += 1
        }
    }

    fun fireDeferredRestore() {
        if (!restoreOnReturn || pendingRestoreType == null) return
        restoreOnReturn = false
        restoreFocusToken += 1
        if (pendingRestoreType == RestoreTarget.COMPANY_OR_NETWORK) {
            companyRestoreToken += 1
        }
    }

    // Back from a screen this one navigated to recomposes it at the start of the pop transition,
    // RESUMED only at its end. The restore fires here, while the leaving screen still holds focus:
    // waiting for ON_RESUME lost the race to the leaving screen's disposal, which clears focus,
    // and Compose's initial focus then lands on the description, whose onActionFocused scrolls
    // the page to the top and clears the pending restore (F-12). No-op on a first open.
    LaunchedEffect(Unit) { fireDeferredRestore() }

    // Still needed when this screen never left composition (Back during the forward transition).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) fireDeferredRestore()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(
        meta.id,
        detailReturnEpisodeFocusRequest?.season,
        detailReturnEpisodeFocusRequest?.episode
    ) {
        if (initialDetailReturnFocusHandled) return@LaunchedEffect
        if (!isSeries) {
            initialDetailReturnFocusHandled = true
            return@LaunchedEffect
        }
        val request = detailReturnEpisodeFocusRequest
        if (request?.season == null || request.episode == null) {
            initialDetailReturnFocusHandled = true
            return@LaunchedEffect
        }
        val targetEpisode = resolveDetailReturnEpisodeFocusTarget(
            meta = meta,
            request = request
        )
        initialDetailReturnFocusHandled = true
        if (targetEpisode == null) {
            onDetailReturnEpisodeFocusConsumed()
            return@LaunchedEffect
        }

        val targetSeason = targetEpisode.season
        if (targetSeason != null && selectedSeason != targetSeason) {
            onSeasonSelected(targetSeason)
        }
        // Prevent the default hero autofocus from stealing focus after the episode restore completes.
        initialHeroFocusRequested = true
        consumeReturnEpisodeFocusOnClear = true
        markRestore(RestoreTarget.EPISODE, episodeId = targetEpisode.id, onReturn = false)
        restoreFocusToken += 1
        if (seasons.isNotEmpty()) {
            // Compose the episodes row before requesting focus on a card, already at its anchor.
            listState.scrollToItem(1, -bandGeometry.episodesTopPx)
        }
    }

    val visibleEpisodeImdbRatings = remember(
        episodeImdbRatings,
        detailImdbRatingsVisibility,
        episodeProgressMap,
        watchedEpisodes
    ) {
        episodeImdbRatings.filterKeys { episodeKey ->
            val isWatched = episodeProgressMap[episodeKey]?.isCompleted() == true ||
                watchedEpisodes.contains(episodeKey)
            detailImdbRatingsVisibility.showEpisodeRating(isWatched)
        }
    }
    val moreLikeThisSourceLabel = when (moreLikeThisSource) {
        MoreLikeThisSource.TMDB -> stringResource(R.string.detail_more_like_this_powered_by_tmdb)
        null -> null
    }
    var seasonOptionsDialogSeason by remember { mutableStateOf<Int?>(null) }
    // Tracks whether the initial auto-scroll to the "next to play" episode has fired.
    // Once it fires, no more auto-scrolls happen for the lifetime of this detail screen.
    var initialEpisodeScrollDone by remember(meta.id) { mutableStateOf(false) }
    val episodeFocusRequestersBySeason = remember(meta.id) { mutableMapOf<Int, MutableMap<String, FocusRequester>>() }
    val seasonEpisodeFocusRequesters = remember(selectedSeason, episodesForSeason) {
        val byEpisodeId = episodeFocusRequestersBySeason.getOrPut(selectedSeason) { mutableMapOf() }
        episodesForSeason.forEach { episode ->
            if (!byEpisodeId.containsKey(episode.id)) {
                byEpisodeId[episode.id] = FocusRequester()
            }
        }
        byEpisodeId.keys.retainAll(episodesForSeason.map { it.id }.toSet())
        byEpisodeId
    }
    // lastFocusedEpisodeIdBySeason (a snapshot map written on every card move) is read only when
    // focus moves (the episode target below), through this derived flag, or unobserved when the
    // episodes row is built (its anchor), never by this body, so card moves never recompose the
    // screen.
    val hasFocusedEpisodeInSeason by remember(lastFocusedEpisodeIdBySeason, selectedSeason) {
        derivedStateOf { lastFocusedEpisodeIdBySeason[selectedSeason] != null }
    }
    // Where Down from the season chips and Up from the section tabs land in the episodes band:
    // the season's last focused card, else the next-to-watch / default episode, else the first.
    val episodeFocusTarget: () -> FocusRequester? = remember(
        selectedSeason, episodesForSeason, seasonEpisodeFocusRequesters, lastFocusedEpisodeIdBySeason, nextToWatch, defaultSeriesVideo
    ) {
        {
            val nextEpisodeId = if (pendingRestoreType == RestoreTarget.EPISODE) {
                null
            } else {
                nextToWatch?.nextVideoId
                    ?: nextToWatch?.let { ntw -> episodesForSeason.firstOrNull { it.season == ntw.nextSeason && it.episode == ntw.nextEpisode }?.id }
                    ?: defaultSeriesVideo?.id?.takeIf { defaultId -> episodesForSeason.any { it.id == defaultId } }
            }
            val preferredEpisodeId = lastFocusedEpisodeIdBySeason[selectedSeason]
                ?: nextEpisodeId?.takeIf { episodesForSeason.any { ep -> ep.id == it } }
            (preferredEpisodeId?.let { seasonEpisodeFocusRequesters[it] })
                ?: episodesForSeason.firstOrNull()?.id?.let { seasonEpisodeFocusRequesters[it] }
        }
    }
    val sectionsUpTarget: () -> FocusRequester = remember(episodeFocusTarget, showSeasonTabs) {
        { episodeFocusTarget() ?: if (showSeasonTabs) selectedSeasonFocusRequester else heroPlayFocusRequester }
    }
    // The section shown: the active one while it has a tab, else the first tab.
    fun shownSection(): DetailSection? =
        activeSection.takeIf { section -> sectionTabs.any { it.section == section } } ?: sectionTabs.firstOrNull()?.section
    // Down from the episodes band: the shown section's tab (read when focus moves).
    val selectedTabTarget: () -> FocusRequester? = remember(sectionTabs, sectionTabRequesters) {
        { shownSection()?.let { sectionTabRequesters.getValue(it) } }
    }

    // Select the restore target's section when restoring focus after navigation.
    LaunchedEffect(restoreFocusToken, pendingRestoreType) {
        if (restoreFocusToken <= 0) return@LaunchedEffect
        val target = when (pendingRestoreType) {
            RestoreTarget.CAST_MEMBER -> DetailSection.CAST
            RestoreTarget.MORE_LIKE_THIS -> DetailSection.MORE_LIKE_THIS
            RestoreTarget.COLLECTION -> DetailSection.COLLECTION
            RestoreTarget.COMPANY_OR_NETWORK -> DetailSection.COMPANIES
            else -> null
        } ?: return@LaunchedEffect
        if (sectionTabs.any { it.section == target }) activeSection = target
    }

    // Stable hero play callback
    val heroPlayClick = remember(heroVideo, meta.id, onEpisodeClick, onPlayClick) {
        {
            markRestore(RestoreTarget.HERO)
            if (heroVideo != null) {
                onEpisodeClick(heroVideo)
            } else {
                onPlayClick(meta.id)
            }
        }
    }
    val heroPlayManualClick = remember(heroVideo, meta.id, onEpisodeManualPlayClick, onPlayManuallyClick) {
        {
            markRestore(RestoreTarget.HERO)
            if (heroVideo != null) {
                onEpisodeManualPlayClick(heroVideo)
            } else {
                onPlayManuallyClick(meta.id)
            }
        }
    }
    val heroPlayStartFromBeginningClick = remember(heroVideo, meta.id, onEpisodeStartFromBeginningClick, onPlayStartFromBeginningClick) {
        {
            markRestore(RestoreTarget.HERO)
            if (heroVideo != null) {
                onEpisodeStartFromBeginningClick(heroVideo)
            } else {
                onPlayStartFromBeginningClick(meta.id)
            }
        }
    }

    val episodeClick = remember(onEpisodeClick) {
        { video: Video ->
            markRestore(RestoreTarget.EPISODE, episodeId = video.id)
            onEpisodeClick(video)
        }
    }
    val episodeManualClick = remember(onEpisodeManualPlayClick) {
        { video: Video ->
            markRestore(RestoreTarget.EPISODE, episodeId = video.id)
            onEpisodeManualPlayClick(video)
        }
    }
    LaunchedEffect(
        pendingRestoreType,
        pendingRestoreEpisodeId,
        initialHeroFocusRequested,
        isTrailerPlaying
    ) {
        if (
            !initialHeroFocusRequested &&
            pendingRestoreType == null &&
            pendingRestoreEpisodeId == null &&
            !isTrailerPlaying
        ) {
            repeat(3) {
                if (initialHeroFocusRequested) return@repeat
                heroPlayFocusRequester.requestFocusAfterFrames()
                delay(80)
            }
        }
    }

    // The Home hero's backdrop, when one was handed over, stays for the whole visit (no swap to a
    // differently cropped copy); the ambient accent follows the backdrop shown.
    val backdropUrl = heroBackdropUrl?.takeIf { it.isNotBlank() } ?: meta.backdropUrl ?: meta.poster
    val accent = rememberAmbientAccentProvider(backdropUrl)
    val seasonCount = remember(seasons) { seasons.count { it > 0 } }
    val pageEyebrow = titleTypeEyebrow(meta.apiType, isSeries, seasonCount, meta.status)
    // The sheet's extras load once per title, when it first opens (or reopens after a pushed screen).
    LaunchedEffect(showAbout, meta.id) { if (showAbout) onAboutOpened() }

    val detailTokens = NuvioTheme.marquee.detail

    Box(modifier = Modifier.fillMaxSize()) {
        DetailBackdrop(
            backdropUrl = backdropUrl,
            trailerUrl = trailerUrl,
            trailerAudioUrl = trailerAudioUrl,
            isTrailerPlaying = isTrailerPlaying,
            isTrailerPaused = isTrailerPaused,
            showTrailerControls = showTrailerControls,
            trailerSeekToken = trailerSeekToken,
            trailerSeekDeltaMs = trailerSeekDeltaMs,
            onTrailerControlKey = onTrailerControlKey,
            onTrailerProgressChanged = onTrailerProgressChanged,
            onTrailerEnded = onTrailerEnded,
            scrolled = scrolledFraction
        )

        // Single scrollable column with hero + content, plus the scrolled-state overlays. They
        // fade out under the trailer preview and the About sheet; the backdrop stays.
        val contentAlpha = animateFloatAsState(
            targetValue = if (isTrailerPlaying || showAbout) 0f else 1f,
            animationSpec = tween(durationMillis = NuvioTheme.motion.durations.overlay),
            label = "detailContentFade"
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha.value }
                .recompositionHighlighter(),
            state = listState,
            // Lets the band reach its anchor even with nothing below it.
            contentPadding = PaddingValues(bottom = detailTokens.bandBottomInset)
        ) {
            // First frame: the info column; the episodes band (or the section band) starts below it.
            // A 2-line title can outgrow the frame: the item then grows (the band starts lower),
            // keeping `heroAboveRow` under the actions. In the scrolled state the column leaves
            // upward, fading out with the scroll.
            item(key = "hero", contentType = "hero") {
                Box(
                    modifier = Modifier
                        .noPageScrollOnFocus()
                        .fillMaxWidth()
                        .heightIn(min = bandGeometry.firstFrameHeight)
                        .padding(bottom = NuvioTheme.marquee.home.heroAboveRow)
                        .graphicsLayer { alpha = 1f - scrolledFraction() }
                ) {
                    DetailInfoColumn(
                        meta = meta,
                        isSeries = isSeries,
                        seasonCount = seasonCount,
                        collectionName = collectionName,
                        nextToWatch = nextToWatch,
                        accent = accent,
                        sourceSignal = heroSourceSignal,
                        showImdbRating = showStandardOverallRatings,
                        showFullReleaseDate = showFullReleaseDate,
                        isInLibrary = isInLibrary,
                        isMovieWatched = isMovieWatched,
                        isMovieWatchedPending = isMovieWatchedPending,
                        trailerAvailable = trailerButtonEnabled && !trailerUrl.isNullOrBlank(),
                        playFocusRequester = heroPlayFocusRequester,
                        restorePlayFocusToken = (if (pendingRestoreType == RestoreTarget.HERO) restoreFocusToken else 0) +
                            restorePlayFocusAfterTrailerBackToken,
                        onPlayClick = heroPlayClick,
                        onPlayLongPress = if (showManualPlayOption || nextToWatch?.isResume == true) {
                            { showHeroPlayOptionsDialog = true }
                        } else {
                            null
                        },
                        onToggleLibrary = onToggleLibrary,
                        onLibraryLongPress = onLibraryLongPress,
                        onToggleMovieWatched = onToggleMovieWatched,
                        onTrailerClick = onTrailerButtonClick,
                        onActionFocused = {
                            if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            }
                            initialHeroFocusRequested = true
                            if (pendingRestoreType != RestoreTarget.HERO) {
                                clearPendingRestore()
                            }
                        },
                        onPlayFocused = {
                            onPlayButtonFocused()
                            initialHeroFocusRequested = true
                            clearPendingRestore()
                        },
                        onShowAbout = { showAbout = true }
                    )
                }
            }

            // Episodes band (series): season chips over the episode cards. Focus inside it
            // anchors the band (effect above) and feeds the scrolled-state block. Hidden (still
            // focusable) while the section band below holds focus: bands above the focused one
            // scroll out under the block, as on Home.
            if (showSeasonTabs) {
                item(key = "season_tabs", contentType = "season_tabs") {
                    Box(
                        modifier = Modifier
                            .noPageScrollOnFocus()
                            .onFocusChanged {
                                if (!it.hasFocus && bandFocus.value is DetailBandFocus.Season) bandFocus.value = null
                            }
                            .graphicsLayer { alpha = if (sectionsFocused.value) 0f else 1f }
                            .padding(bottom = detailTokens.chipsToCards)
                    ) {
                        SeasonChips(
                            seasons = seasons,
                            selectedSeason = selectedSeason,
                            onSeasonSelected = onSeasonSelected,
                            onSeasonLongPress = { seasonOptionsDialogSeason = it },
                            onSeasonFocused = { bandFocus.value = DetailBandFocus.Season(it) },
                            selectedChipFocusRequester = selectedSeasonFocusRequester,
                            upFocusRequester = heroPlayFocusRequester,
                            downFocusRequester = episodeFocusTarget,
                            isFocusEnabled = pendingRestoreType != RestoreTarget.EPISODE
                        )
                    }
                }
            }
            if (showEpisodesRow) {
                item(key = "episodes_$selectedSeason", contentType = "episodes") {
                    Box(
                        modifier = Modifier
                            .noPageScrollOnFocus()
                            .onFocusChanged {
                                if (!it.hasFocus && (bandFocus.value as? DetailBandFocus.Episode)?.inSections == false) {
                                    bandFocus.value = null
                                }
                            }
                            .graphicsLayer { alpha = if (sectionsFocused.value) 0f else 1f }
                            .padding(bottom = detailTokens.bandAfterGap)
                    ) {
                        EpisodesRow(
                            episodes = episodesForSeason,
                            accent = accent,
                            episodeProgressMap = episodeProgressMap,
                            episodeRatings = visibleEpisodeImdbRatings,
                            watchedEpisodes = watchedEpisodes,
                            episodeWatchedPendingKeys = episodeWatchedPendingKeys,
                            blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                            onEpisodeClick = episodeClick,
                            onEpisodeManualPlayClick = episodeManualClick,
                            onEpisodeStartFromBeginningClick = { video ->
                                markRestore(RestoreTarget.EPISODE, episodeId = video.id)
                                onEpisodeStartFromBeginningClick(video)
                            },
                            showManualPlayOption = showManualPlayOption,
                            onToggleEpisodeWatched = onToggleEpisodeWatched,
                            onMarkSeasonWatched = onMarkSeasonWatched,
                            onMarkSeasonUnwatched = onMarkSeasonUnwatched,
                            isSeasonFullyWatched = isSeasonFullyWatched(selectedSeason),
                            selectedSeason = selectedSeason,
                            onMarkPreviousEpisodesWatched = onMarkPreviousEpisodesWatched,
                            upFocusRequester = if (showSeasonTabs) selectedSeasonFocusRequester else heroPlayFocusRequester,
                            downFocusRequester = selectedTabTarget,
                            episodeFocusRequesters = seasonEpisodeFocusRequesters,
                            restoreEpisodeId = if (pendingRestoreType == RestoreTarget.EPISODE) pendingRestoreEpisodeId else null,
                            restoreFocusToken = if (pendingRestoreType == RestoreTarget.EPISODE) restoreFocusToken else 0,
                            onRestoreFocusHandled = {
                                clearPendingRestore()
                            },
                            anchorEpisodeId = { lastFocusedEpisodeIdBySeason[selectedSeason] },
                            onEpisodeFocused = { focusedEpisode ->
                                lastFocusedEpisodeIdBySeason[selectedSeason] = focusedEpisode.id
                                onEpisodeFocusedForPrefetch(focusedEpisode)
                                bandFocus.value = DetailBandFocus.Episode(focusedEpisode)
                            },
                            scrollToEpisodeId = if (hasFocusedEpisodeInSeason) {
                                null
                            } else if (!initialEpisodeScrollDone && pendingRestoreType != RestoreTarget.EPISODE) {
                                val ntwId = nextToWatch?.nextVideoId
                                    ?: nextToWatch?.let { ntw -> episodesForSeason.firstOrNull { it.season == ntw.nextSeason && it.episode == ntw.nextEpisode }?.id }
                                if (ntwId != null) {
                                    ntwId
                                } else if (nextToWatch != null) {
                                    // nextToWatch resolved but target is in a different season — mark done and fall through.
                                    initialEpisodeScrollDone = true
                                    defaultSeriesVideo?.id?.takeIf { defaultId -> episodesForSeason.any { it.id == defaultId } }
                                } else {
                                    // nextToWatch not yet calculated — emit null so LaunchedEffect waits.
                                    null
                                }
                            } else null,
                            onScrollToEpisodeHandled = {
                                initialEpisodeScrollDone = true
                            }
                        )
                    }
                }
            }

            // Section band: one tab strip (Cast · More like this · Ratings · Trailers · Collection ·
            // Studios/Network, each only with content) over the selected section. Anchored on focus
            // like the episodes band; the block follows the focused item.
            if (showSections) {
                item(key = "sections", contentType = "sections") {
                    val selectedSection = checkNotNull(shownSection())
                    DetailSectionsBand(
                        tabs = sectionTabs,
                        selected = selectedSection,
                        tabRequesters = sectionTabRequesters,
                        upTarget = sectionsUpTarget,
                        onSelect = { activeSection = it },
                        onTabFocused = { bandFocus.value = DetailBandFocus.Title },
                        trailingLabel = moreLikeThisSourceLabel?.takeIf { selectedSection == DetailSection.MORE_LIKE_THIS },
                        modifier = Modifier
                            .noPageScrollOnFocus()
                            .onFocusChanged {
                                if (!it.hasFocus && bandFocus.value?.inSections == true) bandFocus.value = null
                            }
                    ) { section ->
                        val tabRequester = sectionTabRequesters.getValue(section)
                        val focusTitle = { bandFocus.value = DetailBandFocus.Title }
                        when (section) {
                            DetailSection.CAST -> DetailCastRow(
                                leadingCast = directorWriterMembers,
                                cast = normalCastMembers,
                                accent = accent,
                                upFocusRequester = tabRequester,
                                restorePersonId = if (pendingRestoreType == RestoreTarget.CAST_MEMBER) pendingRestoreCastPersonId else null,
                                restoreFocusToken = if (pendingRestoreType == RestoreTarget.CAST_MEMBER) restoreFocusToken else 0,
                                onRestoreFocusHandled = { clearPendingRestore() },
                                onFocused = focusTitle,
                                onClick = { member ->
                                    val id = member.tmdbId
                                    if (linksLimited) {
                                        onLimitedLinkClick()
                                    } else if (id != null) {
                                        markRestore(RestoreTarget.CAST_MEMBER, castPersonId = id)
                                        onNavigateToCastDetail(id, member.name, personPageDepartment(member.character), meta.name, strCast)
                                    } else if (member.name.isNotBlank()) {
                                        // Addon cast without a TMDB id has no cast page; search by name.
                                        onNavigateToSearch(member.name.trim())
                                    }
                                }
                            )

                            DetailSection.MORE_LIKE_THIS, DetailSection.COLLECTION -> {
                                val isRelated = section == DetailSection.MORE_LIKE_THIS
                                val previews = if (isRelated) moreLikeThis else collection
                                val eyebrow = if (isRelated) strMoreLikeThis else strCollection
                                val cards = remember(previews, relatedWatchedStatus) {
                                    previews.map { item ->
                                        DetailCardItem(
                                            key = item.id,
                                            title = item.name,
                                            imageUrl = item.backdropUrl,
                                            isWatched = relatedWatchedStatus["${item.id}|${item.apiType}"] == true,
                                            value = item
                                        )
                                    }
                                }
                                val restoreType = if (isRelated) RestoreTarget.MORE_LIKE_THIS else RestoreTarget.COLLECTION
                                DetailCardRow(
                                    items = cards,
                                    accent = accent,
                                    upFocusRequester = tabRequester,
                                    restoreKey = when {
                                        pendingRestoreType != restoreType -> null
                                        isRelated -> pendingRestoreMoreLikeItemId
                                        else -> pendingRestoreCollectionItemId
                                    },
                                    restoreFocusToken = if (pendingRestoreType == restoreType) restoreFocusToken else 0,
                                    onRestoreFocusHandled = { clearPendingRestore() },
                                    onFocused = { item -> bandFocus.value = DetailBandFocus.Related(item, eyebrow) },
                                    onClick = { item ->
                                        if (isRelated) {
                                            markRestore(RestoreTarget.MORE_LIKE_THIS, moreLikeItemId = item.id)
                                        } else {
                                            markRestore(RestoreTarget.COLLECTION, collectionItemId = item.id)
                                        }
                                        onNavigateToDetail(item.id, item.apiType, null)
                                    },
                                    onLongPress = onPosterLongPress
                                )
                            }

                            DetailSection.RATINGS -> DetailRatingsGrid(
                                episodes = meta.videos,
                                ratings = visibleEpisodeImdbRatings,
                                isLoading = isEpisodeRatingsLoading,
                                error = episodeRatingsError,
                                upFocusRequester = tabRequester,
                                onEpisodeFocused = { bandFocus.value = DetailBandFocus.Episode(it, inSections = true) }
                            )

                            DetailSection.TRAILERS -> {
                                val fallbackTitle = stringResource(R.string.detail_tab_trailer)
                                val cards = remember(meta.trailers, fallbackTitle) {
                                    meta.trailers.mapNotNull { trailer ->
                                        val ytId = trailer.ytId?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                        DetailCardItem(
                                            key = ytId,
                                            title = trailerTitle(trailer, fallbackTitle),
                                            imageUrl = "https://img.youtube.com/vi/$ytId/hqdefault.jpg",
                                            isWatched = false,
                                            value = trailer
                                        )
                                    }
                                }
                                DetailCardRow(
                                    items = cards,
                                    accent = accent,
                                    upFocusRequester = tabRequester,
                                    restoreKey = selectedSharedTrailer?.ytId?.trim()?.takeIf { restoreSharedTrailerFocusToken > 0 },
                                    restoreFocusToken = restoreSharedTrailerFocusToken,
                                    onRestoreFocusHandled = onSharedTrailerFocusRestored,
                                    onFocused = { trailer -> bandFocus.value = DetailBandFocus.Trailer(trailer) },
                                    onClick = onSharedTrailerSelected
                                )
                            }

                            DetailSection.COMPANIES -> DetailLogoRow(
                                companies = companyItems,
                                upFocusRequester = tabRequester,
                                restoreCompanyId = if (companyRestoreToken > 0 && pendingRestoreType == RestoreTarget.COMPANY_OR_NETWORK) pendingRestoreCompanyId else null,
                                restoreFocusToken = if (pendingRestoreType == RestoreTarget.COMPANY_OR_NETWORK) restoreFocusToken else 0,
                                onRestoreFocusHandled = { clearPendingRestore() },
                                onFocused = focusTitle,
                                onClick = { item ->
                                    if (linksLimited) {
                                        onLimitedLinkClick()
                                    } else {
                                        item.company.tmdbId?.let { entityId ->
                                            markRestore(RestoreTarget.COMPANY_OR_NETWORK, companyId = entityId)
                                            val section = if (item.kind == TmdbEntityKind.NETWORK.routeValue) strNetwork else strStudios
                                            onNavigateToTmdbEntityBrowse(item.kind, entityId, item.company.name, meta.apiType, meta.name, section)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Scrolled-state overlays; like the list, they fade out under the trailer and the About sheet.
        DetailContextLine(
            title = meta.name,
            season = if (showSeasonTabs) selectedSeason else null,
            showSeason = { !sectionsFocused.value },
            alpha = { scrolledFraction() * contentAlpha.value },
            modifier = Modifier.padding(start = NuvioTheme.layout.tvSafeHorizontal, top = detailTokens.contextTop)
        )
        if (showEpisodesRow || showSections) {
            DetailBandBlock(
                focus = bandFocusReader,
                meta = meta,
                eyebrow = pageEyebrow,
                titleMetaParts = detailMetaParts(meta, showFullReleaseDate),
                showImdbRating = showStandardOverallRatings,
                geometry = bandGeometry,
                episodeProgressMap = episodeProgressMap,
                watchedEpisodes = watchedEpisodes,
                episodeRatings = visibleEpisodeImdbRatings,
                accent = accent,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = NuvioTheme.layout.tvSafeHorizontal)
                    .graphicsLayer { alpha = contentAlpha.value }
            )
        }

        DetailTrailerOverlay(meta = meta, visible = isTrailerPlaying, hideTitle = hideLogoDuringTrailer)

        seasonOptionsDialogSeason?.let { season ->
            val hasPreviousSeasons = remember(season, seasons) {
                seasons.any { it != 0 && it < season }
            }
            SeasonOptionsDialog(
                season = season,
                isFullyWatched = isSeasonFullyWatched(season),
                hasPreviousSeasons = hasPreviousSeasons,
                onDismiss = { seasonOptionsDialogSeason = null },
                onMarkSeasonWatched = {
                    onMarkSeasonWatched(season)
                    seasonOptionsDialogSeason = null
                },
                onMarkSeasonUnwatched = {
                    onMarkSeasonUnwatched(season)
                    seasonOptionsDialogSeason = null
                },
                onMarkPreviousSeasonsWatched = {
                    onMarkPreviousSeasonsWatched(season)
                    seasonOptionsDialogSeason = null
                }
            )
        }

        if (showHeroPlayOptionsDialog) {
            PlayManualOverrideDialog(
                title = meta.name,
                subtitle = nextToWatch?.displayText ?: stringResource(R.string.hero_play),
                onDismiss = { showHeroPlayOptionsDialog = false },
                showPlayManually = showManualPlayOption,
                onPlayManually = {
                    showHeroPlayOptionsDialog = false
                    heroPlayManualClick()
                },
                showStartFromBeginning = nextToWatch?.isResume == true,
                onStartFromBeginning = {
                    showHeroPlayOptionsDialog = false
                    heroPlayStartFromBeginningClick()
                }
            )
        }

        if (isSharedTrailerOverlayVisible) {
            SharedTrailerOverlay(
                title = stringResource(R.string.detail_tab_trailer).let { fallback ->
                    selectedSharedTrailer?.let { trailerTitle(it, fallback) } ?: fallback
                },
                trailerUrl = sharedTrailerUrl,
                trailerAudioUrl = sharedTrailerAudioUrl,
                isLoading = isSharedTrailerLoading,
                errorMessage = sharedTrailerErrorMessage,
                onDismiss = onDismissSharedTrailer,
                onRetry = onRetrySharedTrailer
            )
        }

        if (showAbout) {
            DetailAboutSheet(
                meta = meta,
                eyebrow = pageEyebrow,
                accent = accent,
                extras = aboutExtras.takeIf { it.metaId == meta.id } ?: DetailAboutExtras(),
                isTvShow = isTvShow,
                showRatings = showStandardOverallRatings,
                collectionName = collectionName,
                collection = collection,
                // People and genres push their screen over Detail; the sheet reopens on return.
                onPersonClick = { person ->
                    if (linksLimited) {
                        onLimitedLinkClick()
                    } else {
                        person.tmdbId?.let { id ->
                            val section = resources.getString(personChipSectionRes(person.role))
                            onNavigateToCastDetail(id, person.name, personPageDepartment(person.role), meta.name, section)
                        }
                    }
                },
                onGenreClick = { genre -> onNavigateToDiscoverGenre(meta.apiType, genre) },
                // OK on the collection: the sheet closes onto the band's Collection tab.
                onOpenCollection = if (sectionTabs.any { it.section == DetailSection.COLLECTION }) {
                    {
                        showAbout = false
                        activeSection = DetailSection.COLLECTION
                        coroutineScope.launch {
                            listState.animateScrollToItem(sectionsItemIndex, -bandGeometry.sectionsTopPx)
                            sectionTabRequesters.getValue(DetailSection.COLLECTION).requestFocusAfterFrames()
                        }
                    }
                } else {
                    null
                },
                onDismiss = { showAbout = false }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayManualOverrideDialog(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    showPlayManually: Boolean = true,
    onPlayManually: () -> Unit,
    showStartFromBeginning: Boolean = false,
    onStartFromBeginning: () -> Unit = {}
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle
    ) {
        if (showPlayManually) {
            PanelActionRow(
                label = stringResource(R.string.play_manually),
                onClick = onPlayManually,
                focusRequester = primaryFocusRequester
            )
        }

        if (showStartFromBeginning) {
            PanelActionRow(
                label = stringResource(R.string.cw_action_start_from_beginning),
                onClick = onStartFromBeginning,
                focusRequester = if (!showPlayManually) primaryFocusRequester else null
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryListPickerDialog(
    title: String,
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>,
    isPending: Boolean,
    error: String?,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.detail_lists_subtitle),
        width = 500.dp
    ) {
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB6B6)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(tabs, key = { it.key }) { tab ->
                val selected = membership[tab.key] == true
                PlayerPanelRow(
                    title = tab.title,
                    selected = selected,
                    onClick = { if (!isPending) onToggle(tab.key) },
                    focusRequester = if (tab.key == tabs.firstOrNull()?.key) primaryFocusRequester else null
                )
            }
        }

        PanelActionRow(
            label = if (isPending) stringResource(R.string.action_saving) else stringResource(R.string.action_save),
            onClick = onSave,
            enabled = !isPending
        )
    }
}

/**
 * The hero and both bands (episodes, sections) own their scroll position (explicit scrolls on
 * focus), so focus inside them does not scroll the page to the focused child: the request stops
 * here and the page is only asked for this node's zero-size origin. Same behaviour as the
 * deprecated no-op BringIntoViewResponder it replaces (foundation 1.11.2's responder node
 * forwarded calculateRectForParent = Rect.Zero under these attached/placed checks).
 */
private fun Modifier.noPageScrollOnFocus(): Modifier = this then NoPageScrollElement

private data object NoPageScrollElement : ModifierNodeElement<NoPageScrollNode>() {
    override fun create() = NoPageScrollNode()
    override fun update(node: NoPageScrollNode) = Unit
}

private class NoPageScrollNode : Modifier.Node(), BringIntoViewModifierNode, LayoutAwareModifierNode {
    private var placed = false

    override val shouldAutoInvalidate: Boolean get() = false

    override fun onPlaced(coordinates: LayoutCoordinates) {
        placed = true
    }

    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        // DelegatableNode.bringIntoView extension: asks the parent (the page LazyColumn), not this.
        // A null rect makes the parent fall back to this node's full bounds, as the old node did.
        bringIntoView {
            if (isAttached && placed && childCoordinates.isAttached && boundsProvider() != null) Rect.Zero else null
        }
    }
}
