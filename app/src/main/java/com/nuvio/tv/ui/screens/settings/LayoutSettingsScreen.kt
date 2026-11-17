package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.streams.STREAM_BADGE_IMPORT_LIMIT
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.DetailImdbRatingsVisibility
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import com.nuvio.tv.domain.model.TrailerAutoplayMode
import com.nuvio.tv.domain.model.TrailerPreviewDelay
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.screens.addon.QrCodeOverlay

/** Stage 8 chips (Q10), in order. */
private enum class LayoutSection { HOME, DETAIL, STREAMS, CONTINUE_WATCHING }

/** The side-panel pickers this pane opens. */
private enum class LayoutPicker { DISCOVER, EPISODE_RATINGS, CW_SORT }

private const val GROUP_HOME = "layout_home"
private const val GROUP_DETAIL = "layout_detail"
private const val GROUP_CW = "layout_cw"

/** Detail page trailer delay range (as built). */
private const val TRAILER_DELAY_MIN_S = 3
private const val TRAILER_DELAY_MAX_S = 15

/**
 * Spec 31, Layout pane (sandbox u11-settings-marquee v6, stages `layout-home`, `layout-streams`):
 * four chips over Home, Detail page, Streams and Continue watching. Home, Detail page and Continue
 * watching keep their less used rows in an Advanced options group; Streams is short enough to
 * have none. Rows wait for every stored value ([LayoutSettingsUiState.loaded]); the shell's entry
 * requester lands on the shown section's first row.
 */
@Composable
fun LayoutSettingsContent(
    viewModel: LayoutSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val badges by viewModel.streamBadgeUiState.collectAsStateWithLifecycle()
    val toast = LocalMarqueeToast.current
    val groups = settingsAdvancedGroups()
    var section by rememberSaveable { mutableStateOf(LayoutSection.HOME) }
    var picker by rememberSaveable { mutableStateOf<LayoutPicker?>(null) }
    val listState = rememberLazyListState()
    // Each section opens at its top (the sections share one list).
    LaunchedEffect(section) { listState.scrollToItem(0) }

    LaunchedEffect(badges.serverError) {
        val error = badges.serverError ?: return@LaunchedEffect
        toast?.show(error)
        viewModel.clearStreamBadgeError()
    }

    val onEvent = viewModel::onEvent
    SettingsPane(
        title = stringResource(R.string.settings_layout),
        subtitle = stringResource(R.string.layout_pane_subtitle),
        listState = listState,
        chips = if (uiState.loaded) {
            {
                SettingsSectionChips(
                    items = LayoutSection.entries,
                    selected = section,
                    label = { layoutSectionLabel(it) },
                    onSelect = { section = it }
                )
            }
        } else {
            null
        }
    ) {
        if (!uiState.loaded) return@SettingsPane
        when (section) {
            LayoutSection.HOME -> homeItems(
                state = uiState,
                onEvent = onEvent,
                onOpenDiscover = { picker = LayoutPicker.DISCOVER },
                groupOpen = groups.isOpen(GROUP_HOME),
                onToggleGroup = { groups.toggle(GROUP_HOME) },
                entryFocus = initialFocusRequester
            )
            LayoutSection.DETAIL -> detailItems(
                state = uiState,
                onEvent = onEvent,
                onOpenEpisodeRatings = { picker = LayoutPicker.EPISODE_RATINGS },
                groupOpen = groups.isOpen(GROUP_DETAIL),
                onToggleGroup = { groups.toggle(GROUP_DETAIL) },
                entryFocus = initialFocusRequester
            )
            LayoutSection.STREAMS -> streamsItems(
                badges = badges,
                onUseAddonFormatting = viewModel::setUseAddonFormatting,
                onShowSizeBadges = viewModel::setShowFileSizeBadges,
                onOpenBadgeUrls = viewModel::startStreamBadgeQrMode,
                entryFocus = initialFocusRequester
            )
            LayoutSection.CONTINUE_WATCHING -> continueWatchingItems(
                state = uiState,
                onEvent = onEvent,
                onOpenSort = { picker = LayoutPicker.CW_SORT },
                groupOpen = groups.isOpen(GROUP_CW),
                onToggleGroup = { groups.toggle(GROUP_CW) },
                entryFocus = initialFocusRequester
            )
        }
    }

    if (uiState.loaded) {
        when (picker) {
            LayoutPicker.DISCOVER -> DiscoverPicker(
                selected = uiState.discoverLocation,
                onSelect = { onEvent(LayoutSettingsEvent.SetDiscoverLocation(it)) },
                onDismiss = { picker = null }
            )
            LayoutPicker.EPISODE_RATINGS -> EpisodeRatingsPicker(
                selected = uiState.detailImdbRatingsVisibility,
                onSelect = { onEvent(LayoutSettingsEvent.SetDetailImdbRatingsVisibility(it)) },
                onDismiss = { picker = null }
            )
            LayoutPicker.CW_SORT -> CwSortPicker(
                selected = uiState.continueWatchingSortMode,
                onSelect = { onEvent(LayoutSettingsEvent.SetContinueWatchingSortMode(it)) },
                onDismiss = { picker = null }
            )
            null -> Unit
        }
    }

    if (badges.isQrModeActive) {
        QrCodeOverlay(
            qrBitmap = badges.qrCodeBitmap,
            serverUrl = badges.serverUrl,
            instruction = stringResource(R.string.stream_badge_qr_instruction),
            onClose = viewModel::stopStreamBadgeQrMode,
            qrSize = 168.dp
        )
    }
}

