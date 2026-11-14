package com.nuvio.tv.core.tmdb

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ActiveRatingCap
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbCollectionSearchResult
import com.nuvio.tv.data.remote.api.TmdbCompanySearchResult
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbListItem
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCast
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCrew
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

data class TmdbSourceImportMetadata(
    val title: String? = null,
    val coverImageUrl: String? = null
)

@Singleton
class TmdbCollectionSourceResolver @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val tmdbApi: TmdbApi,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val apiKeys: TmdbApiKeySource,
    private val activeRatingCap: ActiveRatingCap
) {
    private fun string(resId: Int): String = appContext.getString(resId)

    /**
     * The key every call sends. With none at all it throws [TmdbNoKeyException] (Spec 52 T1) instead of a
     * request the no-key guard would answer with a 401 that reads as "TMDB list not found"; the collection
     * editor shows its message (the addon page's phone import just gets no metadata), [resolve] turns it
     * into [NetworkResult.NO_TMDB_KEY_CODE].
     */
    private suspend fun requireKey(): String =
        apiKeys.key().ifBlank { throw TmdbNoKeyException(string(R.string.tmdb_needs_key)) }

    fun resolve(source: TmdbCollectionSource, page: Int = 1): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)
        // Spec 64 D1/D5: TMDB titles carry no US rating here, so a capped profile gets an
        // empty last page and no TMDB request.
        if (activeRatingCap.current() != null) {
            emit(NetworkResult.Success(row(source, page, hasMore = false, items = emptyList())))
            return@flow
        }
        val row = try {
            withContext(Dispatchers.IO) {
                val language = tmdbSettingsDataStore.settings.first().language
                when (source.sourceType) {
                    TmdbCollectionSourceType.LIST -> resolveList(source, language, page)
                    TmdbCollectionSourceType.COLLECTION -> resolveCollection(source, language)
                    TmdbCollectionSourceType.PERSON,
                    TmdbCollectionSourceType.DIRECTOR -> resolvePersonCredits(source, language)
                    TmdbCollectionSourceType.COMPANY,
                    TmdbCollectionSourceType.NETWORK,
                    TmdbCollectionSourceType.DISCOVER -> resolveDiscover(source, language, page)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TmdbNoKeyException) {
            emit(NetworkResult.Error(e.message.orEmpty(), code = NetworkResult.NO_TMDB_KEY_CODE))
            return@flow
        } catch (e: Exception) {
            emit(NetworkResult.Error(e.message ?: string(R.string.tmdb_error_load_source)))
            return@flow
        }
        emit(NetworkResult.Success(row))
    }

    suspend fun searchCompanies(query: String): List<TmdbCompanySearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        tmdbApi.searchCompanies(requireKey(), query.trim()).body()?.results.orEmpty()
    }

    suspend fun searchCollections(query: String): List<TmdbCollectionSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val language = tmdbSettingsDataStore.settings.first().language
        tmdbApi.searchCollections(requireKey(), query.trim(), language).body()?.results.orEmpty()
    }

    suspend fun listImportMetadata(id: Int): TmdbSourceImportMetadata = withContext(Dispatchers.IO) {
        val language = tmdbSettingsDataStore.settings.first().language
        val body = tmdbApi.getListDetails(id, requireKey(), language, 1).body()
            ?: error(string(R.string.tmdb_error_list_not_found))
        TmdbSourceImportMetadata(
            title = body.name?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun collectionImportMetadata(id: Int): TmdbSourceImportMetadata = withContext(Dispatchers.IO) {
        val language = tmdbSettingsDataStore.settings.first().language
        val body = tmdbApi.getCollectionDetails(id, requireKey(), language).body()
            ?: error(string(R.string.tmdb_error_collection_not_found))
        TmdbSourceImportMetadata(
            title = body.name?.takeIf { it.isNotBlank() },
            coverImageUrl = tmdbImageUrl(body.posterPath, "w500") ?: tmdbImageUrl(body.backdropPath, TmdbImageSizes.backdrop)
        )
    }

    suspend fun companyImportMetadata(id: Int): TmdbSourceImportMetadata = withContext(Dispatchers.IO) {
        val body = tmdbApi.getCompanyDetails(id, requireKey()).body()
            ?: error(string(R.string.tmdb_error_company_not_found))
        TmdbSourceImportMetadata(
            title = body.name?.takeIf { it.isNotBlank() },
            coverImageUrl = tmdbImageUrl(body.logoPath, "w500")
        )
    }

    suspend fun networkImportMetadata(id: Int): TmdbSourceImportMetadata = withContext(Dispatchers.IO) {
        val body = tmdbApi.getNetworkDetails(id, requireKey()).body()
            ?: error(string(R.string.tmdb_error_network_not_found))
        TmdbSourceImportMetadata(
            title = body.name?.takeIf { it.isNotBlank() },
            coverImageUrl = tmdbImageUrl(body.logoPath, "w500")
        )
    }

    suspend fun personImportMetadata(id: Int): TmdbSourceImportMetadata = withContext(Dispatchers.IO) {
        val language = tmdbSettingsDataStore.settings.first().language
        val body = tmdbApi.getPersonDetails(id, requireKey(), language).body()
            ?: error(string(R.string.tmdb_error_person_not_found))
        TmdbSourceImportMetadata(
            title = body.name?.takeIf { it.isNotBlank() },
            coverImageUrl = tmdbImageUrl(body.profilePath, "w500")
        )
    }

    fun parseTmdbId(input: String): Int? {
        val trimmed = input.trim()
        trimmed.toIntOrNull()?.let { return it }
        return Regex("""(?:list|collection|company|network|person)/(\d+)""")
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""[?&]id=(\d+)""")
                .find(trimmed)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private suspend fun resolveList(source: TmdbCollectionSource, language: String, page: Int): CatalogRow {
        val id = source.tmdbId ?: error(string(R.string.tmdb_error_missing_list_id))
        val body = tmdbApi.getListDetails(id, requireKey(), language, page).body()
            ?: error(string(R.string.tmdb_error_list_not_found))
        val items = body.items.orEmpty()
            .mapNotNull { it.toPreview() }
            .sortedFor(source.sortBy)
            .distinctBy { "${it.apiType}:${it.id}" }
        return row(
            source = source.copy(title = source.title.ifBlank { body.name ?: string(R.string.collections_editor_tmdb_default_list) }),
            page = body.page ?: page,
            hasMore = (body.page ?: page) < (body.totalPages ?: page),
            items = items
        )
    }

    private suspend fun resolveCollection(source: TmdbCollectionSource, language: String): CatalogRow {
        val id = source.tmdbId ?: error(string(R.string.tmdb_error_missing_collection_id))
        val body = tmdbApi.getCollectionDetails(id, requireKey(), language).body()
            ?: error(string(R.string.tmdb_error_collection_not_found))
        val items = body.parts.orEmpty()
            .mapNotNull {
                val title = it.title?.takeIf { value -> value.isNotBlank() } ?: return@mapNotNull null
                tmdbPreview(
                    id = it.id, type = ContentType.MOVIE, title = title,
                    posterPath = it.posterPath, backdropPath = it.backdropPath, overview = it.overview,
                    date = it.releaseDate, voteAverage = it.voteAverage, voteCount = it.voteCount
                )
            }
            .sortedFor(source.sortBy)
        return row(
            source = source.copy(title = source.title.ifBlank { body.name ?: string(R.string.collections_editor_tmdb_collection) }),
            page = 1,
            hasMore = false,
            items = items
        )
    }

    private suspend fun resolvePersonCredits(source: TmdbCollectionSource, language: String): CatalogRow {
        val id = source.tmdbId ?: error(string(R.string.tmdb_error_missing_person_id))
        val body = tmdbApi.getPersonCombinedCredits(id, requireKey(), language).body()
            ?: error(string(R.string.tmdb_error_person_credits_not_found))
        val items = when (source.sourceType) {
            TmdbCollectionSourceType.DIRECTOR -> body.crew.orEmpty()
                .filter { it.job.equals("Director", ignoreCase = true) }
                .mapNotNull { it.toPreview(source.mediaType) }
            else -> body.cast.orEmpty().mapNotNull { it.toPreview(source.mediaType) }
        }
            .distinctBy { "${it.apiType}:${it.id}" }
            .sortedFor(source.sortBy)
        return row(
            source = source,
            page = 1,
            hasMore = false,
            items = items
        )
    }

    private suspend fun resolveDiscover(source: TmdbCollectionSource, language: String, page: Int): CatalogRow {
        val mediaType = if (source.sourceType == TmdbCollectionSourceType.NETWORK) {
            TmdbCollectionMediaType.TV
        } else {
            source.mediaType
        }
        val filters = source.filters
        val today = LocalDate.now().toString()
        val response = when (mediaType) {
            TmdbCollectionMediaType.MOVIE -> tmdbApi.discoverMovies(
                apiKey = requireKey(),
                language = language,
                page = page,
                sortBy = movieSort(source.sortBy),
                withCompanies = when (source.sourceType) {
                    TmdbCollectionSourceType.COMPANY -> source.tmdbId?.toString()
                    else -> filters.withCompanies
                },
                releaseDateLte = filters.releaseDateLte,
                voteCountGte = filters.voteCountGte,
                withGenres = filters.withGenres,
                releaseDateGte = filters.releaseDateGte,
                voteAverageGte = filters.voteAverageGte,
                voteAverageLte = filters.voteAverageLte,
                withOriginalLanguage = filters.withOriginalLanguage,
                withOriginCountry = filters.withOriginCountry,
                withKeywords = filters.withKeywords,
                withoutKeywords = filters.withoutKeywords,
                year = filters.year,
                watchRegion = if (!filters.withWatchProviders.isNullOrBlank() || !filters.withoutWatchProviders.isNullOrBlank()) {
                    filters.watchRegion?.takeIf { it.isNotBlank() } ?: "US"
                } else null,
                withWatchProviders = filters.withWatchProviders,
                withWatchMonetizationTypes = if (!filters.withWatchProviders.isNullOrBlank()) "flatrate|free|ads|rent|buy" else null,
                withoutCompanies = filters.withoutCompanies,
                withoutGenres = filters.withoutGenres,
                withoutWatchProviders = filters.withoutWatchProviders
            ).body()
            TmdbCollectionMediaType.TV -> tmdbApi.discoverTv(
                apiKey = requireKey(),
                language = language,
                page = page,
                sortBy = tvSort(source.sortBy),
                withCompanies = when (source.sourceType) {
                    TmdbCollectionSourceType.COMPANY -> source.tmdbId?.toString()
                    else -> filters.withCompanies
                },
                withNetworks = when (source.sourceType) {
                    TmdbCollectionSourceType.NETWORK -> source.tmdbId?.toString()
                    else -> filters.withNetworks
                },
                firstAirDateLte = filters.releaseDateLte ?: if (source.sourceType == TmdbCollectionSourceType.NETWORK) today else null,
                withStatus = if (source.sourceType == TmdbCollectionSourceType.NETWORK) "0|3|4" else null,
                voteCountGte = filters.voteCountGte,
                withGenres = filters.withGenres,
                firstAirDateGte = filters.releaseDateGte,
                voteAverageGte = filters.voteAverageGte,
                voteAverageLte = filters.voteAverageLte,
                withOriginalLanguage = filters.withOriginalLanguage,
                withOriginCountry = filters.withOriginCountry,
                withKeywords = filters.withKeywords,
                withoutKeywords = filters.withoutKeywords,
                firstAirDateYear = filters.year,
                watchRegion = if (!filters.withWatchProviders.isNullOrBlank() || !filters.withoutWatchProviders.isNullOrBlank()) {
                    filters.watchRegion?.takeIf { it.isNotBlank() } ?: "US"
                } else null,
                withWatchProviders = filters.withWatchProviders,
                withWatchMonetizationTypes = if (!filters.withWatchProviders.isNullOrBlank()) "flatrate|free|ads|rent|buy" else null,
                withoutCompanies = filters.withoutCompanies,
                withoutGenres = filters.withoutGenres,
                withoutWatchProviders = filters.withoutWatchProviders
            ).body()
        } ?: error(string(R.string.tmdb_error_discover_no_data))
        val items = response.results.orEmpty().mapNotNull { it.toPreview(mediaType) }.distinctBy { it.id }
        return row(
            source = source.copy(mediaType = mediaType),
            page = response.page ?: page,
            hasMore = (response.page ?: page) < (response.totalPages ?: page) && items.isNotEmpty(),
            items = items
        )
    }

    private fun row(source: TmdbCollectionSource, page: Int, hasMore: Boolean, items: List<MetaPreview>): CatalogRow {
        val mediaType = if (source.mediaType == TmdbCollectionMediaType.TV) "series" else source.mediaType.value
        return CatalogRow(
            addonId = "tmdb",
            addonName = "TMDB",
            addonBaseUrl = "",
            catalogId = source.rowCatalogId(),
            catalogName = source.title,
            type = ContentType.fromString(mediaType),
            rawType = mediaType,
            items = items,
            isLoading = false,
            hasMore = hasMore,
            currentPage = page,
            supportsSkip = hasMore,
            skipStep = 20
        )
    }

    private fun List<MetaPreview>.sortedFor(sortBy: String): List<MetaPreview> {
        return when (sortBy) {
            TmdbCollectionSort.ORIGINAL.value -> this
            TmdbCollectionSort.VOTE_AVERAGE_DESC.value -> sortedWith(
                compareByDescending<MetaPreview> { it.tmdbRating ?: -1f }
                    .thenByDescending { it.releaseInfo ?: "" }
            )
            TmdbCollectionSort.VOTE_COUNT_DESC.value -> sortedWith(
                compareByDescending<MetaPreview> { it.voteCount ?: -1 }
                    .thenByDescending { it.tmdbRating ?: -1f }
            )
            TmdbCollectionSort.RELEASE_DATE_DESC.value,
            TmdbCollectionSort.FIRST_AIR_DATE_DESC.value -> sortedByDescending { it.releaseInfo ?: "" }
            TmdbCollectionSort.POPULAR_DESC.value -> this
            else -> this
        }
    }

    private fun TmdbListItem.toPreview(): MetaPreview? {
        val media = mediaType?.lowercase(Locale.US)
        val contentType = when (media) {
            "tv" -> ContentType.SERIES
            else -> ContentType.MOVIE
        }
        val title = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null
        return tmdbPreview(
            id = id, type = contentType, title = title,
            posterPath = posterPath, backdropPath = backdropPath, overview = overview,
            date = releaseDate ?: firstAirDate, voteAverage = voteAverage, voteCount = voteCount
        )
    }

    private fun TmdbDiscoverResult.toPreview(mediaType: TmdbCollectionMediaType): MetaPreview? {
        val title = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: originalTitle?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: return null
        return tmdbPreview(
            id = id, type = mediaType.contentType(), title = title,
            posterPath = posterPath, backdropPath = backdropPath, overview = overview,
            date = if (mediaType == TmdbCollectionMediaType.TV) firstAirDate else releaseDate,
            voteAverage = voteAverage, voteCount = voteCount
        )
    }

    private fun TmdbPersonCreditCast.toPreview(mediaType: TmdbCollectionMediaType): MetaPreview? {
        if (!matchesMediaType(mediaType, this.mediaType)) return null
        val title = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: return null
        return tmdbPreview(
            id = id, type = mediaType.contentType(), title = title,
            posterPath = posterPath, backdropPath = backdropPath, overview = overview,
            date = if (mediaType == TmdbCollectionMediaType.TV) firstAirDate else releaseDate,
            voteAverage = voteAverage, voteCount = voteCount
        )
    }

    private fun TmdbPersonCreditCrew.toPreview(mediaType: TmdbCollectionMediaType): MetaPreview? {
        if (!matchesMediaType(mediaType, this.mediaType)) return null
        val title = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: return null
        return tmdbPreview(
            id = id, type = mediaType.contentType(), title = title,
            posterPath = posterPath, backdropPath = backdropPath, overview = overview,
            date = if (mediaType == TmdbCollectionMediaType.TV) firstAirDate else releaseDate,
            voteAverage = voteAverage, voteCount = voteCount
        )
    }

    private fun TmdbCollectionMediaType.contentType(): ContentType =
        if (this == TmdbCollectionMediaType.TV) ContentType.SERIES else ContentType.MOVIE

    /** The preview every TMDB source maps to; callers pick the title chain and the date field. */
    private fun tmdbPreview(
        id: Int,
        type: ContentType,
        title: String,
        posterPath: String?,
        backdropPath: String?,
        overview: String?,
        date: String?,
        voteAverage: Double?,
        voteCount: Int?
    ): MetaPreview = MetaPreview(
        id = "tmdb:$id",
        type = type,
        rawType = if (type == ContentType.SERIES) "series" else "movie",
        name = title,
        poster = tmdbImageUrl(posterPath, "w500") ?: tmdbImageUrl(backdropPath, "w780"),
        posterShape = PosterShape.POSTER,
        background = tmdbImageUrl(backdropPath, TmdbImageSizes.backdrop),
        logo = null,
        description = overview?.takeIf { it.isNotBlank() },
        releaseInfo = date?.take(4),
        released = date?.takeIf { it.isNotBlank() },
        imdbRating = null,
        tmdbRating = voteAverage?.toFloat(),
        voteCount = voteCount,
        genres = emptyList()
    )

    private fun matchesMediaType(expected: TmdbCollectionMediaType, actual: String?): Boolean {
        return when (expected) {
            TmdbCollectionMediaType.MOVIE -> actual == "movie"
            TmdbCollectionMediaType.TV -> actual == "tv"
        }
    }

    private fun movieSort(sortBy: String): String {
        return when (sortBy) {
            "first_air_date.desc" -> "primary_release_date.desc"
            TmdbCollectionSort.VOTE_COUNT_DESC.value -> TmdbCollectionSort.VOTE_COUNT_DESC.value
            else -> sortBy.ifBlank { "popularity.desc" }
        }
    }

    private fun tvSort(sortBy: String): String {
        return when (sortBy) {
            "primary_release_date.desc" -> "first_air_date.desc"
            TmdbCollectionSort.VOTE_COUNT_DESC.value -> TmdbCollectionSort.VOTE_COUNT_DESC.value
            else -> sortBy.ifBlank { "popularity.desc" }
        }
    }
}

/**
 * The catalogId of the row resolved for this source. FolderDetail's loading shimmer row
 * uses it too, so the shimmer and the loaded row share a key.
 */
internal fun TmdbCollectionSource.rowCatalogId(): String {
    return buildString {
        append("tmdb_")
        append(sourceType.name.lowercase(Locale.US))
        tmdbId?.let {
            append("_")
            append(it)
        }
        append("_")
        append(mediaType.value)
        append("_")
        append(sortBy.replace('.', '_'))
        filters.hashCode().takeIf { sourceType == TmdbCollectionSourceType.DISCOVER }?.let {
            append("_")
            append(it.toUInt().toString(16))
        }
    }
}
