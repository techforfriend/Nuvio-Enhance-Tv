package com.nuvio.tv.core.update

import android.os.Build
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Where and for what the check looks (Spec 52 P6); [supported] false = no check, no Updates UI. */
data class UpdateFeed(
    val supported: Boolean,
    val owner: String,
    val repo: String,
    val installedCode: Int,
    val supportedAbis: List<String>
) {
    companion object {
        fun fromBuild(): UpdateFeed = UpdateFeed(
            supported = updateCheckSupported(
                builtInTmdbKey = BuildConfig.TMDB_API_KEY,
                debugBuild = BuildConfig.IS_DEBUG_BUILD,
                applicationId = BuildConfig.APPLICATION_ID
            ),
            owner = BuildConfig.UPDATE_OWNER,
            repo = BuildConfig.UPDATE_REPO,
            installedCode = BuildConfig.VERSION_CODE,
            supportedAbis = Build.SUPPORTED_ABIS.toList()
        )
    }
}

/** Download and install (P8) as the update panel shows it. */
sealed interface DownloadState {
    data object Idle : DownloadState

    /**
     * From the press to the installer handoff: the fresh check first ([totalBytes] 0), then the
     * download ([bytes] moves once per whole percent).
     */
    data class Running(val bytes: Long = 0L, val totalBytes: Long = 0L) : DownloadState

    /** "Download failed. Try again." The fresh check or the download failed, or no installer answered. */
    data object Failed : DownloadState

    /**
     * Spec 80 D6/D14: verified while the update panel was not on screen (the changelog or its video
     * was), so the file waits for "Ready: Install now" ([UpdateController.installReady]).
     */
    data object Ready : DownloadState
}

/**
 * What About, the rail dot and the top-bar pill show (S8). [lastCheckFailed] = the latest check
 * this run, automatic or not, got no usable answer (no popup either way); [available] = the known
 * release while it is newer than this build, so installing it clears it.
 */
data class UpdateState(
    val supported: Boolean = false,
    val autoCheck: Boolean = true,
    val checking: Boolean = false,
    val lastCheckFailed: Boolean = false,
    val lastOkCheckMs: Long = 0L,
    val available: KnownRelease? = null,
    val download: DownloadState = DownloadState.Idle
)

/**
 * The NEW badge, About's rail dot and the top-bar pill (Spec 52 D2): a newer release is known and
 * Check automatically is on; turning the toggle off hides all three, the About row stays.
 */
val UpdateState.noticeShown: Boolean
    get() = supported && autoCheck && available != null

/**
 * The update check (Spec 52 P6/P7): the public repo's latest release, at most once a day at app
 * start while Check automatically is on, or on demand. One check at a time; a second request
 * joins the running one. Runs on its own scope, so leaving About never cuts a check short.
 *
 * The controller holds the record in memory: it reads the store once and keeps what each write
 * returns, under one lock, so [state] changes in the order the writes land and no DataStore flow
 * is collected on this scope (a read error reads as defaults, never a crash).
 *
 * Download and install (P8) is one action from the press to the installer handoff, one at a time;
 * it runs on the same scope, so only [cancelDownload] (Cancel, the panel's Back or Not now, the activity's ON_STOP while
 * Settings shows, Settings leaving the screen other than for the release video, its back-stack
 * entry ending: Spec 80 D14) stops it. The installer opens the moment the file verifies only
 * while the update panel is on screen ([setPanelShown]); otherwise the file waits as
 * [DownloadState.Ready] until [installReady] (Spec 80 D6: never over a changelog or its video).
 */
