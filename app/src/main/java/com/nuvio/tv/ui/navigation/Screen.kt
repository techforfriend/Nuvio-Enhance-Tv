package com.nuvio.tv.ui.navigation

import android.os.SystemClock
import com.nuvio.tv.core.util.encodeUrlArg

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Detail : Screen("detail/{itemId}/{itemType}?addonBaseUrl={addonBaseUrl}&returnFocusSeason={returnFocusSeason}&returnFocusEpisode={returnFocusEpisode}&returnToHomeOnBack={returnToHomeOnBack}&heroBackdropUrl={heroBackdropUrl}") {

        fun createRoute(
            itemId: String,
            itemType: String,
            addonBaseUrl: String? = null,
            returnFocusSeason: Int? = null,
            returnFocusEpisode: Int? = null,
            returnToHomeOnBack: Boolean = false,
            heroBackdropUrl: String? = null
        ): String {
            val encodedItemId = encodeUrlArg(itemId)
            val encodedItemType = encodeUrlArg(itemType)
            val encodedAddon = addonBaseUrl?.let { encodeUrlArg(it) } ?: ""
            val encodedHeroBackdrop = heroBackdropUrl?.let { encodeUrlArg(it) } ?: ""
            return "detail/$encodedItemId/$encodedItemType?addonBaseUrl=$encodedAddon&returnFocusSeason=${returnFocusSeason ?: ""}&returnFocusEpisode=${returnFocusEpisode ?: ""}&returnToHomeOnBack=$returnToHomeOnBack&heroBackdropUrl=$encodedHeroBackdrop"
        }
    }
    data object Stream : Screen("stream/{videoId}/{contentType}/{title}?poster={poster}&backdrop={backdrop}&logo={logo}&season={season}&episode={episode}&episodeName={episodeName}&genres={genres}&year={year}&contentId={contentId}&contentName={contentName}&runtime={runtime}&manualSelection={manualSelection}&returnToDetailOnBack={returnToDetailOnBack}&returnToHomeOnBack={returnToHomeOnBack}&startFromBeginning={startFromBeginning}&contentLanguage={contentLanguage}&streamRequestId={streamRequestId}") {

        /**
         * [videoId] is the playback identity (player, watch progress, link cache).
         * [streamRequestId], when set, is the id streams are requested for instead
         * (Stremio `behaviorHints.defaultVideoId`); null means [videoId].
         */
        fun createRoute(
            videoId: String,
            contentType: String,
            title: String,
            poster: String? = null,
            backdrop: String? = null,
            logo: String? = null,
            season: Int? = null,
            episode: Int? = null,
            episodeName: String? = null,
            genres: String? = null,
            year: String? = null,
            contentId: String? = null,
            contentName: String? = null,
            runtime: Int? = null,
            manualSelection: Boolean = false,
            returnToDetailOnBack: Boolean = false,
            returnToHomeOnBack: Boolean = false,
            startFromBeginning: Boolean = false,
            contentLanguage: String? = null,
            streamRequestId: String? = null
        ): String {
            val encodedVideoId = encodeUrlArg(videoId)
            val encodedContentTypePath = encodeUrlArg(contentType)
            val encodedTitle = encodeUrlArg(title)
            val encodedPoster = poster?.let { encodeUrlArg(it) } ?: ""
            val encodedBackdrop = backdrop?.let { encodeUrlArg(it) } ?: ""
            val encodedLogo = logo?.let { encodeUrlArg(it) } ?: ""
            val encodedEpisodeName = episodeName?.let { encodeUrlArg(it) } ?: ""
            val encodedGenres = genres?.let { encodeUrlArg(it) } ?: ""
            val encodedYear = year?.let { encodeUrlArg(it) } ?: ""
            val encodedContentId = contentId?.let { encodeUrlArg(it) } ?: ""
            val encodedContentName = contentName?.let { encodeUrlArg(it) } ?: ""
            val encodedContentLanguage = contentLanguage?.let { encodeUrlArg(it) } ?: ""
            val encodedStreamRequestId = streamRequestId?.let { encodeUrlArg(it) } ?: ""
            return "stream/$encodedVideoId/$encodedContentTypePath/$encodedTitle?poster=$encodedPoster&backdrop=$encodedBackdrop&logo=$encodedLogo&season=${season ?: ""}&episode=${episode ?: ""}&episodeName=$encodedEpisodeName&genres=$encodedGenres&year=$encodedYear&contentId=$encodedContentId&contentName=$encodedContentName&runtime=${runtime ?: ""}&manualSelection=$manualSelection&returnToDetailOnBack=$returnToDetailOnBack&returnToHomeOnBack=$returnToHomeOnBack&startFromBeginning=$startFromBeginning&contentLanguage=$encodedContentLanguage&streamRequestId=$encodedStreamRequestId"
        }
    }
    data object Player : Screen("player/{streamUrl}/{title}?streamName={streamName}&year={year}&headers={headers}&contentId={contentId}&contentType={contentType}&contentName={contentName}&poster={poster}&backdrop={backdrop}&logo={logo}&videoId={videoId}&season={season}&episode={episode}&episodeTitle={episodeTitle}&bingeGroup={bingeGroup}&autoPlayNav={autoPlayNav}&returnToDetailOnBack={returnToDetailOnBack}&returnToHomeOnBack={returnToHomeOnBack}&filename={filename}&videoHash={videoHash}&videoSize={videoSize}&startFromBeginning={startFromBeginning}&addonName={addonName}&addonLogo={addonLogo}&streamDescription={streamDescription}&infoHash={infoHash}&fileIdx={fileIdx}&sources={sources}&contentLanguage={contentLanguage}&runtimeMinutes={runtimeMinutes}&subtitles={subtitles}&launchStartedAtMs={launchStartedAtMs}") {

        fun createRoute(
            streamUrl: String,
            title: String,
            streamName: String? = null,
            year: String? = null,
            headers: Map<String, String>? = null,
            contentId: String? = null,
            contentType: String? = null,
            contentName: String? = null,
            poster: String? = null,
            backdrop: String? = null,
            logo: String? = null,
            videoId: String? = null,
            season: Int? = null,
            episode: Int? = null,
            episodeTitle: String? = null,
            bingeGroup: String? = null,
            autoPlayNav: Boolean = false,
            returnToDetailOnBack: Boolean = false,
            returnToHomeOnBack: Boolean = false,
            filename: String? = null,
            videoHash: String? = null,
            videoSize: Long? = null,
            startFromBeginning: Boolean = false,
            addonName: String? = null,
            addonLogo: String? = null,
            streamDescription: String? = null,
            infoHash: String? = null,
            fileIdx: Int? = null,
            sources: List<String>? = null,
            contentLanguage: String? = null,
            /** Expected runtime in minutes, already resolved by the stream screen. */
            runtimeMinutes: Int? = null,
            /** The stream's own subtitles (Stremio `stream.subtitles`). */
            subtitles: List<com.nuvio.tv.domain.model.Subtitle> = emptyList(),
            launchStartedAtMs: Long = SystemClock.elapsedRealtime()
        ): String {
            val encodedUrl = encodeUrlArg(streamUrl)
            val encodedTitle = encodeUrlArg(title)
            val encodedStreamName = streamName?.let { encodeUrlArg(it) } ?: ""
            val encodedYear = year?.let { encodeUrlArg(it) } ?: ""
            val encodedHeaders = headers?.let {
                encodeUrlArg(org.json.JSONObject(it).toString())
            } ?: ""
            val encodedContentId = contentId?.let { encodeUrlArg(it) } ?: ""
            val encodedContentType = contentType?.let { encodeUrlArg(it) } ?: ""
            val encodedContentName = contentName?.let { encodeUrlArg(it) } ?: ""
            val encodedPoster = poster?.let { encodeUrlArg(it) } ?: ""
            val encodedBackdrop = backdrop?.let { encodeUrlArg(it) } ?: ""
            val encodedLogo = logo?.let { encodeUrlArg(it) } ?: ""
            val encodedVideoId = videoId?.let { encodeUrlArg(it) } ?: ""
            val encodedEpisodeTitle = episodeTitle?.let { encodeUrlArg(it) } ?: ""
            val encodedBingeGroup = bingeGroup?.let { encodeUrlArg(it) } ?: ""
            val encodedFilename = filename?.let { encodeUrlArg(it) } ?: ""
            val encodedVideoHash = videoHash?.let { encodeUrlArg(it) } ?: ""
            val encodedAddonName = addonName?.let { encodeUrlArg(it) } ?: ""
            val encodedAddonLogo = addonLogo?.let { encodeUrlArg(it) } ?: ""
            val encodedStreamDescription = streamDescription?.let { encodeUrlArg(it) } ?: ""
            val encodedInfoHash = infoHash?.let { encodeUrlArg(it) } ?: ""
            val encodedSources = sources?.let { encodeUrlArg(org.json.JSONArray(it).toString()) } ?: ""
            val encodedContentLanguage = contentLanguage?.let { encodeUrlArg(it) } ?: ""
            val encodedSubtitles = subtitles.takeIf { it.isNotEmpty() }
                ?.let { encodeUrlArg(com.nuvio.tv.core.player.encodeStreamSubtitlesArg(it)) } ?: ""
            return "player/$encodedUrl/$encodedTitle?streamName=$encodedStreamName&year=$encodedYear&headers=$encodedHeaders&contentId=$encodedContentId&contentType=$encodedContentType&contentName=$encodedContentName&poster=$encodedPoster&backdrop=$encodedBackdrop&logo=$encodedLogo&videoId=$encodedVideoId&season=${season ?: ""}&episode=${episode ?: ""}&episodeTitle=$encodedEpisodeTitle&bingeGroup=$encodedBingeGroup&autoPlayNav=$autoPlayNav&returnToDetailOnBack=$returnToDetailOnBack&returnToHomeOnBack=$returnToHomeOnBack&filename=$encodedFilename&videoHash=$encodedVideoHash&videoSize=${videoSize ?: ""}&startFromBeginning=$startFromBeginning&addonName=$encodedAddonName&addonLogo=$encodedAddonLogo&streamDescription=$encodedStreamDescription&infoHash=$encodedInfoHash&fileIdx=${fileIdx ?: ""}&sources=$encodedSources&contentLanguage=$encodedContentLanguage&runtimeMinutes=${runtimeMinutes ?: ""}&subtitles=$encodedSubtitles&launchStartedAtMs=$launchStartedAtMs"
        }
    }
    data object Search : Screen("search") {
        /**
         * Search pushed on top of another screen with [createRoute]'s query prefilled and run.
         * A separate destination so the root "search" route stays argument-free for the
         * top bar (the root route shows the bar; this pushed one does not).
         */
        /** Nav arg name of [QUERY_ROUTE]; SearchViewModel reads it from SavedStateHandle. */
        const val QUERY_ARG = "query"
        const val QUERY_ROUTE = "search/{$QUERY_ARG}"

        fun createRoute(query: String): String = "search/${encodeUrlArg(query)}"
    }
    data object Discover : Screen("discover") {
        /**
         * Discover pushed from Detail's About sheet (Spec 26 R3): [createRoute]'s type and genre
         * pick that type's first catalog listing the genre, the genre selected. A separate
         * destination, as Search's QUERY_ROUTE, so the root route stays argument-free.
         */
        /** Nav arg names of [GENRE_ROUTE]; DiscoverViewModel reads them from SavedStateHandle. */
        const val TYPE_ARG = "type"
        const val GENRE_ARG = "genre"
        const val GENRE_ROUTE = "discover/{$TYPE_ARG}/{$GENRE_ARG}"

        fun createRoute(type: String, genre: String): String =
            "discover/${encodeUrlArg(type)}/${encodeUrlArg(genre)}"
    }
    data object LiveTv : Screen("live_tv")
    /** Live TV › Sources (Spec 34 L7e); [ADD_ARG] true opens the Add source dialog on entry. */
    data object LiveTvSources : Screen("live_tv_sources?add={add}") {
        const val ADD_ARG = "add"

        fun createRoute(add: Boolean): String = "live_tv_sources?add=$add"
    }
    data object Library : Screen("library")
    data object Settings : Screen("settings") {
        /**
         * SavedStateHandle key of a one-shot deep entry (Spec 33 D5, SettingsEntry name): set on
         * the Settings back-stack entry by openSettingsAt, cleared by SettingsScreen once focus
         * lands. Not a route argument: every surface matches the bare "settings" route.
         */
        const val ENTRY_KEY = "settings_entry"
    }
    data object AddonManager : Screen("addon_manager")
    data object CloudStreamManager : Screen("cloudstream_manager")
    data object CatalogOrder : Screen("catalog_order")
    data object ManageProfiles : Screen("manage_profiles")
    data object AuthQrSignIn : Screen("auth_qr_sign_in")
    data object DpadComboSetup : Screen("dpad_combo_setup?mode={mode}") {
        fun createRoute(mode: String): String = "dpad_combo_setup?mode=$mode"
    }
    data object CatalogSeeAll : Screen("catalog_see_all/{catalogId}/{addonId}/{type}?fromSearch={fromSearch}") {

        fun createRoute(catalogId: String, addonId: String, type: String, fromSearch: Boolean = false): String {
            return "catalog_see_all/${encodeUrlArg(catalogId)}/${encodeUrlArg(addonId)}/${encodeUrlArg(type)}?fromSearch=$fromSearch"
        }
    }

    data object Collections : Screen("collections")

    data object CollectionEditor : Screen("collection_editor?collectionId={collectionId}") {
        fun createRoute(collectionId: String? = null): String {
            return "collection_editor?collectionId=${collectionId?.let { encodeUrlArg(it) } ?: ""}"
        }
    }

    data object FolderDetail : Screen("folder_detail/{collectionId}/{folderId}") {

        fun createRoute(collectionId: String, folderId: String): String {
            return "folder_detail/${encodeUrlArg(collectionId)}/${encodeUrlArg(folderId)}"
        }
    }

    /**
     * Person page. [department]: the TMDB department whose tab opens first (blank = most credits);
     * [sourceTitle] + [sectionLabel]: the breadcrumb back to where it was opened ("Title › Cast").
     */
    data object CastDetail : Screen(
        "cast_detail/{personId}/{personName}?department={department}&sourceTitle={sourceTitle}&sectionLabel={sectionLabel}"
    ) {

        fun createRoute(
            personId: Int,
            personName: String,
            department: String? = null,
            sourceTitle: String? = null,
            sectionLabel: String? = null
        ): String {
            return "cast_detail/$personId/${encodeUrlArg(personName)}" +
                "?department=${optionalArg(department)}" +
                "&sourceTitle=${optionalArg(sourceTitle)}&sectionLabel=${optionalArg(sectionLabel)}"
        }
    }

    /** Studio / network page; [sourceTitle] + [sectionLabel] as on [CastDetail] ("Title › Studios"). */
    data object TmdbEntityBrowse : Screen(
        "tmdb_entity_browse/{entityKind}/{entityId}/{entityName}?sourceType={sourceType}" +
            "&sourceTitle={sourceTitle}&sectionLabel={sectionLabel}"
    ) {

        fun createRoute(
            entityKind: String,
            entityId: Int,
            entityName: String,
            sourceType: String,
            sourceTitle: String? = null,
            sectionLabel: String? = null
        ): String {
            return "tmdb_entity_browse/${encodeUrlArg(entityKind)}/$entityId/${encodeUrlArg(entityName)}" +
                "?sourceType=${encodeUrlArg(sourceType)}" +
                "&sourceTitle=${optionalArg(sourceTitle)}&sectionLabel=${optionalArg(sectionLabel)}"
        }
    }
}

/** An optional query value: encoded, or empty when absent (the screen reads blank as absent). */
private fun optionalArg(value: String?): String = value?.let { encodeUrlArg(it) } ?: ""
