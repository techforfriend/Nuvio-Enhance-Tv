package com.nuvio.tv.ui.navigation

import android.os.SystemClock
import com.nuvio.tv.ui.theme.NuvioMotion

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nuvio.tv.ui.screens.CatalogSeeAllScreen
import com.nuvio.tv.ui.screens.detail.DetailPlayHandler
import com.nuvio.tv.ui.screens.detail.MetaDetailsScreen
import com.nuvio.tv.ui.screens.home.HomeScreen
import com.nuvio.tv.ui.screens.addon.AddonManagerScreen
import com.nuvio.tv.ui.screens.addon.CatalogOrderScreen
import com.nuvio.tv.ui.screens.cloudstream.CloudStreamManagerScreen
import com.nuvio.tv.ui.screens.library.LibraryScreen
import com.nuvio.tv.livetv.ui.LiveTvScreen
import com.nuvio.tv.livetv.ui.LiveTvSourcesScreen
import com.nuvio.tv.ui.screens.player.PlayerBackRoute
import com.nuvio.tv.core.player.PlayerExitReason
import com.nuvio.tv.ui.screens.player.PlayerScreen
import com.nuvio.tv.ui.screens.player.playerBackRoute
import com.nuvio.tv.core.player.PlaceholderStreamPolicy.runtimeMinutesFromProgress
import com.nuvio.tv.ui.screens.search.DiscoverScreen
import com.nuvio.tv.ui.screens.search.SearchScreen
import com.nuvio.tv.ui.screens.settings.DpadComboSetupMode
import com.nuvio.tv.ui.screens.settings.DpadComboSetupScreen
import com.nuvio.tv.ui.screens.settings.SettingsEntry
import com.nuvio.tv.ui.screens.settings.SettingsScreen
import com.nuvio.tv.ui.screens.settings.openSettingsAt
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import com.nuvio.tv.ui.screens.stream.StreamScreen
import com.nuvio.tv.domain.model.ContinueWatchingItem
import com.nuvio.tv.ui.screens.account.AuthQrSignInScreen
import com.nuvio.tv.ui.screens.cast.CastDetailScreen
import com.nuvio.tv.ui.screens.profile.ProfileSelectionMode
import com.nuvio.tv.ui.screens.profile.ProfileSelectionScreen
import com.nuvio.tv.ui.screens.tmdb.TmdbEntityBrowseScreen
import com.nuvio.tv.ui.screens.home.HeroBackdropState

/**
 * [topBarRoutes]: routes the app top bar is drawn over (TopBarScaffold). Those screens start
 * their content at `NuvioTheme.marquee.topBar.contentTop` and hide their own title, which the
 * selected tab already names.
 */