private fun trailerModeLabel(mode: TrailerAutoplayMode): Int = when (mode) {
    TrailerAutoplayMode.OFF -> R.string.layout_trailer_mode_off
    TrailerAutoplayMode.HERO -> R.string.layout_trailer_mode_hero
    TrailerAutoplayMode.CARD -> R.string.layout_trailer_mode_card
}

@Composable
private fun layoutSectionLabel(section: LayoutSection): String = stringResource(
    when (section) {
        LayoutSection.HOME -> R.string.layout_chip_home
        LayoutSection.DETAIL -> R.string.layout_chip_detail
        LayoutSection.STREAMS -> R.string.layout_section_streams
        LayoutSection.CONTINUE_WATCHING -> R.string.layout_chip_continue_watching
    }
)

/** Stage `layout-home`: Card shape, Discover, Ratings on Home, Trailer (Spec 96); three rows in the group. */
private fun LazyListScope.homeItems(
    state: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit,
    onOpenDiscover: () -> Unit,
    groupOpen: Boolean,
    onToggleGroup: () -> Unit,
    entryFocus: FocusRequester?
) {
    item(key = "layout_card_shape") {
        SettingsSegmentRow(
            title = stringResource(R.string.layout_landscape_posters),
            subtitle = stringResource(R.string.layout_card_shape_sub),
            options = listOf(
                stringResource(R.string.layout_card_shape_addon),
                stringResource(R.string.layout_card_shape_landscape)
            ),
            selectedIndex = if (state.modernLandscapePostersEnabled) 1 else 0,
            onSelect = { onEvent(LayoutSettingsEvent.SetModernLandscapePostersEnabled(it == 1)) },
            modifier = Modifier.optionalFocusRequester(entryFocus)
        )
    }
    item(key = "layout_discover") {
        SettingsValueRow(
            title = stringResource(R.string.discover_title),
            subtitle = stringResource(R.string.layout_discover_sub),
            value = discoverLocationLabel(state.discoverLocation),
            onClick = onOpenDiscover
        )
    }
    item(key = "layout_home_ratings") {
        SettingsSwitchRow(
            title = stringResource(R.string.layout_home_ratings),
            subtitle = stringResource(R.string.layout_home_ratings_sub),
            checked = state.homeImdbRatingsVisibility.showRatings,
            onCheckedChange = { show ->
                onEvent(
                    LayoutSettingsEvent.SetHomeImdbRatingsVisibility(
                        if (show) HomeImdbRatingsVisibility.SHOW_ALL else HomeImdbRatingsVisibility.HIDE_ALL
                    )
                )
            }
        )
    }
    // Spec 96 D7/D12/D14 (sandbox spec-96-trailer-preview v4): one three-way choice, then the In the card delay.
    item(key = "layout_trailer_section") {
        SettingsSectionLabel(text = stringResource(R.string.layout_trailer_section))
    }
    item(key = "layout_trailer_mode") {
        val mode = state.trailerAutoplayMode
        val delay = state.trailerCardPreviewDelaySeconds
        SettingsSegmentRow(
            title = stringResource(R.string.layout_hero_autoplay),
            subtitle = when (mode) {
                TrailerAutoplayMode.OFF -> stringResource(R.string.layout_trailer_mode_off_sub)
                TrailerAutoplayMode.HERO -> stringResource(R.string.layout_trailer_mode_hero_sub)
                TrailerAutoplayMode.CARD -> stringResource(R.string.layout_trailer_mode_card_sub, delay, 2 * delay)
            },
            // Built from the enum, so the segment order cannot drift from the index mapping.
            options = TrailerAutoplayMode.entries.map { stringResource(trailerModeLabel(it)) },
            selectedIndex = mode.ordinal,
            onSelect = { onEvent(LayoutSettingsEvent.SetTrailerAutoplayMode(TrailerAutoplayMode.entries[it])) }
        )
    }
    item(key = "layout_trailer_preview_delay") {
        // Dimmed outside In the card, value kept (as the Detail page's Trailer Delay).
        val card = state.trailerAutoplayMode == TrailerAutoplayMode.CARD
        SettingsStepperRow(
            title = stringResource(R.string.layout_trailer_preview_delay),
            subtitle = stringResource(
                if (card) R.string.layout_trailer_preview_delay_sub else R.string.layout_needs_trailer_card
            ),
            value = state.trailerCardPreviewDelaySeconds,
            min = TrailerPreviewDelay.MIN_S,
            max = TrailerPreviewDelay.MAX_S,
            step = 1,
            valueText = stringResource(R.string.assessment_value_seconds, state.trailerCardPreviewDelaySeconds),
            onValueChange = { onEvent(LayoutSettingsEvent.SetTrailerCardPreviewDelaySeconds(it)) },
            enabled = card
        )
    }
    settingsAdvancedGroup(key = GROUP_HOME, count = 3, open = groupOpen, onToggle = onToggleGroup) {
        item(key = "layout_hero_muted") {
            // Governs only the hero trailer: the card is always muted, the large panel has sound (D11, D15).
            val hero = state.trailerAutoplayMode == TrailerAutoplayMode.HERO
            SettingsSwitchRow(
                title = stringResource(R.string.layout_trailer_muted),
                subtitle = stringResource(
                    if (hero) R.string.layout_trailer_muted_sub_preview else R.string.layout_needs_trailer_hero
                ),
                checked = state.focusedPosterBackdropTrailerMuted,
                onCheckedChange = { onEvent(LayoutSettingsEvent.SetFocusedPosterBackdropTrailerMuted(it)) },
                enabled = hero
            )
        }
        item(key = "layout_catalog_type") {
            SettingsSwitchRow(
                title = stringResource(R.string.layout_catalog_type),
                subtitle = stringResource(R.string.layout_catalog_type_sub),
                checked = state.catalogTypeSuffixEnabled,
                onCheckedChange = { onEvent(LayoutSettingsEvent.SetCatalogTypeSuffixEnabled(it)) }
            )
        }
        item(key = "layout_hide_unreleased") {
            SettingsSwitchRow(
                title = stringResource(R.string.layout_hide_unreleased),
                subtitle = stringResource(R.string.layout_hide_unreleased_sub),
                checked = state.hideUnreleasedContent,
                onCheckedChange = { onEvent(LayoutSettingsEvent.SetHideUnreleasedContent(it)) }
            )
        }
    }
}

