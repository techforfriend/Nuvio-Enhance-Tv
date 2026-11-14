package com.nuvio.tv.core.tip

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.update.TipRecord
import com.nuvio.tv.core.update.UpdateStore
import com.nuvio.tv.core.update.autoCheckDue
import com.nuvio.tv.data.remote.api.GitHubFileApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The file Arshad edits on the public repo's default branch (D5). */
internal const val TIP_FILE_PATH = "tip.json"

/**
 * Where the tip links come from (Spec 81 D5; every build, D10): [owner]/[repo] = the public repo
 * holding `tip.json` (the update check's); [builtIn] = the link a build carries for when no file has
 * answered (blank or invalid = none).
 */
data class TipSource(val owner: String, val repo: String, val builtIn: String)

/** What one fetch of `tip.json` learned. */
internal sealed interface TipFetch {
    /** 200 with a usable file (its link may be null: tips paused). */
    data class Answered(val links: TipLinks) : TipFetch

    /** 404: no file (any more): the built-in link applies. */
    data object NoFile : TipFetch

    /** Any other status, a body over the cap, or a file [parseTipFile] refuses: the last good answer stays. */
    data object Unusable : TipFetch

    /** No HTTP answer (no network, DNS, timeout, a cut body): nothing is recorded, the next start tries again. */
    data object Unreached : TipFetch
}

/**
 * Spec 81 T1: the link the tip card's QR carries, or null (Tip hidden, D4). At app start
 * ([refreshIfDue]) it reads the stored answer, then, at most once a day ([autoCheckDue]), fetches
 * `tip.json`: a usable answer replaces the stored one, a 404 drops it (the built-in link applies), an
 * unusable answer keeps it, no answer records nothing. The file is untrusted remote input: capped at
 * [TIP_FILE_MAX_BYTES], every link checked by [validTipLink]. [country] picks a per-country override.
 */
@Singleton
class TipLinkRepository internal constructor(
    private val api: GitHubFileApi,
    private val store: UpdateStore,
    private val source: TipSource,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val country: () -> String
) {
    /** D10: every build has Tip; the file lives where the update check reads releases. */
    @Inject constructor(api: GitHubFileApi, store: UpdateStore) : this(
        api, store,
        TipSource(BuildConfig.UPDATE_OWNER, BuildConfig.UPDATE_REPO, BUILT_IN_TIP_LINK),
        CoroutineScope(SupervisorJob() + Dispatchers.IO), System::currentTimeMillis,
        ::tvCountry
    )

    private val builtIn = TipLinks(link = source.builtIn.takeIf(::validTipLink))
    private val refreshing = Mutex()
    private val _link = MutableStateFlow<String?>(null)

    /** The QR's link for this TV; null until the stored answer is read, and whenever Tip is hidden. */
    val link: StateFlow<String?> = _link.asStateFlow()

    /** App start: see the class. Returns the job (tests join it). */
    fun refreshIfDue(): Job =
        scope.launch {
            refreshing.withLock {
                // This process's own record wins over the store: a write that failed must not let a
                // later refresh (an activity recreate) bring back the older stored answer.
                // A store that cannot be read shows the fallback and is read again next time.
                val record = memory ?: try {
                    store.tipRecord().also { memory = it }
                } catch (_: IOException) {
                    TipRecord()
                }
                publish(record.links)
                if (!autoCheckDue(nowMs(), record.attemptMs)) return@withLock
                val now = nowMs()
                when (val fetch = fetch()) {
                    is TipFetch.Answered -> adopt(TipRecord(fetch.links, now)) { store.recordTipAnswer(fetch.links, now) }
                    TipFetch.NoFile -> adopt(TipRecord(null, now)) { store.recordTipAnswer(null, now) }
                    TipFetch.Unusable -> adopt(record.copy(attemptMs = now)) {
                        store.recordTipAnswer(null, now, keepLinks = true)
                    }
                    TipFetch.Unreached -> Unit
                }
            }
        }

    /** The record as of this run (guarded by [refreshing]); null until the store is first read. */
    private var memory: TipRecord? = null

    /**
     * [record] becomes this run's answer and is shown, then written. A store that cannot be written
     * keeps it in memory for this run; the next start fetches again.
     */
    private suspend fun adopt(record: TipRecord, write: suspend () -> Unit) {
        memory = record
        publish(record.links)
        try {
            write()
        } catch (e: IOException) {
            Log.w(TAG, "tip record not written: ${e.javaClass.simpleName}")
        }
    }

    private fun publish(stored: TipLinks?) {
        _link.value = effectiveTipLink(stored ?: builtIn, country())
    }

    internal suspend fun fetch(): TipFetch = try {
        val response = api.rawFile(source.owner, source.repo, TIP_FILE_PATH)
        val body = response.body()
        try {
            when {
                response.code() == 200 && body != null ->
                    readCapped(body)?.let(::parseTipFile)?.let { TipFetch.Answered(it) } ?: TipFetch.Unusable
                response.code() == 404 -> TipFetch.NoFile
                else -> TipFetch.Unusable
            }
        } finally {
            body?.close()
            response.errorBody()?.close()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        TipFetch.Unreached
    } catch (e: Exception) {
        // A converter or protocol failure: an answer, but not a usable one. Never logs the body (KT-SEC-02).
        Log.w(TAG, "tip file unusable: ${e.javaClass.simpleName}")
        TipFetch.Unusable
    }

    private companion object {
        const val TAG = "TipLink"
    }
}

/** [body] as text when it is at most [TIP_FILE_MAX_BYTES], else null; never reads past the cap (KT-JSON-03). */
internal fun readCapped(body: ResponseBody): String? {
    if (body.contentLength() > TIP_FILE_MAX_BYTES) return null
    val buffer = Buffer()
    val source = body.source()
    while (buffer.size <= TIP_FILE_MAX_BYTES) {
        if (source.read(buffer, TIP_FILE_MAX_BYTES + 1L - buffer.size) == -1L) return buffer.readUtf8()
    }
    return null
}