@Composable
fun NuvioNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route,
    topBarRoutes: Set<String> = emptySet(),
    /** Home empty state's "Open addons"; the scaffold applies the settings lock. */
    onOpenAddons: () -> Unit = {},
    /** The settings lock (SettingsAccess): hides the player's way into Settings (Spec 33 D7). Add a
     *  TMDB key goes through [onOpenTmdbKeySettings], which applies the lock itself. */
    settingsLocked: Boolean = false,
    /** A no-key page's Add a TMDB key (Spec 52 T1); the scaffold applies the settings lock. */
    onOpenTmdbKeySettings: () -> Unit = {}
) {
    fun isStreamToPlayer(from: String, to: String): Boolean {
        return from.startsWith("stream/") && to.startsWith("player/")
    }

    fun isPlayerToStream(from: String, to: String): Boolean {
        return from.startsWith("player/") && to.startsWith("stream/")
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = targetState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isStreamToPlayer(from, to) && isAutoPlayNav) {
                EnterTransition.None
            } else {
                fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.medium))
            }
        },
        exitTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = targetState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isStreamToPlayer(from, to) && isAutoPlayNav) {
                ExitTransition.None
            } else {
                fadeOut(animationSpec = tween(NuvioMotion.tokens.durations.medium))
            }
        },
        popEnterTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = initialState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isPlayerToStream(from, to) && isAutoPlayNav) {
                EnterTransition.None
            } else {
                fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.medium))
            }
        },
        popExitTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = initialState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isPlayerToStream(from, to) && isAutoPlayNav) {
                ExitTransition.None
            } else {
                fadeOut(animationSpec = tween(NuvioMotion.tokens.durations.medium))
            }
        }
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    val heroBackdrop = HeroBackdropState.consumeForTitle(itemId, itemType)
                    navController.navigate(
                        Screen.Detail.createRoute(
                            itemId = itemId,
                            itemType = itemType,
                            addonBaseUrl = addonBaseUrl,
                            heroBackdropUrl = heroBackdrop
                        )
                    )
                },
                onContinueWatchingClick = { item ->
                    navController.navigate(continueWatchingStreamRoute(item))
                },
                onContinueWatchingStartFromBeginning = { item ->
                    navController.navigate(
                        continueWatchingStreamRoute(item, startFromBeginning = true)
                    )
                },
                onContinueWatchingPlayManually = { item ->
                    navController.navigate(
                        continueWatchingStreamRoute(item, manualSelection = true)
                    )
                },
                onNavigateToCloudStreamStream = { target ->
                    // Direct CS3 play from a Home card: no Detail exists for a CS3
                    // title, so back returns to Home and contentId stays blank so the
                    // Stream screen never tries to open Detail for it.
                    navController.navigate(
                        Screen.Stream.createRoute(
                            videoId = target.videoId,
                            contentType = target.contentType,
                            title = target.title,
                            poster = target.posterUrl,
                            season = target.season,
                            episode = target.episode,
                            contentId = "",
                            contentName = target.title,
                            returnToHomeOnBack = true
                        )
                    )
                },
                onNavigateToFolderDetail = { collectionId, folderId ->
                    navController.navigate(Screen.FolderDetail.createRoute(collectionId, folderId))
                },
                onNavigateToAddons = onOpenAddons
            )
        }

        composable(
            route = Screen.Detail.route,
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("itemType") { type = NavType.StringType },
                optionalStringArg("addonBaseUrl"),
                optionalStringArg("returnFocusSeason"),
                optionalStringArg("returnFocusEpisode"),
                optionalStringArg("returnToHomeOnBack", default = "false"),
                optionalStringArg("heroBackdropUrl")
            )
        ) { backStackEntry ->
            val detailArgs = backStackEntry.arguments
            val savedState = backStackEntry.savedStateHandle
            val returnToHomeOnBack = detailArgs
                ?.getString("returnToHomeOnBack")
                ?.toBooleanStrictOrNull() == true
            val returnFocusSeason by savedState.getStateFlow(
                "returnFocusSeason", detailArgs?.getString("returnFocusSeason")?.toIntOrNull()
            ).collectAsState()
            val returnFocusEpisode by savedState.getStateFlow(
                "returnFocusEpisode", detailArgs?.getString("returnFocusEpisode")?.toIntOrNull()
            ).collectAsState()
            val heroRestoreToken by savedState.getStateFlow(
                "heroRestoreToken", 0
            ).collectAsState()
            val heroBackdropUrl = detailArgs?.getString("heroBackdropUrl")?.takeIf { it.isNotBlank() }
            // Details' three Play entries; remembered so MetaDetailsScreen keeps skipping.
            fun detailPlayHandler(
                manualSelection: Boolean = false,
                startFromBeginning: Boolean = false
            ): DetailPlayHandler =
                { videoId, contentType, contentId, title, poster, backdrop, logo, season, episode, episodeName, genres, year, runtime, contentLanguage, streamRequestId ->
                    navController.navigate(
                        Screen.Stream.createRoute(
                            videoId = videoId,
                            contentType = contentType,
                            title = title,
                            poster = poster,
                            backdrop = backdrop,
                            logo = logo,
                            season = season,
                            episode = episode,
                            episodeName = episodeName,
                            genres = genres,
                            year = year,
                            contentId = contentId,
                            contentName = title,
                            runtime = runtime,
                            manualSelection = manualSelection,
                            returnToDetailOnBack = contentType.equals("series", ignoreCase = true),
                            startFromBeginning = startFromBeginning,
                            contentLanguage = contentLanguage,
                            streamRequestId = streamRequestId
                        )
                    )
                }
            MetaDetailsScreen(
                returnFocusSeason = returnFocusSeason,
                returnFocusEpisode = returnFocusEpisode,
                heroRestoreToken = heroRestoreToken,
                heroBackdropUrl = heroBackdropUrl,
                onReturnFocusConsumed = {
                    savedState["returnFocusSeason"] = null
                    savedState["returnFocusEpisode"] = null
                },
                onBackPress = {
                    if (returnToHomeOnBack) {
                        val popped = navController.popBackStack(Screen.Home.route, inclusive = false)
                        if (!popped) {
                            navController.navigate(Screen.Home.route) {
                                launchSingleTop = true
                            }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onNavigateToCastDetail = { personId, personName, department, sourceTitle, sectionLabel ->
                    navController.navigate(
                        Screen.CastDetail.createRoute(personId, personName, department, sourceTitle, sectionLabel)
                    )
                },
                onNavigateToSearch = { query ->
                    navController.navigate(Screen.Search.createRoute(query))
                },
                onNavigateToDiscoverGenre = { type, genre ->
                    navController.navigate(Screen.Discover.createRoute(type, genre))
                },
                onNavigateToTmdbEntityBrowse = { entityKind, entityId, entityName, sourceType, sourceTitle, sectionLabel ->
                    navController.navigate(
                        Screen.TmdbEntityBrowse.createRoute(
                            entityKind = entityKind,
                            entityId = entityId,
                            entityName = entityName,
                            sourceType = sourceType,
                            sourceTitle = sourceTitle,
                            sectionLabel = sectionLabel
                        )
                    )
                },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onPlayClick = remember(navController) { detailPlayHandler() },
                onPlayManuallyClick = remember(navController) { detailPlayHandler(manualSelection = true) },
                onPlayStartFromBeginningClick = remember(navController) {
                    detailPlayHandler(startFromBeginning = true)
                }
            )
        }

        composable(
            route = Screen.Stream.route,
            arguments = listOf(
                navArgument("videoId") { type = NavType.StringType },
                navArgument("contentType") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
                optionalStringArg("poster"),
                optionalStringArg("backdrop"),
                optionalStringArg("logo"),
                optionalStringArg("season"),
                optionalStringArg("episode"),
                optionalStringArg("episodeName"),
                optionalStringArg("genres"),
                optionalStringArg("year"),
                optionalStringArg("contentId"),
                optionalStringArg("contentName"),
                optionalStringArg("runtime"),
                optionalStringArg("manualSelection", default = "false"),
                optionalStringArg("returnToDetailOnBack", default = "false"),
                optionalStringArg("returnToHomeOnBack", default = "false"),
                optionalStringArg("startFromBeginning", default = "false"),
                optionalStringArg("contentLanguage"),
                optionalStringArg("streamRequestId")
            )
        ) { backStackEntry ->
            val streamArgs = backStackEntry.arguments
            val returnToDetailOnBack = streamArgs
                ?.getString("returnToDetailOnBack")
                ?.toBooleanStrictOrNull() == true
            val returnToHomeOnBack = streamArgs
                ?.getString("returnToHomeOnBack")
                ?.toBooleanStrictOrNull() == true
            val startFromBeginning = streamArgs
                ?.getString("startFromBeginning")
                ?.toBooleanStrictOrNull() == true
            StreamScreen(
                startFromBeginning = startFromBeginning,
                onBackPress = {
                    val streamContentType = streamArgs?.getString("contentType").orEmpty()
                    val streamContentId = streamArgs?.getString("contentId").orEmpty()
                    val season = streamArgs?.getString("season")?.toIntOrNull()
                    val episode = streamArgs?.getString("episode")?.toIntOrNull()
                    if (streamContentType.equals("series", ignoreCase = true) && streamContentId.isNotBlank()) {
                        val detailEntry = runCatching { navController.getBackStackEntry(Screen.Detail.route) }.getOrNull()
                        if (detailEntry != null) {
                            detailEntry.savedStateHandle["returnFocusSeason"] = season
                            detailEntry.savedStateHandle["returnFocusEpisode"] = episode
                            navController.popBackStack(Screen.Detail.route, inclusive = false)
                        } else {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = streamContentId,
                                    itemType = streamContentType,
                                    addonBaseUrl = null,
                                    returnFocusSeason = season,
                                    returnFocusEpisode = episode,
                                    returnToHomeOnBack = returnToHomeOnBack,
                                    heroBackdropUrl = streamArgs?.getString("backdrop")
                                )
                            ) {
                                popUpTo(Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onStreamSelected = { playbackInfo ->
                    playbackInfo.toPlayerRoute(
                        autoPlayNav = false,
                        returnToDetailOnBack = returnToDetailOnBack,
                        returnToHomeOnBack = returnToHomeOnBack,
                        startFromBeginning = startFromBeginning
                    )?.let { route -> navController.navigate(route) }
                },
                onAutoPlayResolved = { playbackInfo ->
                    playbackInfo.toPlayerRoute(
                        autoPlayNav = true,
                        returnToDetailOnBack = returnToDetailOnBack,
                        returnToHomeOnBack = returnToHomeOnBack,
                        startFromBeginning = startFromBeginning
                    )?.let { route ->
                        navController.navigate(route) {
                            popUpTo(Screen.Stream.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("streamUrl") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
                optionalStringArg("streamName"),
                optionalStringArg("year"),
                optionalStringArg("headers"),
                optionalStringArg("contentId"),
                optionalStringArg("contentType"),
                optionalStringArg("contentName"),
                optionalStringArg("poster"),
                optionalStringArg("backdrop"),
                optionalStringArg("logo"),
                optionalStringArg("videoId"),
                optionalStringArg("season"),
                optionalStringArg("episode"),
                optionalStringArg("episodeTitle"),
                optionalStringArg("bingeGroup"),
                optionalStringArg("autoPlayNav", default = "false"),
                optionalStringArg("returnToDetailOnBack", default = "false"),
                optionalStringArg("returnToHomeOnBack", default = "false"),
                optionalStringArg("filename"),
                optionalStringArg("videoHash"),
                optionalStringArg("videoSize"),
                optionalStringArg("runtimeMinutes"),
                optionalStringArg("startFromBeginning", default = "false"),
                optionalStringArg("addonName"),
                optionalStringArg("addonLogo"),
                optionalStringArg("streamDescription"),
                optionalStringArg("contentLanguage"),
                optionalStringArg("subtitles"),
                optionalStringArg("launchStartedAtMs")
            )
        ) { backStackEntry ->
            PlayerScreen(
                // Pushed over the film: the scaffold hides the top bar there, so Back returns here.
                onOpenNetworkSettings = if (settingsLocked) null else {
                    { navController.openSettingsAt(SettingsEntry.NETWORK) { navigate(Screen.Settings.route) } }
                },
                onBackPress = { currentVideoId, currentSeason, currentEpisode, autoPlayEnabled, playbackCompleted ->
                    val args = backStackEntry.arguments
                    val initialSeason = args?.getString("season")?.toIntOrNull()
                    val initialEpisode = args?.getString("episode")?.toIntOrNull()
                    val episodeChangedInPlace = (currentSeason != null || currentEpisode != null) &&
                        (currentSeason != initialSeason || currentEpisode != initialEpisode)
                    val returnToDetailOnBack = args?.getString("returnToDetailOnBack")
                        ?.toBooleanStrictOrNull() == true
                    val returnToHomeOnBack = args?.getString("returnToHomeOnBack")
                        ?.toBooleanStrictOrNull() == true
                    val contentType = args?.getString("contentType").orEmpty()
                    val contentId = args?.getString("contentId").orEmpty()
                    val focusSeason = currentSeason ?: initialSeason
                    val focusEpisode = currentEpisode ?: initialEpisode
                    fun returnToDetail() = navController.returnToDetailFromPlayer(
                        contentId = contentId,
                        contentType = contentType,
                        focusSeason = focusSeason,
                        focusEpisode = focusEpisode,
                        returnToHomeOnBack = returnToHomeOnBack,
                        heroBackdropUrl = args?.getString("backdrop")
                    )
                    fun returnToDetailOrPop() {
                        if (returnToDetailOnBack && contentType.equals("series", ignoreCase = true) && contentId.isNotBlank()) {
                            returnToDetail()
                        } else {
                            navController.popBackStack()
                        }
                    }

                    when (
                        playerBackRoute(
                            episodeChangedInPlace = episodeChangedInPlace,
                            autoPlayEnabled = autoPlayEnabled,
                            playbackCompleted = playbackCompleted,
                            hasContentId = contentId.isNotBlank()
                        )
                    ) {
                        PlayerBackRoute.DETAIL -> returnToDetail()
                        // autoplay moved to next episode — skip Stream, go to detail
                        PlayerBackRoute.DETAIL_OR_POP -> returnToDetailOrPop()
                        PlayerBackRoute.CURRENT_EPISODE_STREAMS -> {
                            // manual stream switch to next episode — go to Stream of current episode
                            val videoId = currentVideoId ?: args?.getString("videoId").orEmpty()
                            if (videoId.isNotBlank() && contentType.isNotBlank()) {
                                navController.navigate(
                                    Screen.Stream.createRoute(
                                        videoId = videoId,
                                        contentType = contentType,
                                        title = args?.getString("title").orEmpty(),
                                        poster = args?.getString("poster"),
                                        backdrop = args?.getString("backdrop"),
                                        logo = args?.getString("logo"),
                                        season = focusSeason,
                                        episode = focusEpisode,
                                        year = args?.getString("year"),
                                        contentId = contentId.takeIf { it.isNotBlank() },
                                        contentName = args?.getString("contentName"),
                                        manualSelection = true,
                                        returnToDetailOnBack = returnToDetailOnBack,
                                        returnToHomeOnBack = returnToHomeOnBack
                                    )
                                ) {
                                    popUpTo(Screen.Stream.route) { inclusive = true }
                                    launchSingleTop = true
                                }
                            } else {
                                navController.popBackStack()
                            }
                        }
                        PlayerBackRoute.PREVIOUS_SCREEN -> {
                            val returnedToStream = navController.popBackStack(Screen.Stream.route, inclusive = false)
                            if (!returnedToStream) returnToDetailOrPop()
                        }
                    }
                },
                onPlaybackEnded = { nextVideoId, nextSeason, nextEpisode, exitReason ->
                    val args = backStackEntry.arguments
                    val contentType = args?.getString("contentType").orEmpty()
                    val contentId = args?.getString("contentId").orEmpty()
                    val returnToDetailOnBack = args?.getString("returnToDetailOnBack")
                        ?.toBooleanStrictOrNull() == true
                    val returnToHomeOnBack = args?.getString("returnToHomeOnBack")
                        ?.toBooleanStrictOrNull() == true
                    if (nextVideoId != null && nextSeason != null && nextEpisode != null) {
                        val route = Screen.Stream.createRoute(
                            videoId = nextVideoId,
                            contentType = contentType,
                            title = args?.getString("title").orEmpty(),
                            poster = args?.getString("poster"),
                            backdrop = args?.getString("backdrop"),
                            logo = args?.getString("logo"),
                            season = nextSeason,
                            episode = nextEpisode,
                            episodeName = null,
                            genres = null,
                            year = args?.getString("year"),
                            contentId = contentId.takeIf { it.isNotBlank() },
                            contentName = args?.getString("contentName"),
                            runtime = null,
                            returnToDetailOnBack = returnToDetailOnBack,
                            returnToHomeOnBack = returnToHomeOnBack
                        )
                        navController.navigate(route) {
                            popUpTo(Screen.Player.route) { inclusive = true }
                        }
                    } else {
                        if (exitReason == PlayerExitReason.StillWatchingPrompt) {
                            val detailEntry = navController.currentBackStack.value
                                .lastOrNull { it.destination.route?.startsWith("detail/") == true }
                            if (detailEntry != null) {
                                detailEntry.savedStateHandle["returnFocusSeason"] = null
                                detailEntry.savedStateHandle["returnFocusEpisode"] = null
                                val token = (detailEntry.savedStateHandle.get<Int>("heroRestoreToken") ?: 0) + 1
                                detailEntry.savedStateHandle["heroRestoreToken"] = token
                                navController.popBackStack(Screen.Detail.route, inclusive = false)
                            } else {
                                if (contentId.isNotBlank()) {
                                    navController.navigate(
                                        Screen.Detail.createRoute(
                                            itemId = contentId,
                                            itemType = contentType,
                                            addonBaseUrl = null,
                                            returnToHomeOnBack = returnToHomeOnBack,
                                            heroBackdropUrl = args?.getString("backdrop")
                                        )
                                    ) {
                                        popUpTo(Screen.Player.route) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                } else {
                                    val poppedToStream = navController.popBackStack(Screen.Stream.route, inclusive = true)
                                    if (!poppedToStream) {
                                        navController.popBackStack()
                                    }
                                }
                            }
                        } else {
                            if (contentId.isNotBlank()) {
                                navController.returnToDetailFromPlayer(
                                    contentId = contentId,
                                    contentType = contentType,
                                    focusSeason = args?.getString("season")?.toIntOrNull(),
                                    focusEpisode = args?.getString("episode")?.toIntOrNull(),
                                    returnToHomeOnBack = returnToHomeOnBack,
                                    heroBackdropUrl = args?.getString("backdrop")
                                )
                            } else {
                                val poppedToStream = navController.popBackStack(Screen.Stream.route, inclusive = true)
                                if (!poppedToStream) {
                                    navController.popBackStack()
                                }
                            }
                        }
                    }
                },
                onPlaybackErrorBack = {
                    val returnedToStream = navController.popBackStack(Screen.Stream.route, inclusive = false)
                    if (!returnedToStream) {
                        val args = backStackEntry.arguments
                        val videoId = args?.getString("videoId").orEmpty()
                        val contentType = args?.getString("contentType").orEmpty()
                        val title = args?.getString("title").orEmpty()

                        if (videoId.isBlank() || contentType.isBlank() || title.isBlank()) {
                            navController.popBackStack()
                        } else {
                            val route = Screen.Stream.createRoute(
                                videoId = videoId,
                                contentType = contentType,
                                title = title,
                                poster = args?.getString("poster"),
                                backdrop = args?.getString("backdrop"),
                                logo = args?.getString("logo"),
                                season = args?.getString("season")?.toIntOrNull(),
                                episode = args?.getString("episode")?.toIntOrNull(),
                                episodeName = args?.getString("episodeTitle"),
                                genres = null,
                                year = args?.getString("year"),
                                contentId = args?.getString("contentId"),
                                contentName = args?.getString("contentName"),
                                runtime = null,
                                manualSelection = true,
                                returnToDetailOnBack = args?.getString("returnToDetailOnBack")
                                    ?.toBooleanStrictOrNull() == true,
                                returnToHomeOnBack = args?.getString("returnToHomeOnBack")
                                    ?.toBooleanStrictOrNull() == true
                            )

                            navController.navigate(route) {
                                popUpTo(Screen.Player.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            )
        }

        // One body for both Search destinations: the root (top-bar) route and the
        // pushed QUERY_ROUTE, whose QUERY_ARG the ViewModel prefills and runs.
        val searchScreenContent: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit = { backStackEntry ->
            val searchViewModel: com.nuvio.tv.ui.screens.search.SearchViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(backStackEntry)
            SearchScreen(
                viewModel = searchViewModel,
                underTopBar = backStackEntry.destination.route in topBarRoutes,
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    val heroBackdrop = HeroBackdropState.consumeAndClear()
                    navController.navigate(
                        Screen.Detail.createRoute(
                            itemId = itemId,
                            itemType = itemType,
                            addonBaseUrl = addonBaseUrl,
                            heroBackdropUrl = heroBackdrop
                        )
                    )
                },
                onNavigateToSeeAll = { catalogId, addonId, type ->
                    navController.navigate(
                        Screen.CatalogSeeAll.createRoute(catalogId, addonId, type, fromSearch = true)
                    )
                }
            )
        }
        composable(Screen.Search.route, content = searchScreenContent)
        composable(
            route = Screen.Search.QUERY_ROUTE,
            arguments = listOf(navArgument(Screen.Search.QUERY_ARG) { type = NavType.StringType }),
            content = searchScreenContent
        )

        // One body for both Discover destinations: the root (top-bar) route and the pushed
        // GENRE_ROUTE, whose args the ViewModel applies to its first load.
        val discoverScreenContent: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit = { backStackEntry ->
            DiscoverScreen(
                viewModel = androidx.hilt.navigation.compose.hiltViewModel(backStackEntry),
                underTopBar = backStackEntry.destination.route in topBarRoutes,
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    val heroBackdrop = HeroBackdropState.consumeAndClear()
                    navController.navigate(
                        Screen.Detail.createRoute(
                            itemId = itemId,
                            itemType = itemType,
                            addonBaseUrl = addonBaseUrl,
                            heroBackdropUrl = heroBackdrop
                        )
                    )
                }
            )
        }
        composable(Screen.Discover.route, content = discoverScreenContent)
        composable(
            route = Screen.Discover.GENRE_ROUTE,
            arguments = listOf(
                navArgument(Screen.Discover.TYPE_ARG) { type = NavType.StringType },
                navArgument(Screen.Discover.GENRE_ARG) { type = NavType.StringType }
            ),
            content = discoverScreenContent
        )

        // Always a top-bar tab: content starts at the bar's contentTop (Spec 34 D1).
        composable(Screen.LiveTv.route) {
            LiveTvScreen(onOpenSources = { add -> navController.navigate(Screen.LiveTvSources.createRoute(add)) })
        }

        composable(
            route = Screen.LiveTvSources.route,
            arguments = listOf(
                navArgument(Screen.LiveTvSources.ADD_ARG) {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            LiveTvSourcesScreen(
                openAdd = backStackEntry.arguments?.getBoolean(Screen.LiveTvSources.ADD_ARG) == true,
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.Library.route) {
            LibraryScreen(
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    val heroBackdrop = HeroBackdropState.consumeAndClear()
                    navController.navigate(
                        Screen.Detail.createRoute(
                            itemId = itemId,
                            itemType = itemType,
                            addonBaseUrl = addonBaseUrl,
                            heroBackdropUrl = heroBackdrop
                        )
                    )
                }
            )
        }

        composable(Screen.Settings.route) { backStackEntry ->
            val settingsEntry by backStackEntry.savedStateHandle
                .getStateFlow<String?>(Screen.Settings.ENTRY_KEY, null)
                .collectAsStateWithLifecycle()
            SettingsScreen(
                entryName = settingsEntry,
                onEntryConsumed = { backStackEntry.savedStateHandle[Screen.Settings.ENTRY_KEY] = null },
                onNavigateToAddons = { navController.navigate(Screen.AddonManager.route) },
                onNavigateToCloudStream = { navController.navigate(Screen.CloudStreamManager.route) },
                onNavigateToCatalogOrder = { navController.navigate(Screen.CatalogOrder.route) },
                onNavigateToCollections = { navController.navigate(Screen.Collections.route) },
                onNavigateToAuthQrSignIn = { navController.navigate(Screen.AuthQrSignIn.route) },
                onNavigateToManageProfiles = { navController.navigate(Screen.ManageProfiles.route) },
                onNavigateToDpadComboSetup = { mode ->
                    navController.navigate(Screen.DpadComboSetup.createRoute(mode))
                }
            )
        }

        composable(
            route = Screen.DpadComboSetup.route,
            arguments = listOf(
                navArgument("mode") {
                    type = NavType.StringType
                    defaultValue = DpadComboSetupMode.SETUP
                }
            )
        ) { backStackEntry ->
            val mode = backStackEntry.arguments?.getString("mode") ?: DpadComboSetupMode.SETUP
            DpadComboSetupScreen(
                mode = mode,
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.ManageProfiles.route) {
            ProfileSelectionScreen(
                onProfileSelected = {},
                screenMode = ProfileSelectionMode.Management,
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.AddonManager.route) {
            AddonManagerScreen(onBackPress = { navController.popBackStack() })
        }

        composable(Screen.CloudStreamManager.route) {
            CloudStreamManagerScreen(onBackPress = { navController.popBackStack() })
        }

        composable(Screen.CatalogOrder.route) {
            CatalogOrderScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.Collections.route) {
            com.nuvio.tv.ui.screens.collection.CollectionManagementScreen(
                onNavigateToEditor = { collectionId ->
                    navController.navigate(Screen.CollectionEditor.createRoute(collectionId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.CollectionEditor.route,
            arguments = listOf(
                optionalStringArg("collectionId")
            )
        ) {
            com.nuvio.tv.ui.screens.collection.CollectionEditorScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.FolderDetail.route,
            arguments = listOf(
                navArgument("collectionId") { type = NavType.StringType },
                navArgument("folderId") { type = NavType.StringType }
            )
        ) {
            com.nuvio.tv.ui.screens.collection.FolderDetailScreen(
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    val heroBackdrop = HeroBackdropState.consumeForTitle(itemId, itemType)
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl, heroBackdropUrl = heroBackdrop))
                },
                onBack = { navController.popBackStack() },
                onAddTmdbKey = onOpenTmdbKeySettings
            )
        }

        composable(Screen.AuthQrSignIn.route) {
            AuthQrSignInScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.CatalogSeeAll.route,
            arguments = listOf(
                navArgument("catalogId") { type = NavType.StringType },
                navArgument("addonId") { type = NavType.StringType },
                navArgument("type") { type = NavType.StringType },
                navArgument("fromSearch") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val catalogId = backStackEntry.arguments?.getString("catalogId") ?: ""
            val addonId = backStackEntry.arguments?.getString("addonId") ?: ""
            val type = backStackEntry.arguments?.getString("type") ?: ""
            val fromSearch = backStackEntry.arguments?.getBoolean("fromSearch") ?: false

            // When coming from search, get the SearchViewModel from the Search back stack entry
            // so we share the same data (existing results + pagination)
            val searchBackStackEntry = androidx.compose.runtime.remember(fromSearch) {
                if (fromSearch) {
                    // Topmost entry of either Search destination (root or pushed query search).
                    navController.currentBackStack.value.lastOrNull {
                        it.destination.route == Screen.Search.route ||
                            it.destination.route == Screen.Search.QUERY_ROUTE
                    }
                } else null
            }
            if (searchBackStackEntry == null) {
                // See All only opens from Search; with no Search entry behind it there is no row
                // to show (unreachable today), so go back rather than render another screen's row.
                androidx.compose.runtime.LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                CatalogSeeAllScreen(
                    catalogId = catalogId,
                    addonId = addonId,
                    type = type,
                    searchViewModel = androidx.hilt.navigation.compose.hiltViewModel<com.nuvio.tv.ui.screens.search.SearchViewModel>(searchBackStackEntry),
                    onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                        val heroBackdrop = HeroBackdropState.consumeForTitle(itemId, itemType)
                        navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl, heroBackdropUrl = heroBackdrop))
                    },
                    onBackPress = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Screen.CastDetail.route,
            arguments = listOf(
                navArgument("personId") { type = NavType.StringType },
                navArgument("personName") { type = NavType.StringType },
                optionalStringArg("department"),
                optionalStringArg("sourceTitle"),
                optionalStringArg("sectionLabel")
            )
        ) {
            CastDetailScreen(
                onBackPress = { navController.popBackStack() },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onAddTmdbKey = onOpenTmdbKeySettings
            )
        }

        composable(
            route = Screen.TmdbEntityBrowse.route,
            arguments = listOf(
                navArgument("entityKind") { type = NavType.StringType },
                navArgument("entityId") { type = NavType.IntType },
                navArgument("entityName") { type = NavType.StringType },
                navArgument("sourceType") {
                    type = NavType.StringType
                    defaultValue = "tv"
                },
                optionalStringArg("sourceTitle"),
                optionalStringArg("sectionLabel")
            )
        ) {
            TmdbEntityBrowseScreen(
                onBackPress = { navController.popBackStack() },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onAddTmdbKey = onOpenTmdbKeySettings
            )
        }
    }
}

/** Optional route argument: nullable String, absent = [default]. */
private fun optionalStringArg(name: String, default: String? = null): NamedNavArgument = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = default
}

/** Player route for a stream picked on the Stream screen; null when it has nothing playable. */
private fun StreamPlaybackInfo.toPlayerRoute(
    autoPlayNav: Boolean,
    returnToDetailOnBack: Boolean,
    returnToHomeOnBack: Boolean,
    startFromBeginning: Boolean
): String? {
    val streamUrl = url ?: if (isTorrent) "torrent://$infoHash" else null
    return streamUrl?.let {
        Screen.Player.createRoute(
            streamUrl = it,
            title = title,
            streamName = streamName,
            year = year,
            headers = headers,
            contentId = contentId,
            contentType = contentType,
            contentName = contentName,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            bingeGroup = bingeGroup,
            autoPlayNav = autoPlayNav,
            returnToDetailOnBack = returnToDetailOnBack,
            returnToHomeOnBack = returnToHomeOnBack,
            filename = filename,
            videoHash = videoHash,
            videoSize = videoSize,
            runtimeMinutes = runtimeMinutes,
            startFromBeginning = startFromBeginning,
            addonName = addonName,
            addonLogo = addonLogo,
            streamDescription = streamDescription,
            infoHash = infoHash,
            fileIdx = fileIdx,
            sources = sources,
            contentLanguage = contentLanguage,
            subtitles = subtitles,
            launchStartedAtMs = launchStartedAtMs ?: SystemClock.elapsedRealtime()
        )
    }
}

/**
 * Player -> Detail: pops back to this title's Detail entry (same item id; same type when
 * both are known) and asks it to refocus [focusSeason]/[focusEpisode]; with no such entry,
 * replaces the player with a fresh Detail.
 */
private fun NavHostController.returnToDetailFromPlayer(
    contentId: String,
    contentType: String,
    focusSeason: Int?,
    focusEpisode: Int?,
    returnToHomeOnBack: Boolean,
    heroBackdropUrl: String?
) {
    val detailEntry = currentBackStack.value
        .lastOrNull {
            val itemId = it.arguments?.getString("itemId").orEmpty()
            val itemType = it.arguments?.getString("itemType").orEmpty()
            it.destination.route?.startsWith("detail/") == true &&
                itemId == contentId &&
                (itemType.isBlank() || contentType.isBlank() || itemType.equals(contentType, ignoreCase = true))
        }
    if (detailEntry != null) {
        detailEntry.savedStateHandle["returnFocusSeason"] = focusSeason
        detailEntry.savedStateHandle["returnFocusEpisode"] = focusEpisode
        popBackStack(Screen.Detail.route, inclusive = false)
    } else {
        navigate(
            Screen.Detail.createRoute(
                itemId = contentId,
                itemType = contentType,
                addonBaseUrl = null,
                returnFocusSeason = focusSeason,
                returnFocusEpisode = focusEpisode,
                returnToHomeOnBack = returnToHomeOnBack,
                heroBackdropUrl = heroBackdropUrl
            )
        ) {
            popUpTo(Screen.Player.route) { inclusive = true }
            launchSingleTop = true
        }
    }
}

/**
 * Stream route for a Continue Watching card. A session started straight from a CW card returns
 * to Home on Back (Arshad 2026-10-09, window NOTE N1): no Details hop, also for series. A start
 * that resolves via the Stream list keeps the Stream screen's own Back; a finished episode still
 * goes to Details (playerBackRoute DETAIL, T26).
 */
internal fun continueWatchingStreamRoute(
    item: ContinueWatchingItem,
    manualSelection: Boolean = false,
    startFromBeginning: Boolean = false
): String {
    return when (item) {
        is ContinueWatchingItem.InProgress -> Screen.Stream.createRoute(
            videoId = item.progress.videoId,
            contentType = item.progress.contentType,
            title = item.progress.name,
            poster = item.progress.poster,
            backdrop = item.progress.backdrop,
            logo = item.progress.logo,
            season = item.progress.season,
            episode = item.progress.episode,
            episodeName = item.progress.episodeTitle,
            genres = null,
            year = null,
            contentId = item.progress.contentId,
            contentName = item.progress.name,
            runtime = runtimeMinutesFromProgress(item.progress.duration),
            manualSelection = manualSelection,
            returnToDetailOnBack = false,
            returnToHomeOnBack = true,
            startFromBeginning = startFromBeginning,
            contentLanguage = item.contentLanguage
        )
        is ContinueWatchingItem.NextUp -> Screen.Stream.createRoute(
            videoId = item.info.videoId,
            contentType = item.info.contentType,
            title = item.info.name,
            poster = item.info.poster,
            backdrop = item.info.backdrop,
            logo = item.info.logo,
            season = item.info.season,
            episode = item.info.episode,
            episodeName = item.info.episodeTitle,
            genres = null,
            year = null,
            contentId = item.info.contentId,
            contentName = item.info.name,
            runtime = null,
            manualSelection = manualSelection,
            returnToDetailOnBack = false,
            returnToHomeOnBack = true,
            startFromBeginning = startFromBeginning,
            contentLanguage = item.info.contentLanguage
        )
    }
}