/** Detail page chip (rows as built): blur, episode ratings and trailer rows first; three in the group. */
private fun LazyListScope.detailItems(
    state: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit,
    onOpenEpisodeRatings: () -> Unit,
    groupOpen: Boolean,
    onToggleGroup: () -> Unit,
    entryFocus: FocusRequester?
) {
    item(key = "layout_blur_unwatched") {
        SettingsSwitchRow(
            title = stringResource(R.string.layout_blur_unwatched),
            subtitle = stringResource(R.string.layout_blur_unwatched_sub),
            checked = state.blurUnwatchedEpisodes,
            onCheckedChange = { onEvent(LayoutSettingsEvent.SetBlurUnwatchedEpisodes(it)) },
            modifier = Modifier.optionalFocusRequester(entryFocus)
        )
    }
    item(key = "layout_episode_ratings") {
        SettingsValueRow(
            title = stringResource(R.string.layout_episode_ratings),
            subtitle = stringResource(R.string.layout_episode_ratings_sub),
            value = episodeRatingsLabel(state.detailImdbRatingsVisibility),
            onClick = onOpenEpisodeRatings
        )
    }
    item(key = "layout_detail_autoplay") {
        SettingsSwitchRow(
            title = stringResource(R.string.audio_autoplay_trailers),
            subtitle = stringResource(R.string.audio_autoplay_trailers_sub),
            checked = state.detailPageTrailerAutoplayEnabled,
            onCheckedChange = { onEvent(LayoutSettingsEvent.SetDetailPageTrailerAutoplayEnabled(it)) }
        )
    }
    item(key = "layout_detail_delay") {
        val autoplay = state.detailPageTrailerAutoplayEnabled
        SettingsStepperRow(
            title = stringResource(R.string.audio_trailer_delay),
            subtitle = if (autoplay) null else stringResource(R.string.layout_needs_detail_autoplay),
            value = state.detailPageTrailerAutoplayDelaySeconds,
            min = TRAILER_DELAY_MIN_S,
            max = TRAILER_DELAY_MAX_S,
            step = 1,
            valueText = stringResource(R.string.assessment_value_seconds, state.detailPageTrailerAutoplayDelaySeconds),
            onValueChange = { onEvent(LayoutSettingsEvent.SetDetailPageTrailerAutoplayDelaySeconds(it)) },
            enabled = autoplay
        )
    }
    item(key = "layout_trailer_button") {
        SettingsSwitchRow(
            title = stringResource(R.string.layout_trailer_button),
            subtitle = stringResource(R.string.layout_trailer_button_sub),
            checked = state.detailPageTrailerButtonEnabled,
            onCheckedChange = { onEvent(LayoutSettingsEvent.SetDetailPageTrailerButtonEnabled(it)) }
        )
    }
    settingsAdvancedGroup(key = GROUP_DETAIL, count = 3, open = groupOpen, onToggle = onToggleGroup) {
        item(key = "layout_imdb_trailers") {
            SettingsSwitchRow(
                title = stringResource(R.string.layout_imdb_trailers),
                subtitle = stringResource(R.string.layout_imdb_trailers_sub),
                checked = state.imdbTrailersEnabled,
                onCheckedChange = { onEvent(LayoutSettingsEvent.SetImdbTrailersEnabled(it)) }
            )
        }
        item(key = "layout_prefer_external_meta") {
            SettingsSwitchRow(
                title = stringResource(R.string.layout_prefer_external_meta),
                subtitle = stringResource(R.string.layout_prefer_external_meta_sub),
                checked = state.preferExternalMetaAddonDetail,
                onCheckedChange = { onEvent(LayoutSettingsEvent.SetPreferExternalMetaAddonDetail(it)) }
            )
        }
        item(key = "layout_full_release_date") {
            SettingsSwitchRow(
                title = stringResource(R.string.layout_show_full_release_date),
                subtitle = stringResource(R.string.layout_show_full_release_date_sub),
                checked = state.showFullReleaseDate,
                onCheckedChange = { onEvent(LayoutSettingsEvent.SetShowFullReleaseDate(it)) }
            )
        }
    }
}

