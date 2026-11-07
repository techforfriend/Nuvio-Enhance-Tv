package com.nuvio.tv.core.cloudstream

/**
 * One loaded CloudStream provider: the MainAPI surface the TV app drives.
 *
 * Mirrors Enhanced-2's `commonMain` CloudStreamProvider interface. A provider belongs to
 * one installed plugin ([id] is the plugin id, `repoUrl#internalName`); the [data] strings
 * it returns are opaque routes that only that provider can resolve again — see
 * [CloudStreamDexRuntime] for the route encoding.
 */
interface CloudStreamProvider {
    val id: String
    suspend fun getMainPage(page: Int): List<Pair<String, List<CloudStreamSearchItem>>>
    suspend fun search(query: String): List<CloudStreamSearchItem>
    suspend fun loadByExternalId(externalId: String): CloudStreamLoadItem?
    suspend fun load(data: String): CloudStreamLoadItem
    suspend fun loadLinks(data: String): List<CloudStreamPlaybackSource>
}
