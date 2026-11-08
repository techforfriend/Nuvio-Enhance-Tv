package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import android.util.Log
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.core.cloudstream.CloudStreamStreamSource
import com.nuvio.tv.ui.screens.cloudstream.CloudStreamHomePlayTarget
import com.nuvio.tv.ui.screens.cloudstream.CloudStreamHomeViewModel
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsPickerPanel
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.components.errorStateText
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.StartupAuthNotice
import kotlinx.coroutines.delay

private const val HOME_STABLE_GATE_TIMEOUT_MS = 5_000L

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    cloudStreamHomeViewModel: CloudStreamHomeViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToCloudStreamStream: (CloudStreamHomePlayTarget) -> Unit = {},
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit = { item ->
        onNavigateToDetail(
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId
            },
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentType
                is ContinueWatchingItem.NextUp -> item.info.contentType
            },
            ""
        )
    },
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onNavigateToAddons: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val modernPresentation by viewModel.modernHomePresentation.collectAsStateWithLifecycle()
    val initialCwResolved by viewModel.initialCwResolved.collectAsStateWithLifecycle()
    val effectiveAutoplayEnabled by viewModel.effectiveAutoplayEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )
    val hasCatalogContent = uiState.catalogRows.any { it.items.isNotEmpty() }
    val hasCollectionContent = uiState.homeRows.any { it is HomeRow.CollectionRow }
    val hasHeroContent = uiState.heroItems.isNotEmpty()
    // CloudStream section: the selected provider's main-page sections, as Marquee
    // rows appended after the addon rows. Empty while no CS3 plugin is runnable.
    val cs3State by cloudStreamHomeViewModel.uiState.collectAsStateWithLifecycle()
    val cs3Rows: StableList<HeroCarouselRow> = remember(
        cs3State.sections,
        cs3State.selectedProviderId,
        cs3State.selectedProvider?.metadata?.name,
        modernPresentation.rows.list.size
    ) {
        val providerId = cs3State.selectedProviderId
        val providerName = cs3State.selectedProvider?.metadata?.name
        if (providerId == null || providerName == null) {
            StableList()
        } else {
            cloudStreamHomeRows(
                sections = cs3State.sections,
                providerId = providerId,
                providerName = providerName,
                baseRowIndex = modernPresentation.rows.list.size,
                onProviderClick = { cloudStreamHomeViewModel.showProviderPicker() }
            )
        }
    }
    val hasCloudStreamContent = cs3State.sections.isNotEmpty()
    val modernPresentationReady =
        modernPresentation.rows.list.isNotEmpty() ||
            hasCloudStreamContent ||
            (uiState.heroSectionEnabled && hasHeroContent && !hasCatalogContent && !hasCollectionContent)
    var showHomeContentWithAnimation by rememberSaveable { mutableStateOf(false) }
    var hasShownInitialHomeContent by rememberSaveable { mutableStateOf(false) }
    // Once we've shown stable home content, never go back to loading gate.
    var homeStableGateReleased by rememberSaveable { mutableStateOf(false) }
    // Track that catalog loading has started at least once (isLoading went true→false).
    var catalogLoadingStarted by rememberSaveable { mutableStateOf(false) }

    // Notify ViewModel of locale changes after activity recreation
    LaunchedEffect(Unit) {
        viewModel.notifyLocaleChanged()
    }

    // T27: CW stream prefetches start only while Home is the resumed destination.
    LifecycleResumeEffect(viewModel) {
        viewModel.setHomeOnTop(true)
        onPauseOrDispose { viewModel.setHomeOnTop(false) }
    }

    val movieWatchedStatus = uiState.movieWatchedStatus
    // The lambda reads movieWatchedStatus through rememberUpdatedState so the
    // remembered instance always sees the latest map. (With strong skipping the
    // identity-stability claim itself is moot — lambdas memoize — but the
    // updated-state indirection is still what keeps this lambda correct.)
    val latestMovieWatchedStatus = androidx.compose.runtime.rememberUpdatedState(movieWatchedStatus)
    val isCatalogItemWatched: (MetaPreview) -> Boolean = remember {
        { item: MetaPreview -> latestMovieWatchedStatus.value[homeItemStatusKey(item.id, item.apiType)] == true }
    }
    val onCatalogItemLongPress: (MetaPreview, String) -> Unit = remember(viewModel) {
        { item, addonBaseUrl -> viewModel.posterOptions.show(item, addonBaseUrl) }
    }

    val onNavigateToDetailStable = remember(onNavigateToDetail) { onNavigateToDetail }
    val onContinueWatchingClickStable = remember(onContinueWatchingClick) { onContinueWatchingClick }
    val onContinueWatchingStartFromBeginningStable = remember(onContinueWatchingStartFromBeginning) { onContinueWatchingStartFromBeginning }
    val onContinueWatchingPlayManuallyStable = remember(onContinueWatchingPlayManually) { onContinueWatchingPlayManually }
    val onNavigateToFolderDetailStable = remember(onNavigateToFolderDetail) { onNavigateToFolderDetail }
    LaunchedEffect(
        uiState.isLoading,
        hasCatalogContent,
        hasCollectionContent,
        hasHeroContent,
        initialCwResolved,
        modernPresentationReady
    ) {
        // Track that addons are known (even if isLoading flipped too fast to catch).
        if (uiState.installedAddonsCount > 0) {
            catalogLoadingStarted = true
        }
        // Wait until catalog loading has completed with content AND the CW
        // pipeline has completed its first emission.
        if (!homeStableGateReleased &&
            catalogLoadingStarted &&
            !uiState.isLoading &&
            initialCwResolved &&
            modernPresentationReady &&
            // When addons are installed, require at least one catalog row —
            // unless the CloudStream section already has content.
            (hasCatalogContent || hasCloudStreamContent || uiState.installedAddonsCount == 0)
        ) {
            Log.d("HomeGate", "RELEASE: catalogs=$hasCatalogContent cwResolved=$initialCwResolved cwItems=${uiState.continueWatchingItems.size} addons=${uiState.installedAddonsCount}")
            homeStableGateReleased = true
        }
    }

    LaunchedEffect(Unit) {
        // Safety timeout — if catalogs and CW haven't loaded within this
        // window, show whatever is available.  Covers edge cases like
        // clean cache (addons loading from remote sync) and users with
        // no addons at all.
        delay(HOME_STABLE_GATE_TIMEOUT_MS)
        if (!homeStableGateReleased) {
            Log.d("HomeGate", "RELEASE timeout: isLoading=${uiState.isLoading} cwResolved=$initialCwResolved catalogs=$hasCatalogContent cwItems=${uiState.continueWatchingItems.size}")
            homeStableGateReleased = true
        }
    }

    val noAddonsError = stringResource(R.string.home_error_no_addons)
    val noCatalogAddonsError = stringResource(R.string.home_error_no_catalog_addons)
    val hasAnyContent = uiState.catalogRows.isNotEmpty() ||
        (uiState.continueWatchingEnabled && uiState.continueWatchingItems.isNotEmpty()) ||
        uiState.heroItems.isNotEmpty() ||
        hasCollectionContent ||
        hasCloudStreamContent
    val showStartupLoader = when {
        !uiState.layoutPreferencesReady -> true
        uiState.isLoading && !hasAnyContent -> true
        uiState.error == noAddonsError && uiState.catalogRows.isEmpty() -> !homeStableGateReleased
        uiState.error == noCatalogAddonsError && uiState.catalogRows.isEmpty() && !hasCollectionContent && !hasHeroContent -> !homeStableGateReleased
        uiState.error != null && uiState.catalogRows.isEmpty() -> false
        !uiState.isLoading && !hasAnyContent -> !homeStableGateReleased
        else -> !homeStableGateReleased || !modernPresentationReady || !showHomeContentWithAnimation
    }

    // Reports the home screen as fully drawn once it leaves the loading state so startup timing is measurable and post-launch work can be deferred.
    ReportDrawnWhen { !showStartupLoader }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            // Nothing drawn here: the startup loader below covers these states.
            !uiState.layoutPreferencesReady -> {}

            uiState.isLoading && !hasAnyContent -> {}

            uiState.error == noAddonsError && uiState.catalogRows.isEmpty() -> {
                if (homeStableGateReleased) {
                    MarqueeHomeMessage(
                        title = noAddonsError,
                        body = AnnotatedString(stringResource(R.string.home_empty_no_addons_body)),
                        action = HomeMessageAction.OPEN_ADDONS,
                        onAction = onNavigateToAddons
                    )
                }
            }

            uiState.error == noCatalogAddonsError && uiState.catalogRows.isEmpty() && !hasCollectionContent && !hasHeroContent -> {
                if (homeStableGateReleased) {
                    MarqueeHomeMessage(
                        title = noCatalogAddonsError,
                        body = AnnotatedString(stringResource(R.string.home_empty_no_catalog_addons_body)),
                        action = HomeMessageAction.OPEN_ADDONS,
                        onAction = onNavigateToAddons
                    )
                }
            }

            uiState.error != null && uiState.catalogRows.isEmpty() -> {
                MarqueeHomeMessage(
                    title = stringResource(R.string.home_error_title),
                    body = errorStateText(uiState.error.orEmpty()),
                    action = HomeMessageAction.RETRY,
                    onAction = { viewModel.onEvent(HomeEvent.OnRetry) }
                )
            }

            !uiState.isLoading && !hasAnyContent -> {
                // Don't show "no catalogs" until the stable gate has released:
                // addons may still be loading from remote after a cache clear.
                if (homeStableGateReleased) {
                    MarqueeHomeMessage(
                        title = stringResource(R.string.home_empty_no_catalogs_title),
                        body = AnnotatedString(stringResource(R.string.home_empty_no_catalogs_body)),
                        action = HomeMessageAction.RETRY,
                        onAction = { viewModel.onEvent(HomeEvent.OnRetry) }
                    )
                }
            }

            else -> {
                // On first launch, wait for stable content before revealing home.
                // Once released, never go back to loading (homeStableGateReleased is rememberSaveable).
                if (homeStableGateReleased && modernPresentationReady) {
                    // Flip showHomeContentWithAnimation on the next frame so
                    // AnimatedVisibility can run its enter transition.
                    LaunchedEffect(Unit) {
                        if (!showHomeContentWithAnimation) {
                            kotlinx.coroutines.yield()
                            showHomeContentWithAnimation = true
                        }
                    }
                    LaunchedEffect(showHomeContentWithAnimation) {
                        if (showHomeContentWithAnimation) {
                            hasShownInitialHomeContent = true
                        }
                    }
                    AnimatedVisibility(
                        visible = showHomeContentWithAnimation,
                        enter = if (hasShownInitialHomeContent) {
                            EnterTransition.None
                        } else {
                            fadeIn(animationSpec = tween(320)) +
                                slideInVertically(
                                    initialOffsetY = { it / 24 },
                                    animationSpec = tween(320)
                                )
                        }
                    ) {
                        ModernHomeRoute(
                            viewModel = viewModel,
                            uiState = uiState,
                            cloudStreamRows = cs3Rows,
                            onCloudStreamCardClick = { itemId, _ ->
                                val rest = itemId.removePrefix(
                                    CloudStreamStreamSource.CS3_VIDEO_ID_PREFIX
                                )
                                val providerId = rest.substringBefore('|')
                                val data = rest.substringAfter('|', "")
                                cs3State.sections
                                    .flatMap { it.items }
                                    .firstOrNull {
                                        it.providerId == providerId && it.data == data
                                    }
                                    ?.let { cloudStreamHomeViewModel.onCardClick(it) }
                            },
                            onNavigateToDetail = onNavigateToDetailStable,
                            onContinueWatchingClick = onContinueWatchingClickStable,
                            onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginningStable,
                            onContinueWatchingPlayManually = onContinueWatchingPlayManuallyStable,
                            showContinueWatchingManualPlayOption = effectiveAutoplayEnabled,
                            onNavigateToFolderDetail = onNavigateToFolderDetailStable,
                            isCatalogItemWatched = isCatalogItemWatched,
                            onCatalogItemLongPress = onCatalogItemLongPress
                        )
                    }
                }
            }
        }

        if (showStartupLoader) {
            MarqueeHomeSkeleton(
                landscape = homeSkeletonLandscape(uiState, continueWatchingPending = !initialCwResolved)
            )
        }

        val startupAuthNotice = uiState.startupAuthNotice
        if (startupAuthNotice != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = NuvioTheme.spacing.xl)
                    .background(
                        color = Color(0xFF5A1C1C),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = when (startupAuthNotice) {
                        StartupAuthNotice.NUVIO -> stringResource(R.string.auth_notice_nuvio_logged_out)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
        }
    }

    val posterOptionsState by viewModel.posterOptions.state.collectAsStateWithLifecycle()
    PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = onNavigateToDetail
    )

    // CloudStream: provider picker (the pill in the first CS3 row's title) and
    // the episode picker for series cards.
    val cs3ShowProviderPicker by cloudStreamHomeViewModel.showProviderPicker.collectAsStateWithLifecycle()
    if (cs3ShowProviderPicker) {
        SettingsPickerPanel(
            title = stringResource(R.string.cs3_home_provider_title),
            options = cs3State.providers.map {
                SettingsPickerOption(it.metadata.id.value, it.metadata.name)
            },
            selectedValue = cs3State.selectedProviderId ?: "",
            onSelect = { cloudStreamHomeViewModel.selectProvider(it) },
            onDismiss = { cloudStreamHomeViewModel.dismissProviderPicker() }
        )
    }
    val cs3PendingEpisodes by cloudStreamHomeViewModel.pendingEpisodes.collectAsStateWithLifecycle()
    cs3PendingEpisodes?.let { pick ->
        SettingsPickerPanel(
            title = pick.item.name,
            options = pick.episodes.mapIndexed { index, episode ->
                SettingsPickerOption(index, cloudStreamEpisodeLabel(episode))
            },
            selectedValue = -1,
            onSelect = { index ->
                cloudStreamHomeViewModel.onEpisodeSelected(pick.episodes[index])
            },
            onDismiss = { cloudStreamHomeViewModel.dismissEpisodePicker() }
        )
    }

    // A resolved card click navigates to the Stream screen once.
    val cs3PendingPlay by cloudStreamHomeViewModel.pendingPlay.collectAsStateWithLifecycle()
    LaunchedEffect(cs3PendingPlay) {
        val target = cs3PendingPlay ?: return@LaunchedEffect
        onNavigateToCloudStreamStream(target)
        cloudStreamHomeViewModel.consumePendingPlay()
    }

    // A card tap whose load() failed: show the reason once as a toast, so the
    // tap doesn't look ignored. Same LocalMarqueeToast the manager screen uses.
    val cs3TapError by cloudStreamHomeViewModel.tapError.collectAsStateWithLifecycle()
    val toast = LocalMarqueeToast.current
    LaunchedEffect(cs3TapError) {
        val message = cs3TapError ?: return@LaunchedEffect
        toast?.show(message)
        cloudStreamHomeViewModel.consumeTapError()
    }
}