/** Stage `layout-streams`: addon formatting first; Size badges hides while it is on (no group). */
private fun LazyListScope.streamsItems(
    badges: StreamBadgeSettingsUiState,
    onUseAddonFormatting: (Boolean) -> Unit,
    onShowSizeBadges: (Boolean) -> Unit,
    onOpenBadgeUrls: () -> Unit,
    entryFocus: FocusRequester?
) {
    item(key = "layout_addon_formatting") {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_stream_addon_formatting_title),
            subtitle = stringResource(R.string.settings_stream_addon_formatting_description),
            checked = badges.useAddonFormatting,
            onCheckedChange = onUseAddonFormatting,
            modifier = Modifier.optionalFocusRequester(entryFocus)
        )
    }
    if (!badges.useAddonFormatting) {
        item(key = "layout_size_badges") {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_stream_size_badges_title),
                subtitle = stringResource(R.string.layout_size_badges_sub),
                checked = badges.showFileSizeBadges,
                onCheckedChange = onShowSizeBadges
            )
        }
    }
    item(key = "layout_badge_urls") {
        val rules = badges.rules.normalized()
        SettingsValueRow(
            title = stringResource(R.string.settings_stream_badge_urls_title),
            subtitle = stringResource(R.string.layout_badge_urls_sub),
            value = if (rules.hasImport) {
                stringResource(
                    R.string.layout_badge_urls_value,
                    rules.imports.size,
                    STREAM_BADGE_IMPORT_LIMIT,
                    rules.enabledFilterCount
                )
            } else {
                stringResource(R.string.action_none)
            },
            onClick = onOpenBadgeUrls
        )
    }
}

/**
 * Continue watching chip (rows as built). Rows that only shape the Home row dim while it is off;
 * Up next from furthest episode stays live (Detail reads it too).
 */
