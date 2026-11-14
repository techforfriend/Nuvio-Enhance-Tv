package com.nuvio.tv.ui.screens.welcome

import android.content.Context
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.update.ChangelogLoad
import com.nuvio.tv.core.update.ChangelogRelease
import com.nuvio.tv.core.update.ChangelogRepository
import com.nuvio.tv.core.update.SeenBuild
import com.nuvio.tv.core.update.UpdateStore
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.components.marquee.MarqueePrimaryButton
import com.nuvio.tv.ui.screens.detail.formatShortDate
import com.nuvio.tv.ui.screens.settings.changelogTitle
import com.nuvio.tv.ui.screens.settings.releaseDate
import com.nuvio.tv.ui.screens.settings.releaseNotesText
import com.nuvio.tv.ui.screens.tip.TipButton
import com.nuvio.tv.ui.screens.tip.TipEntryDialog
import com.nuvio.tv.ui.screens.tip.TipEntryState
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** What a start does about the What's new card (Spec 80 D1). */
internal sealed interface WhatsNewStart {
    /** Same build as last time: nothing. */
    data object Same : WhatsNewStart

    /**
     * A fresh install (the Welcome card is the first-start card) or a downgrade: record this build,
     * no card.
     */
    data object Record : WhatsNewStart

    /** An update: look this build up in the changelog. [previousName]: the build it replaced, when known. */
    data class Lookup(val previousName: String?) : WhatsNewStart
}

/**
 * [seen]: the build recorded last; [updatedSinceInstall]: the package was replaced since it was
 * first installed (an update from a build older than this card, which recorded nothing).
 */
internal fun whatsNewStart(seen: SeenBuild?, installedCode: Int, updatedSinceInstall: Boolean): WhatsNewStart =
    when {
        seen == null -> if (updatedSinceInstall) WhatsNewStart.Lookup(previousName = null) else WhatsNewStart.Record
        seen.versionCode == installedCode -> WhatsNewStart.Same
        seen.versionCode > installedCode -> WhatsNewStart.Record
        else -> WhatsNewStart.Lookup(seen.versionName.takeIf { it.isNotBlank() })
    }

/** One What's new row: "**Live TV**: Xtream playlists…" is label "Live TV", value "Xtream playlists…". */
internal data class WhatsNewRow(val label: String, val value: String)

/** [whatsNew] (the release's What's new section, Markdown) as rows: one per non-blank line, split at its first ": ". */
internal fun whatsNewRows(whatsNew: String): List<WhatsNewRow> =
    releaseNotesText(whatsNew).lineSequence()
        .map { it.trim().removePrefix("\u2022").trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val colon = line.indexOf(": ")
            if (colon <= 0) WhatsNewRow(line, "")
            else WhatsNewRow(line.substring(0, colon).trim(), line.substring(colon + 2).trim())
        }
        .toList()

/** The card's content: the installed build's release, its rows, the build it replaced when known. */
internal data class WhatsNewCard(
    val release: ChangelogRelease,
    val rows: List<WhatsNewRow>,
    val previousName: String?
)

/**
 * Spec 80 D1/D18: on the first start of a different build, looks the installed build up in the
 * public changelog and offers its What's new card once. A build not on GitHub (every private build)
 * or a release without a What's new section gets no card. The new build is recorded when the card
 * shows, or when the lookup answers with no card; a lookup that fails (offline, GitHub down) records
 * nothing, so the next start tries again. Activity-scoped, so a recreate keeps a card that shows.
 */