@Singleton
class UpdateController internal constructor(
    private val api: GitHubReleaseApi,
    private val store: UpdateStore,
    private val feed: UpdateFeed,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val downloader: ApkDownloader,
    private val install: (File) -> Boolean
) {
    @Inject constructor(
        api: GitHubReleaseApi,
        store: UpdateStore,
        downloader: ApkDownloader,
        installer: ApkInstaller
    ) : this(
        api, store, UpdateFeed.fromBuild(), CoroutineScope(SupervisorJob() + Dispatchers.IO), System::currentTimeMillis,
        downloader, installer::launch
    )

    private data class Snapshot(
        val loaded: Boolean = false,
        val record: UpdateRecord = UpdateRecord(),
        val checking: Boolean = false,
        val failed: Boolean = false,
        val download: DownloadState = DownloadState.Idle
    )

    private val snapshot = MutableStateFlow(Snapshot())
    private val writes = Mutex()
    private val lock = Any()
    private var running: Deferred<CheckOutcome>? = null

    /** Held by each download, so a press right after a cancel waits for the cancelled action's deletes. */
    private val files = Mutex()
    private var downloadJob: Job? = null

    /** Whether the update panel is on screen now (Spec 80 D14): a verified file goes to the installer at once only then. */
    @Volatile private var panelShown = false

    /** The verified file while [DownloadState.Ready]; guarded by [lock]. */
    private var readyApk: File? = null

    /** App start: a file a past run left (handed to the installer, or cut by a kill) goes; downloads wait for it. */
    private val cleanup: Job? = if (feed.supported) scope.launch { downloader.clear() } else null

    val state: StateFlow<UpdateState> =
        if (!feed.supported) {
            MutableStateFlow(UpdateState())
        } else {
            snapshot.map(::toState).stateIn(scope, SharingStarted.Eagerly, toState(Snapshot()))
        }

    /** [UpdateState.noticeShown] alone (the top-bar pill, About's rail dot): progress never reaches them. */
    val notice: StateFlow<Boolean> = state.map { it.noticeShown }.stateIn(scope, SharingStarted.Eagerly, state.value.noticeShown)

    /**
     * Spec 80 D5: the release (versionCode) the top-bar pill is to pulse for, once: the pill shows
     * for it and has not pulsed for it yet ([pillPulsed] records that). Null = no pulse.
     */
    val pulseRelease: StateFlow<Int?> =
        if (!feed.supported) {
            MutableStateFlow(null)
        } else {
            snapshot.map(::pulseOf).stateIn(scope, SharingStarted.Eagerly, null)
        }

    init {
        if (feed.supported) {
            scope.launch {
                val record = store.read()
                snapshot.update { it.copy(loaded = true, record = record) }
            }
        }
    }

    /** About's Check for updates. */
    fun checkNow() {
        if (feed.supported) scope.launch { awaitCheck() }
    }

    /**
     * App start (MainActivity): checks when Check automatically is on and the last attempt is a
     * day old. Returns the launched job (tests join it), null on a build without the check.
     */
    fun autoCheckIfDue(): Job? {
        if (!feed.supported) return null
        return scope.launch {
            val record = loadedRecord()
            if (record.autoCheck && autoCheckDue(nowMs(), record.lastAttemptMs)) awaitCheck()
        }
    }

    /** False when the store could not be written (the toggle keeps its value). */
    suspend fun setAutoCheck(enabled: Boolean): Boolean {
        if (!feed.supported) return false
        loadedRecord()
        return try {
            write { setAutoCheck(enabled) }
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Runs a check, or joins the one running; null on a build without the check. A caller that
     * needs this answer's release (S7: a fresh URL and digest before a download) reads it from the
     * returned [CheckOutcome.Answered], not from [state].
     */
    suspend fun awaitCheck(): CheckOutcome? {
        if (!feed.supported) return null
        val check = synchronized(lock) {
            running?.takeIf { it.isActive } ?: scope.async { check() }.also { running = it }
        }
        return check.await()
    }

    /**
     * Download and install: a fresh check first (P8: the current URL and digest; a pulled release
     * ends as up to date or failed, never a dead link), then the download, then the installer.
     * A press while one runs gets the running one. Returns the action's job (tests join it), null
     * on a build without the check.
     */
    fun downloadAndInstall(): Job? {
        if (!feed.supported) return null
        if (snapshot.value.download == DownloadState.Ready) return installReady()
        return synchronized(lock) {
            downloadJob?.takeIf { it.isActive }
                ?: scope.launch {
                    cleanup?.join()
                    files.withLock { runDownload() }
                }.also { downloadJob = it }
        }
    }

    /**
     * Cancel, or the update panel closing (Not now, Back, Settings leaving the screen other than for
     * the release video, and the activity's ON_STOP: from the background Android drops the installer
     * launch without an error; Spec 80 D14): stops the action, and what it wrote is deleted, a file
     * waiting as Ready too. A failed state clears too (also one a Try again left before it
     * started running), so the panel opens fresh next time. After the installer handoff the action
     * has ended: nothing is cancelled and the handed-over file stays.
     */
    fun cancelDownload() {
        // A waiting file is dropped too (Not now on Ready): the next press downloads afresh.
        val ready = synchronized(lock) {
            downloadJob?.cancel()
            snapshot.update {
                if (it.download == DownloadState.Failed || it.download == DownloadState.Ready) it.copy(download = DownloadState.Idle) else it
            }
            readyApk.also { readyApk = null }
        }
        ready?.delete()
    }

    /** The pill has started its pulse for [versionCode] (Spec 80 D5): it never pulses for it again. */
    fun pillPulsed(versionCode: Int) {
        if (!feed.supported) return
        scope.launch {
            try {
                write { setPulsed(versionCode) }
            } catch (e: IOException) {
                // Kept in memory at least, so this run does not pulse for it again.
                Log.w(TAG, "pulse not recorded: ${e.javaClass.simpleName}")
                // Under the write lock, so it lands in write order (a later write's record still drops it).
                writes.withLock { snapshot.update { it.copy(record = it.record.copy(pulsedVersionCode = versionCode)) } }
            }
        }
    }

    /** The update panel came on screen ([shown]) or left it; see [panelShown]. */
    fun setPanelShown(shown: Boolean) {
        panelShown = shown
    }

    /**
     * "Ready: Install now" (Spec 80 D6): the waiting file to the installer. Idle once handed over,
     * Failed when no installer answered. Null when nothing waits.
     */
    fun installReady(): Job? {
        val apk = synchronized(lock) { readyApk.also { readyApk = null } } ?: return null
        return scope.launch {
            val handed = try {
                install(apk)
            } catch (e: Exception) {
                Log.w(UPDATE_DOWNLOAD_TAG, "handoff failed: ${e.javaClass.simpleName}")
                false
            }
            if (!handed) apk.delete()
            // Only over the Ready this press answered: a Cancel and a new press meanwhile own the state now.
            snapshot.update {
                if (it.download == DownloadState.Ready) it.copy(download = if (handed) DownloadState.Idle else DownloadState.Failed) else it
            }
        }
    }

    private suspend fun runDownload() {
        var end: DownloadState = DownloadState.Failed
        snapshot.update { it.copy(download = DownloadState.Running()) }
        try {
            end = downloadAndHandOver()
        } catch (e: CancellationException) {
            // The rename and the archive check do not stop for a cancel, so a verified file can be left.
            downloader.clear()
            end = DownloadState.Idle
            throw e
        } catch (e: Exception) {
            // Nothing may escape to the scope (no handler: an app crash), e.g. FileProvider's IllegalArgumentException.
            Log.w(UPDATE_DOWNLOAD_TAG, "download action failed: ${e.javaClass.simpleName}")
            // A handoff that threw leaves no file behind (Spec 80 S4).
            downloader.clear()
        } finally {
            // Under the lock with cancelDownload: a Ready whose file a cancel already took reads Idle.
            synchronized(lock) {
                val final = if (end == DownloadState.Ready && readyApk == null) DownloadState.Idle else end
                snapshot.update { it.copy(download = final) }
            }
        }
    }

    private suspend fun downloadAndHandOver(): DownloadState {
        val offer = when (val outcome = awaitCheck()) {
            is CheckOutcome.Answered -> outcome.offer?.takeIf { it.isNewer() } ?: return DownloadState.Idle
            CheckOutcome.NoRelease -> return DownloadState.Idle
            CheckOutcome.Failed, CheckOutcome.Unreached, null -> return DownloadState.Failed
        }
        val total = offer.sizeBytes
        snapshot.update { it.copy(download = DownloadState.Running(0L, total)) }
        // Published once per whole percent: a read is a few KiB, so per read would be thousands of states.
        var shownPercent = -1L
        val result = downloader.download(offer) { bytes ->
            val percent = bytes * 100 / total
            if (percent != shownPercent) {
                shownPercent = percent
                snapshot.update { it.copy(download = DownloadState.Running(bytes, total)) }
            }
        }
        return when (result) {
            is DownloadResult.Verified -> {
                // A cancel that landed while the file was checked wins: no installer, no Ready (the
                // CancellationException path deletes the file).
                val context = currentCoroutineContext()
                context.ensureActive()
                when {
                    panelShown -> if (install(result.apk)) {
                        DownloadState.Idle
                    } else {
                        result.apk.delete()
                        DownloadState.Failed
                    }
                    else -> {
                        // Under the lock cancelDownload cancels under: either it sees the file or this sees the cancel.
                        synchronized(lock) {
                            context.ensureActive()
                            readyApk = result.apk
                        }
                        DownloadState.Ready
                    }
                }
            }
            DownloadResult.Rejected -> {
                forget(offer)
                DownloadState.Failed
            }
            DownloadResult.Failed -> DownloadState.Failed
        }
    }

    /** P7: an offer whose file can never install is dropped until a check learns it again. */
    private suspend fun forget(release: KnownRelease) {
        try {
            write { forgetRelease(release) }
        } catch (e: IOException) {
            Log.w(UPDATE_DOWNLOAD_TAG, "release not dropped: ${e.javaClass.simpleName}")
        }
    }

    private fun KnownRelease.isNewer(): Boolean = versionCode > feed.installedCode

    private suspend fun check(): CheckOutcome {
        loadedRecord()
        snapshot.update { it.copy(checking = true) }
        var outcome: CheckOutcome = CheckOutcome.Unreached
        try {
            outcome = fetchAndRecord()
        } finally {
            snapshot.update { it.copy(checking = false, failed = outcome !is CheckOutcome.Answered) }
        }
        Log.i(TAG, "check: ${outcome.javaClass.simpleName}")
        return outcome
    }

    /** The answer, recorded; a write that fails makes it [CheckOutcome.Unreached] (nothing kept). */
    private suspend fun fetchAndRecord(): CheckOutcome {
        val outcome = fetch()
        if (outcome == CheckOutcome.Unreached) return outcome
        return try {
            write { record(outcome, nowMs()) }
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "check not recorded: ${e.javaClass.simpleName}")
            CheckOutcome.Unreached
        }
    }

    private suspend fun fetch(): CheckOutcome = try {
        val response = api.latestRelease(feed.owner, feed.repo)
        checkOutcome(response.code(), response.body(), feed.supportedAbis)
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        CheckOutcome.Unreached
    } catch (e: Exception) {
        // An answer that did not decode (SerializationException) or a converter failure.
        Log.w(TAG, "check answer unusable: ${e.javaClass.simpleName}")
        CheckOutcome.Failed
    }

    private suspend fun loadedRecord(): UpdateRecord = snapshot.first { it.loaded }.record

    /** One store write and its record into memory, in the order the writes land. */
    private suspend fun write(block: suspend UpdateStore.() -> UpdateRecord) {
        writes.withLock {
            val record = store.block()
            snapshot.update { it.copy(record = record) }
        }
    }

    private fun pulseOf(snapshot: Snapshot): Int? {
        val state = toState(snapshot)
        val code = state.available?.versionCode ?: return null
        return code.takeIf { state.noticeShown && it != snapshot.record.pulsedVersionCode }
    }

    private fun toState(snapshot: Snapshot) = UpdateState(
        supported = true,
        autoCheck = snapshot.record.autoCheck,
        checking = snapshot.checking,
        lastCheckFailed = snapshot.failed,
        lastOkCheckMs = snapshot.record.lastOkCheckMs,
        available = snapshot.record.knownRelease?.takeIf { it.isNewer() },
        download = snapshot.download
    )

    private companion object {
        const val TAG = "UpdateCheck"
    }
}