private fun LazyListScope.continueWatchingItems(
    state: LayoutSettingsUiState,
    onEvent: (LayoutSettingsEvent) -> Unit,
    onOpenSort: () -> Unit,
    groupOpen: Boolean,
    onToggleGroup: () -> Unit,
    entryFocus: FocusRequester?
) {
    val cwOn = state.continueWatchingEnabled
    item(key = "layout_cw_enabled") {
        SettingsSwitchRow(
            title = stringResource(R.string.layout_cw_enabled),
            subtitle = stringResource(R.string.layout_cw_enabled_sub),
            checked = cwOn,
            onCheckedChange = { onEvent(LayoutSettingsEvent.SetContinueWatchingEnabled(it)) },
            modifier = Modifier.optionalFocusRequester(entryFocus)
        )
    }
    item(key = "layout_cw_style") {
        SettingsSegmentRow(
            title = stringResource(R.string.layout_cw_card_style),
            subtitle = if (cwOn) null else stringResource(R.string.layout_needs_cw),
            options = listOf(
                stringResource(R.string.layout_cw_card_style_card),
                stringResource(R.string.layout_cw_card_style_poster)
            ),
            selectedIndex = if (state.continueWatchingCardStyle == ContinueWatchingCardStyle.POSTER) 1 else 0,
            onSelect = { index ->
                onEvent(
                    LayoutSettingsEvent.SetContinueWatchingCardStyle(
                        if (index == 1) ContinueWatchingCardStyle.POSTER else ContinueWatchingCardStyle.CARD
                    )
                )
            },
            enabled = cwOn
        )
    }
    item(key = "layout_cw_sort") {
        SettingsValueRow(
            title = stringResource(R.string.layout_cw_sort_mode),
            subtitle = stringResource(if (cwOn) R.string.layout_cw_sort_mode_sub else R.string.layout_needs_cw),
            value = cwSortLabel(state.continueWatchingSortMode),
            onClick = onOpenSort,
            enabled = cwOn
        )
    }
    item(key = "layout_cw_furthest") {
        SettingsSwitchRow(
            title = stringResource(R.string.layout_next_up_furthest_episode),
            subtitle = stringResource(R.string.layout_next_up_furthest_episode_sub),
            checked = state.nextUpFromFurthestEpisode,
            onCheckedChange = { onEvent(LayoutSettingsEvent.SetNextUpFromFurthestEpisode(it)) }
        )
    }
    settingsAdvancedGroup(key = GROUP_CW, count = 3, open = groupOpen, onToggle = onToggleGroup) {
        // Poster cards always show poster art, so the episode thumbnail rows do not apply to them.
        val thumbnailsApply = cwOn && state.continueWatchingCardStyle != ContinueWatchingCardStyle.POSTER
        item(key = "layout_cw_thumbnails") {
            SettingsSwitchRow(
                title = stringResource(R.string.layout_use_episode_thumbnails_cw),
                subtitle = stringResource(
                    when {
                        !cwOn -> R.string.layout_needs_cw
                        !thumbnailsApply -> R.string.layout_needs_card_style
                        else -> R.string.layout_use_episode_thumbnails_cw_sub
                    }
                ),
                checked = state.useEpisodeThumbnailsInCw,
                onCheckedChange = { onEvent(LayoutSettingsEvent.SetUseEpisodeThumbnailsInCw(it)) },
                enabled = thumbnailsApply
            )
        }
        item(key = "layout_cw_blur") {
            val blurApplies = thumbnailsApply && state.useEpisodeThumbnailsInCw
            SettingsSwitchRow(
                title = stringResource(R.string.layout_blur_cw_next_up),
                subtitle = stringResource(
                    when {
                        !cwOn -> R.string.layout_needs_cw
                        !thumbnailsApply -> R.string.layout_needs_card_style
                        !blurApplies -> R.string.layout_needs_episode_thumbnails
                        else -> R.string.layout_blur_cw_next_up_sub
                    }
                ),
                checked = state.blurContinueWatchingNextUp,
                onCheckedChange = { onEvent(LayoutSettingsEvent.SetBlurContinueWatchingNextUp(it)) },
                enabled = blurApplies
            )
        }
        item(key = "layout_cw_unaired") {
            // Only the Home row reads it (HomeViewModelContinueWatching), so it dims with the row.
            SettingsSwitchRow(
                title = stringResource(R.string.layout_show_unaired_next_up),
                subtitle = stringResource(
                    if (cwOn) R.string.layout_show_unaired_next_up_sub else R.string.layout_needs_cw
                ),
                checked = state.showUnairedNextUp,
                onCheckedChange = { onEvent(LayoutSettingsEvent.SetShowUnairedNextUp(it)) },
                enabled = cwOn
            )
        }
    }
}

