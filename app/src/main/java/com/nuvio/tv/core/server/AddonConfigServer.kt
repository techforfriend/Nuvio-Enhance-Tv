package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.core.util.booleanField
import com.nuvio.tv.core.util.stringElements
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * LAN server behind the addon / catalog / collection QR page ([AddonWebPage]). One instance is one
 * QR session: every route lives under `/a/<token>/` (Spec 68 X3), so only a phone that scanned this
 * session's QR reaches the addon URLs (bearer credentials) in /api/state.
 */
class AddonConfigServer(
    private val token: String,
    private val context: Context,
    private val webConfigMode: AddonWebConfigMode,
    private val currentPageStateProvider: () -> PageState,
    private val onChangeProposed: (PendingAddonChange) -> Unit,
    private val tmdbMetadataProvider: ((TmdbSourceMetadataRequest) -> TmdbSourceMetadataInfo?)? = null,
    private val tmdbSearchProvider: ((TmdbSourceSearchRequest) -> List<TmdbSourceSearchResultInfo>)? = null,
    private val logoProvider: (() -> ByteArray?)? = null,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val pendingChanges = PendingChangeStore<PendingAddonChange>()

    fun confirmChange(id: String) {
        pendingChanges[id]?.status = AddonChangeStatus.CONFIRMED
    }

    fun rejectChange(id: String) {
        pendingChanges[id]?.status = AddonChangeStatus.REJECTED
    }

    /** The QR address on this device's [ip]. */
    fun url(ip: String): String = "http://$ip:$listeningPort$PATH_PREFIX$token/"

    override fun serve(session: IHTTPSession): Response =
        route(session).withTokenPathHeaders(session.method)

    private fun route(session: IHTTPSession): Response {
        session.rejectUntrustedRequest()?.let { return it }
        val path = pathAfterToken(session.uri, PATH_PREFIX, token)
            ?: return session.notFoundOutsideSession()
        val method = session.method

        return when {
            method == Method.GET && path.isEmpty() -> serveWebPage()
            method == Method.GET && path == "logo.png" -> pngOrNotFound(logoProvider?.invoke())
            method == Method.GET && path == "api/state" -> servePageState()
            method == Method.GET && path == "api/addons" -> serveAddonList()
            method == Method.POST && path == "api/addons" -> handleAddonUpdate(session)
            method == Method.GET && path == "api/collections" -> serveCollections()
            method == Method.GET && path == "api/tmdb/metadata" -> serveTmdbMetadata(session)
            method == Method.GET && path == "api/tmdb/search" -> serveTmdbSearch(session)
            method == Method.GET && path.startsWith(STATUS_PATH) -> serveChangeStatus(path)
            else -> notFoundResponse()
        }
    }

    private fun serveWebPage(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            AddonWebPage.getHtml(context, webConfigMode)
        )
    }

    private fun serveCollections(): Response {
        val collections = currentPageStateProvider().collections
        return jsonResponse(Response.Status.OK, configServerJson.encodeToString(collections))
    }

    private fun serveAddonList(): Response {
        val addons = currentPageStateProvider().addons
        return jsonResponse(Response.Status.OK, configServerJson.encodeToString(addons))
    }

    private fun servePageState(): Response {
        val state = currentPageStateProvider()
        return jsonResponse(Response.Status.OK, configServerJson.encodeToString(state))
    }

    private fun serveTmdbMetadata(session: IHTTPSession): Response {
        val provider = tmdbMetadataProvider
            ?: return jsonResponse(Response.Status.NOT_FOUND, jsonMessage("error", "TMDB metadata unavailable"))
        val sourceType = session.parameters["sourceType"]?.firstOrNull()?.trim().orEmpty()
        val tmdbId = session.parameters["id"]?.firstOrNull()?.trim()?.toIntOrNull()
        if (sourceType.isBlank() || tmdbId == null) {
            return jsonResponse(Response.Status.BAD_REQUEST, jsonMessage("error", "Invalid TMDB metadata request"))
        }
        val metadata = runCatching {
            provider(TmdbSourceMetadataRequest(sourceType = sourceType, tmdbId = tmdbId))
        }.getOrNull()
        return if (metadata != null) {
            jsonResponse(Response.Status.OK, configServerJson.encodeToString(metadata))
        } else {
            jsonResponse(Response.Status.NOT_FOUND, jsonMessage("error", "TMDB source not found"))
        }
    }

    private fun serveTmdbSearch(session: IHTTPSession): Response {
        val provider = tmdbSearchProvider
            ?: return jsonResponse(Response.Status.NOT_FOUND, jsonMessage("error", "TMDB search unavailable"))
        val sourceType = session.parameters["sourceType"]?.firstOrNull()?.trim().orEmpty()
        val query = session.parameters["query"]?.firstOrNull()?.trim().orEmpty()
        if (sourceType.isBlank() || query.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, jsonMessage("error", "Invalid TMDB search request"))
        }
        val results = runCatching {
            provider(TmdbSourceSearchRequest(sourceType = sourceType, query = query))
        }.getOrElse { emptyList() }
        return jsonResponse(Response.Status.OK, configServerJson.encodeToString(results))
    }

    // Reject-then-insert is atomic inside put(): overlapping POSTs cannot leave
    // two PENDING records, and a malformed body (413/400 from withJsonBody) never
    // invalidates an earlier pending proposal.
    private fun handleAddonUpdate(session: IHTTPSession): Response = session.withJsonBody(
        invalidBodyMessage = { context.getString(com.nuvio.tv.R.string.web_error_invalid_request_body) }
    ) { parsed ->
        val currentState = currentPageStateProvider()
        val urls = sanitizeProposedAddonUrls(
            urls = parseStringList(parsed["urls"]),
            installedUrls = currentState.addons.map { it.url }.toSet()
        )
        val catalogOrderKeys = parseStringList(parsed["catalogOrderKeys"])
        val disabledCatalogKeys = parseStringList(parsed["disabledCatalogKeys"])
        // JSON null mirrors Gson's absent-field behavior: no collections
        // proposal. An explicit clear must send an empty array.
        val collectionsJson = parsed["collections"]?.takeIf { it !is JsonNull }?.toString()
        val disabledCollectionKeys = parseStringList(parsed["disabledCollectionKeys"])
        val followAddonsOrder = parsed.booleanField("followAddonsOrder")
        val change = sanitizePendingAddonChange(
            mode = webConfigMode,
            proposedChange = PendingAddonChange(
                proposedUrls = urls,
                proposedCatalogOrderKeys = catalogOrderKeys,
                proposedDisabledCatalogKeys = disabledCatalogKeys,
                proposedCollectionsJson = collectionsJson,
                proposedDisabledCollectionKeys = disabledCollectionKeys,
                proposedFollowAddonsOrder = followAddonsOrder
            ),
            currentState = currentState
        )

        pendingChanges.put(change.id, change) { record ->
            if (record.status == AddonChangeStatus.PENDING) {
                record.status = AddonChangeStatus.REJECTED
            }
        }
        onChangeProposed(change)

        val response = buildJsonObject {
            put("status", "pending_confirmation")
            put("id", change.id)
        }.toString()
        jsonResponse(Response.Status.OK, response)
    }

    private fun serveChangeStatus(path: String): Response {
        val id = path.removePrefix(STATUS_PATH)
        val change = pendingChanges[id]
        val status = change?.status?.name?.lowercase() ?: "not_found"
        return jsonResponse(Response.Status.OK, jsonMessage("status", status))
    }

    private fun parseStringList(rawValue: JsonElement?): List<String> {
        val values = rawValue as? JsonArray ?: return emptyList()
        return values.stringElements().asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    companion object {
        private const val PATH_PREFIX = "/a/"
        private const val STATUS_PATH = "api/status/"

        fun startOnAvailablePort(
            context: Context,
            webConfigMode: AddonWebConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider: () -> PageState,
            onChangeProposed: (PendingAddonChange) -> Unit,
            tmdbMetadataProvider: ((TmdbSourceMetadataRequest) -> TmdbSourceMetadataInfo?)? = null,
            tmdbSearchProvider: ((TmdbSourceSearchRequest) -> List<TmdbSourceSearchResultInfo>)? = null,
            logoProvider: (() -> ByteArray?)? = null,
            startPort: Int = 8080,
            maxAttempts: Int = 10
        ): AddonConfigServer? = startOnFirstAvailablePort(startPort, maxAttempts) { port ->
            AddonConfigServer(
                token = newSessionToken(),
                context = context,
                webConfigMode = webConfigMode,
                currentPageStateProvider = currentPageStateProvider,
                onChangeProposed = onChangeProposed,
                tmdbMetadataProvider = tmdbMetadataProvider,
                tmdbSearchProvider = tmdbSearchProvider,
                logoProvider = logoProvider,
                port = port
            )
        }
    }
}
