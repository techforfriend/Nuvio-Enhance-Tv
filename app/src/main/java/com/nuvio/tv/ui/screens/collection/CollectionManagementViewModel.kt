package com.nuvio.tv.ui.screens.collection

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.ValidationResult
import com.nuvio.tv.domain.model.Collection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.IOException
import javax.inject.Inject

enum class ImportMode { FILE, URL }

data class CollectionManagementUiState(
    val collections: List<Collection> = emptyList(),
    val isLoading: Boolean = true,
    val showImportDialog: Boolean = false,
    val importText: String = "",
    val importError: String? = null,
    val importMode: ImportMode = ImportMode.FILE,
    val importUrl: String = "",
    val validationResult: ValidationResult? = null,
    val validatedJson: String? = null,
    val isLoadingImport: Boolean = false
)

@HiltViewModel
class CollectionManagementViewModel @Inject constructor(
    private val collectionsDataStore: CollectionsDataStore,
    private val collectionSyncService: CollectionSyncService,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionManagementUiState())
    val uiState: StateFlow<CollectionManagementUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            collectionsDataStore.collections.collectLatest { collections ->
                _uiState.update {
                    it.copy(collections = collections, isLoading = false)
                }
            }
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch {
            collectionsDataStore.removeCollection(collectionId)
            collectionSyncService.triggerPush()
        }
    }

    fun moveUp(collectionId: String) = moveCollection(collectionId, offset = -1)

    fun moveDown(collectionId: String) = moveCollection(collectionId, offset = 1)

    // By id against the stored list inside the DataStore edit (L4-23/85), so a rapid second
    // press, a just-deleted row or a sync pull landing in between is never overwritten. An edge
    // press (every row shows both moves, Spec 28 stage 6) writes nothing and pushes nothing.
    private fun moveCollection(collectionId: String, offset: Int) {
        viewModelScope.launch {
            if (collectionsDataStore.moveCollection(collectionId, offset)) collectionSyncService.triggerPush()
        }
    }

    // The one file or URL load in flight. A second press while it runs is ignored; a mode switch
    // or closing the panel cancels it, so a late result never lands in the other mode or a
    // reopened panel.
    private var importJob: Job? = null

    fun showImportDialog() = resetImport(showDialog = true)

    fun hideImportDialog() = resetImport(showDialog = false)

    private fun resetImport(showDialog: Boolean) {
        importJob?.cancel()
        _uiState.update {
            it.copy(
                showImportDialog = showDialog, importText = "", importError = null,
                importMode = ImportMode.FILE, importUrl = "",
                validationResult = null, validatedJson = null, isLoadingImport = false
            )
        }
    }

    fun setImportMode(mode: ImportMode) {
        importJob?.cancel()
        _uiState.update {
            it.copy(
                importMode = mode, importError = null, validationResult = null, validatedJson = null,
                isLoadingImport = false
            )
        }
    }

    fun updateImportUrl(url: String) {
        _uiState.update {
            it.copy(importUrl = url, importError = null, validationResult = null, validatedJson = null)
        }
    }

    fun validateJson(json: String) {
        val result = collectionsDataStore.validateCollectionsJson(json)
        _uiState.update {
            if (result.valid) {
                it.copy(validationResult = result, validatedJson = json, importError = null)
            } else {
                it.copy(validationResult = null, validatedJson = null, importError = result.error)
            }
        }
    }

    private val importClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .dns(com.nuvio.tv.core.network.dns.AppDns)
            // W2.1 parity — a user-supplied URL with no bounds reproduces the
            // indefinite-spinner class; the whole call is capped incl. DNS.
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    fun fetchUrl() {
        if (importJob?.isActive == true) return
        val url = _uiState.value.importUrl.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(importError = context.getString(R.string.web_import_enter_url)) }
            return
        }
        _uiState.update { it.copy(isLoadingImport = true, importError = null) }
        importJob = viewModelScope.launch {
            try {
                val request = okhttp3.Request.Builder().url(url).build()
                // Body (null on a non-2xx) and code. State is written only after withContext
                // returns, which throws if the load was cancelled meanwhile.
                val (body, code) = withContext(Dispatchers.IO) {
                    // Reused client (a fresh one leaks connection pools/threads per
                    // fetch); .use{} closes the body on every path, including the
                    // non-2xx early return and exceptions.
                    importClient.newCall(request).execute().use { response ->
                        val text = if (response.isSuccessful) response.body.cappedString(MAX_IMPORT_BYTES) else null
                        text to response.code
                    }
                }
                if (body == null) {
                    _uiState.update {
                        it.copy(
                            isLoadingImport = false,
                            importError = context.getString(R.string.collections_import_failed_fetch_http, code)
                        )
                    }
                    return@launch
                }
                _uiState.update { it.copy(importText = body, isLoadingImport = false) }
                validateJson(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingImport = false,
                        importError = context.getString(
                            R.string.collections_import_failed_fetch_url,
                            e.message ?: context.getString(R.string.error_unknown)
                        )
                    )
                }
            }
        }
    }

    fun confirmImport() {
        val json = _uiState.value.validatedJson ?: _uiState.value.importText.trim()
        if (json.isBlank()) {
            _uiState.update { it.copy(importError = context.getString(R.string.collections_import_no_data)) }
            return
        }
        val imported = collectionsDataStore.importFromJson(json)
        if (imported.isEmpty()) {
            _uiState.update { it.copy(importError = context.getString(R.string.collections_import_invalid_or_empty)) }
            return
        }
        viewModelScope.launch {
            // Merged against the stored list, not the UI snapshot (L4-85).
            collectionsDataStore.upsertCollections(imported)
            collectionSyncService.triggerPush()
            resetImport(showDialog = false)
        }
    }

    // activityContext (not the injected app context) carries the in-app locale override
    // applied in MainActivity.attachBaseContext, so these two errors keep its language.
    fun loadFromFile(activityContext: Context) {
        if (importJob?.isActive == true) return
        _uiState.update { it.copy(isLoadingImport = true, importError = null) }
        importJob = viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        loadFromMediaStoreDownloads(activityContext)
                    } else {
                        loadFromDownloadsDirectory()
                    }
                }
                if (content == null) {
                    _uiState.update {
                        it.copy(
                            isLoadingImport = false,
                            importError = activityContext.getString(R.string.collections_import_file_not_found_downloads)
                        )
                    }
                    return@launch
                }
                _uiState.update { it.copy(importText = content, isLoadingImport = false) }
                validateJson(content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingImport = false,
                        importError = activityContext.getString(
                            R.string.collections_import_failed_read_file,
                            e.message ?: activityContext.getString(R.string.error_unknown)
                        )
                    )
                }
            }
        }
    }

    fun getExportJson(): String {
        return collectionsDataStore.exportToJson(_uiState.value.collections)
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun loadFromMediaStoreDownloads(activityContext: Context): String? {
        val resolver = activityContext.contentResolver
        val uri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(android.provider.MediaStore.Downloads._ID)
        val selection = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf("nuvio-collections.json")
        return resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID))
                val fileUri = android.content.ContentUris.withAppendedId(uri, id)
                resolver.openInputStream(fileUri)?.bufferedReader()?.readText()
            } else null
        }
    }

    private fun loadFromDownloadsDirectory(): String? {
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val file = java.io.File(downloadsDir, "nuvio-collections.json")
        return if (file.exists()) file.readText() else null
    }

    private companion object {
        /**
         * URL-import body cap (KT-JSON-03): a real collections export is tens of KB,
         * so 5 MB is ~100x headroom while a hostile or mistyped URL (a video, a huge
         * page) can no longer heap-buffer an unbounded body before validation.
         */
        const val MAX_IMPORT_BYTES = 5_000_000L
    }
}

/**
 * [ResponseBody.string] with a byte cap: buffers at most [maxBytes] + 1 bytes and throws
 * [IOException] past the cap; otherwise decodes exactly as [ResponseBody.string] does
 * (BOM / Content-Type charset, UTF-8 default) from the same, already-buffered source.
 */
internal fun ResponseBody.cappedString(maxBytes: Long): String {
    if (source().request(maxBytes + 1)) {
        throw IOException("Response exceeds ${maxBytes / 1_000_000}MB import limit")
    }
    return string()
}