@HiltViewModel
class WhatsNewViewModel internal constructor(
    private val store: UpdateStore,
    private val repository: ChangelogRepository,
    private val installed: SeenBuild,
    private val updatedSinceInstall: () -> Boolean
) : ViewModel() {

    @Inject constructor(
        @ApplicationContext context: Context,
        store: UpdateStore,
        repository: ChangelogRepository
    ) : this(
        store,
        repository,
        SeenBuild(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME),
        { packageUpdatedSinceInstall(context) }
    )

    private val _card = MutableStateFlow<WhatsNewCard?>(null)
    internal val card: StateFlow<WhatsNewCard?> = _card.asStateFlow()
    private var started = false
    private var recorded = false

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                val seen = store.seenBuild()
                // The package lookup only matters (and only runs) when nothing is recorded.
                val updated = seen == null && updatedSinceInstall()
                when (val start = whatsNewStart(seen, installed.versionCode, updated)) {
                    WhatsNewStart.Same -> Unit
                    WhatsNewStart.Record -> record()
                    is WhatsNewStart.Lookup -> when (val load = repository.load()) {
                        ChangelogLoad.Failed -> Unit
                        is ChangelogLoad.Loaded -> {
                            val release = load.releases.firstOrNull { it.versionCode == installed.versionCode }
                            val rows = release?.let { whatsNewRows(it.whatsNew) }.orEmpty()
                            if (release == null || rows.isEmpty()) record()
                            else _card.value = WhatsNewCard(release, rows, start.previousName)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // The store could not be read or written: no card, the next start tries again.
                Log.w(TAG, "what's new skipped: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The card is on screen: it never shows for this build again. */
    fun shown() {
        if (recorded) return
        viewModelScope.launch {
            try {
                record()
            } catch (e: IOException) {
                Log.w(TAG, "what's new not recorded: ${e.javaClass.simpleName}")
            }
        }
    }

    fun dismiss() {
        _card.value = null
    }

    private suspend fun record() {
        withContext(NonCancellable) { store.setSeenBuild(installed) }
        recorded = true
    }

    private companion object {
        const val TAG = "WhatsNew"
    }
}

/** The package was replaced since it was first installed; false when it cannot be read. */
@Suppress("DEPRECATION") // getPackageInfo(String, PackageInfoFlags) is API 33+; minSdk is 24.
private fun packageUpdatedSinceInstall(context: Context): Boolean = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.lastUpdateTime > info.firstInstallTime
} catch (_: Exception) {
    false
}

/**
 * The What's new host (Spec 80 C1): shows the card while [onHome] and once the Welcome card has
 * settled (Welcome first; the same Activity-scoped [DeviceWelcomeViewModel] as DeviceWelcomeHost).
 * [onWatchVideo] plays the release video; [onReadNotes] opens the release's changelog page, null
 * (Settings locked for this profile) hides Read notes. Every button but Tip closes the card for
 * good; [tipLink] (Spec 81 T3; null hides Tip) opens the tip card over this one.
 */
@Composable
internal fun WhatsNewHost(
    onHome: Boolean,
    onWatchVideo: (url: String, title: String) -> Unit,
    onReadNotes: ((versionCode: Int) -> Unit)?,
    tipLink: String?
) {
    val viewModel: WhatsNewViewModel = hiltViewModel()
    val welcome: DeviceWelcomeViewModel = hiltViewModel()
    LaunchedEffect(viewModel) { viewModel.start() }
    val card by viewModel.card.collectAsState()
    val welcomeSettled by welcome.settled.collectAsState()
    val current = card ?: return
    if (!onHome || !welcomeSettled) return
    LaunchedEffect(current) { viewModel.shown() }
    val videoTitle = stringResource(R.string.changelog_video_title, current.release.versionName ?: current.release.title)
    WhatsNewCardView(
        card = current,
        onWatchVideo = current.release.video?.let { video ->
            { viewModel.dismiss(); onWatchVideo(video.url, videoTitle) }
        },
        onReadNotes = onReadNotes?.let { read ->
            current.release.versionCode?.let { code -> { viewModel.dismiss(); read(code) } }
        },
        onDismiss = viewModel::dismiss,
        tipLink = tipLink
    )
}

/**
 * `.l11qc.l33c.s80o` (sandbox spec-80-release-changelog v4, stage c-whatsnew): the Welcome card's
 * frame (D8) with the release's What's new rows; Watch video focused, or Read notes without a
 * video, else Dismiss. Back = Dismiss. Spec 81 (sandbox spec-81-donate v3, stage c-whatsnew): Tip
 * before Dismiss, never focused first.
 */
@Composable
private fun WhatsNewCardView(
    card: WhatsNewCard,
    onWatchVideo: (() -> Unit)?,
    onReadNotes: (() -> Unit)?,
    onDismiss: () -> Unit,
    tipLink: String?
) {
    val marquee = NuvioTheme.marquee
    val firstFocus = remember { FocusRequester() }
    val tip = remember { TipEntryState() }
    val released = releaseDate(card.release.publishedAt)?.let(::formatShortDate)
    val subtitle = listOfNotNull(
        card.previousName?.let { stringResource(R.string.whats_new_updated_from, it) },
        released?.let {
            stringResource(if (card.previousName != null) R.string.whats_new_released_after else R.string.update_panel_released, it)
        }
    ).joinToString(" \u00b7 ")
    WelcomeCardFrame(
        eyebrow = stringResource(R.string.whats_new_eyebrow),
        title = changelogTitle(card.release),
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        initialFocus = firstFocus,
        body = {
            card.rows.forEachIndexed { index, row ->
                WelcomeRowLine(
                    icon = Icons.Filled.AutoAwesome,
                    iconTint = NuvioPrimitives.warningOnTint,
                    label = row.label,
                    value = row.value,
                    modifier = if (index == 0) Modifier.padding(top = marquee.welcome.rowsTop) else Modifier
                )
            }
            Text(
                text = stringResource(
                    if (card.release.video != null) R.string.whats_new_note_video else R.string.whats_new_note
                ),
                style = NuvioTheme.textStyles.settingsNote,
                color = marquee.onImage2
            )
        }
    ) {
        if (onWatchVideo != null) {
            MarqueePrimaryButton(
                label = stringResource(R.string.whats_new_watch),
                icon = Icons.Filled.PlayArrow,
                onClick = onWatchVideo,
                focusRequester = firstFocus
            )
        }
        if (onReadNotes != null) {
            MarqueeGlassTextButton(
                label = stringResource(R.string.whats_new_read),
                onClick = onReadNotes,
                modifier = if (onWatchVideo == null) Modifier.focusRequester(firstFocus) else Modifier
            )
        }
        if (tipLink != null) TipButton(onClick = { tip.open(tipLink) }, modifier = Modifier.focusRequester(tip.focus))
        MarqueeGlassTextButton(
            label = stringResource(R.string.whats_new_dismiss),
            onClick = onDismiss,
            modifier = if (onWatchVideo == null && onReadNotes == null) Modifier.focusRequester(firstFocus) else Modifier
        )
    }
    TipEntryDialog(tip, fallback = firstFocus)
}