@Composable
private fun discoverLocationLabel(location: DiscoverLocation): String = stringResource(
    when (location) {
        DiscoverLocation.IN_SEARCH -> R.string.layout_discover_in_search
        DiscoverLocation.IN_SIDEBAR -> R.string.layout_discover_tab
        DiscoverLocation.OFF -> R.string.layout_discover_off
    }
)

@Composable
private fun episodeRatingsLabel(visibility: DetailImdbRatingsVisibility): String = stringResource(
    when (visibility) {
        DetailImdbRatingsVisibility.SHOW_ALL -> R.string.layout_ratings_show
        DetailImdbRatingsVisibility.HIDE_UNWATCHED_EPISODES -> R.string.layout_ratings_hide_unwatched
        DetailImdbRatingsVisibility.HIDE_EPISODES,
        DetailImdbRatingsVisibility.HIDE_ALL -> R.string.layout_ratings_hide
    }
)

@Composable
private fun cwSortLabel(mode: ContinueWatchingSortMode): String = stringResource(
    when (mode) {
        ContinueWatchingSortMode.DEFAULT -> R.string.layout_cw_sort_default
        ContinueWatchingSortMode.STREAMING_STYLE -> R.string.layout_cw_sort_streaming
        ContinueWatchingSortMode.SPLIT_UPCOMING -> R.string.layout_cw_sort_split_upcoming
    }
)

@Composable
private fun DiscoverPicker(
    selected: DiscoverLocation,
    onSelect: (DiscoverLocation) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsPickerPanel(
        title = stringResource(R.string.discover_title),
        options = listOf(
            SettingsPickerOption(
                DiscoverLocation.IN_SEARCH,
                discoverLocationLabel(DiscoverLocation.IN_SEARCH),
                stringResource(R.string.layout_discover_location_in_search_desc)
            ),
            SettingsPickerOption(
                DiscoverLocation.IN_SIDEBAR,
                discoverLocationLabel(DiscoverLocation.IN_SIDEBAR),
                stringResource(R.string.layout_discover_location_in_sidebar_desc)
            ),
            SettingsPickerOption(
                DiscoverLocation.OFF,
                discoverLocationLabel(DiscoverLocation.OFF),
                stringResource(R.string.layout_discover_off_desc)
            )
        ),
        selectedValue = selected,
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

@Composable
private fun EpisodeRatingsPicker(
    selected: DetailImdbRatingsVisibility,
    onSelect: (DetailImdbRatingsVisibility) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsPickerPanel(
        title = stringResource(R.string.layout_episode_ratings),
        caption = stringResource(R.string.layout_episode_ratings_sub),
        options = listOf(
            DetailImdbRatingsVisibility.SHOW_ALL,
            DetailImdbRatingsVisibility.HIDE_EPISODES,
            DetailImdbRatingsVisibility.HIDE_UNWATCHED_EPISODES
        ).map { SettingsPickerOption(it, episodeRatingsLabel(it)) },
        selectedValue = selected.asEpisodeVisibility(),
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

@Composable
private fun CwSortPicker(
    selected: ContinueWatchingSortMode,
    onSelect: (ContinueWatchingSortMode) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsPickerPanel(
        title = stringResource(R.string.layout_cw_sort_mode),
        options = listOf(
            SettingsPickerOption(
                ContinueWatchingSortMode.DEFAULT,
                cwSortLabel(ContinueWatchingSortMode.DEFAULT),
                stringResource(R.string.layout_cw_sort_default_desc)
            ),
            SettingsPickerOption(
                ContinueWatchingSortMode.STREAMING_STYLE,
                cwSortLabel(ContinueWatchingSortMode.STREAMING_STYLE),
                stringResource(R.string.layout_cw_sort_streaming_desc)
            ),
            SettingsPickerOption(
                ContinueWatchingSortMode.SPLIT_UPCOMING,
                cwSortLabel(ContinueWatchingSortMode.SPLIT_UPCOMING),
                stringResource(R.string.layout_cw_sort_split_upcoming_desc)
            )
        ),
        selectedValue = selected,
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}