@Composable
private fun ModernHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    cloudStreamRows: StableList<HeroCarouselRow> = StableList(),
    onCloudStreamCardClick: (String, String) -> Unit = { _, _ -> },
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    val modernPresentation by viewModel.modernHomePresentation.collectAsStateWithLifecycle()
    val enrichingItemId by viewModel.enrichingItemId.collectAsStateWithLifecycle()
    val enrichedPreviews by viewModel.enrichedPreviews.collectAsStateWithLifecycle()
    val failedEnrichmentIds by viewModel.failedEnrichmentIds.collectAsStateWithLifecycle()
    val requestTrailerPreview = remember(viewModel) {
        { itemId: String, title: String, releaseInfo: String?, apiType: String ->
            viewModel.requestTrailerPreview(itemId, title, releaseInfo, apiType)
        }
    }
    val loadMoreCatalog = remember(viewModel) {
        { catalogId: String, addonId: String, type: String ->
            viewModel.onEvent(HomeEvent.OnLoadMoreCatalog(catalogId, addonId, type))
        }
    }
    val removeContinueWatching = remember(viewModel) {
        { contentId: String, season: Int?, episode: Int?, isNextUp: Boolean ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        }
    }
    val saveModernFocusState = remember(viewModel) {
        { vi: Int, vo: Int, rk: String?, ikm: Map<String, String>, m: Map<String, Int>, ri: Int, ii: Int ->
            viewModel.saveFocusState(vi, vo, rk, ikm, m, ri, ii)
        }
    }
    val preloadAdjacentItem = remember(viewModel) {
        { item: MetaPreview ->
            viewModel.preloadAdjacentItem(item)
        }
    }
    MarqueeHomeContent(
        uiState = uiState,
        modernPresentation = modernPresentation,
        focusState = focusState,
        scrollToTopTrigger = scrollToTopTrigger,
        enrichingItemId = enrichingItemId,
        enrichedPreviews = enrichedPreviews,
        failedEnrichmentIds = failedEnrichmentIds,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
        cloudStreamRows = cloudStreamRows,
        onCloudStreamCardClick = onCloudStreamCardClick,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onRequestTrailerPreview = requestTrailerPreview,
        onLoadMoreCatalog = loadMoreCatalog,
        onRemoveContinueWatching = removeContinueWatching,
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onItemFocus = remember(viewModel) {
            { item -> viewModel.onItemFocus(item) }
        },
        onPreloadAdjacentItem = preloadAdjacentItem,
        onSaveFocusState = saveModernFocusState,
        onRequestLazyCatalogLoad = remember(viewModel) {
            { catalogKey: String -> viewModel.requestLazyCatalogLoad(catalogKey) }
        },
        // S4a-3b: focus-dwell stream prefetch for CW cards. Gated on the CW
        // row KEY, not the isCw flag -- MarqueeHomeRows sets that flag for the
        // Upcoming row too, whose cards are unaired and must not be scraped.
        onRowItemFocusedCallback = remember(viewModel) {
            { rowKey: String, index: Int, _: Boolean ->
                if (rowKey == MODERN_CONTINUE_WATCHING_ROW_KEY) {
                    viewModel.onContinueWatchingItemFocused(index)
                }
            }
        }
    )
}
