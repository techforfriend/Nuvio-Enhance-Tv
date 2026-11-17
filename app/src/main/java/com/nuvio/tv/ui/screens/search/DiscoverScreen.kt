package com.nuvio.tv.ui.screens.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.ui.components.marquee.MarqueeMessage
import com.nuvio.tv.ui.components.trailerpreview.LocalTrailerPreviewEnabled
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The Discover tab ("Show in Top Bar"); with "Show in Search" Discover lives inside Search
 * (SearchScreen stage 3) and this route is not opened. [underTopBar]: the filters start below the
 * bar, which draws the top scrim.
 */
@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel = hiltViewModel(),
    underTopBar: Boolean = false,
    onNavigateToDetail: (String, String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val discoverOff = uiState.discoverLocation == DiscoverLocation.OFF

    LaunchedEffect(uiState.discoverLocation) {
        if (!discoverOff) viewModel.ensureDiscoverLoaded()
    }

    val listState = rememberLazyListState()
    val band = rememberDiscoverBand(
        viewModel = viewModel,
        uiState = uiState,
        listState = listState,
        firstGridItem = DISCOVER_TAB_FIRST_GRID_ITEM,
        active = !discoverOff,
        onNavigateToDetail = onNavigateToDetail
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (discoverOff) {
            MarqueeMessage(
                eyebrow = stringResource(R.string.discover_title),
                title = stringResource(R.string.discover_disabled_title),
                body = AnnotatedString(stringResource(R.string.discover_disabled_subtitle))
            )
        } else {
            // Spec 96 D16: the grid's cards opt into the trailer preview (the host's In the card mode decides).
            CompositionLocalProvider(LocalTrailerPreviewEnabled provides true) {
                DiscoverSection(
                    band = band,
                    uiState = uiState,
                    headerTop = if (underTopBar) NuvioTheme.marquee.topBar.contentTop else NuvioTheme.spacing.lg,
                    drawTopScrim = !underTopBar
                )
            }
        }
        DiscoverPosterOptions(viewModel = viewModel, band = band, onNavigateToDetail = onNavigateToDetail)
    }
}
