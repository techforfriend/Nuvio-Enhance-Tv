package com.nuvio.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.savedKeyEnding
import com.nuvio.tv.data.local.AVAILABLE_TMDB_LANGUAGES
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.screens.cloudstream.CloudStreamManagerViewModel

/** Row key of the CloudStream extensions row in the Integrations pane. */
internal const val CLOUDSTREAM_ROW_KEY = "cloudstream_extensions"

/** Row key of the TMDB API key row, which Settings' deep entry focuses ([SettingsEntry.TMDB_KEY]). */
internal const val TMDB_KEY_ROW_KEY = "tmdb_api_key"

/**
 * Spec 31, Integrations pane (sandbox u11-settings-marquee v6, stage `integrations`): the TMDB
 * rows directly under one section label. With enrichment off only More like this and Collections
 * dim (the detail page reads them behind the master switch); Language and Trailers stay live
 * because trailers, collection sources and cast read them on their own.
 *
 * Spec 52 T1 (sandbox `spec-52-public-build-ui`): the TMDB API key row comes first; OK opens
 * [TmdbKeySidePanel]. With no key at all (a public build before one is saved) every row below dims
 * with "Needs a TMDB key", since each of them calls TMDB, and its switch reads OFF (nothing runs
 * without a key; the stored choices show again once one is saved: 12/N-4). Home (ON_STOP) or
 * leaving the pane closes the panel and its phone page, as Spec 44's QR card. [openKeyPanel]
 * (Settings' deep entry [SettingsEntry.TMDB_KEY], once the key row has focus) opens the panel;
 * [onKeyPanelOpened] clears it.
 */
@Composable
fun TmdbSettingsContent(
    viewModel: TmdbSettingsViewModel = hiltViewModel(),
    cloudStreamViewModel: CloudStreamManagerViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
    openKeyPanel: Boolean = false,
    onKeyPanelOpened: () -> Unit = {},
    onNavigateToCloudStream: () -> Unit = {}
) {
    // Null until loaded (see the ViewModel); rows wait for both.
    val settings = viewModel.uiState.collectAsStateWithLifecycle().value
    val keyState = viewModel.keyState.collectAsStateWithLifecycle().value
    val keyPanel = viewModel.keyPanel.collectAsStateWithLifecycle().value
    val keySavedNotice = viewModel.keySavedNotice.collectAsStateWithLifecycle().value
    val phoneUrl = viewModel.phoneUrl.collectAsStateWithLifecycle().value
    val cloudStreamState = cloudStreamViewModel.uiState.collectAsStateWithLifecycle().value
    var showLanguageDialog by remember { mutableStateOf(false) }
    val toast = LocalMarqueeToast.current
    val tmdb = stringResource(R.string.settings_tmdb_name)
    val needsEnrichment = stringResource(R.string.tmdb_needs_enrichment)
    val needsKey = stringResource(R.string.tmdb_needs_key)
    val moreLikeThisSubtitle = stringResource(R.string.tmdb_more_like_this_row_subtitle)
    val collectionsSubtitle = stringResource(R.string.tmdb_collections_subtitle)
    val savedToast = stringResource(R.string.tmdb_api_key_saved_toast)

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onEvent(TmdbSettingsEvent.CloseKeyPanel) }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onEvent(TmdbSettingsEvent.CloseKeyPanel) }
    }

    LaunchedEffect(openKeyPanel) {
        if (openKeyPanel) {
            viewModel.onEvent(TmdbSettingsEvent.OpenKeyPanel)
            onKeyPanelOpened()
        }
    }

    LaunchedEffect(keySavedNotice) {
        if (keySavedNotice) {
            toast?.show(savedToast, success = true)
            viewModel.onEvent(TmdbSettingsEvent.KeySavedNoticeShown)
        }
    }

    SettingsPane(title = stringResource(R.string.settings_integration), subtitle = tmdb) {
        if (settings == null || keyState == null) return@SettingsPane
        val hasKey = keyState.hasKey
        item(key = "tmdb_section") { SettingsSectionLabel(text = tmdb) }
        item(key = TMDB_KEY_ROW_KEY) {
            val returnRow = rememberSettingsReturnTarget(TMDB_KEY_ROW_KEY)
            val ending = keyState.savedKeyEnding()
            SettingsValueRow(
                title = stringResource(R.string.tmdb_api_key_title),
                // A private build's own key (no key saved here) cannot be removed, only replaced.
                subtitle = stringResource(
                    when {
                        keyState.hasUserKey -> R.string.tmdb_api_key_subtitle_saved
                        hasKey -> R.string.tmdb_api_key_subtitle_built_in
                        else -> R.string.tmdb_api_key_subtitle_none
                    }
                ),
                value = if (ending != null) {
                    stringResource(R.string.tmdb_api_key_saved_value, ending)
                } else {
                    stringResource(R.string.tmdb_api_key_not_set)
                },
                emphasizedValue = ending != null,
                leadingIcon = Icons.Filled.VpnKey,
                onClick = { viewModel.onEvent(TmdbSettingsEvent.OpenKeyPanel) },
                modifier = Modifier
                    .optionalFocusRequester(returnRow.requester)
                    .optionalFocusRequester(initialFocusRequester)
            )
        }
        item(key = "tmdb_enabled") {
            SettingsSwitchRow(
                title = stringResource(R.string.tmdb_enrichment_title),
                subtitle = if (hasKey) stringResource(R.string.tmdb_enrichment_subtitle) else needsKey,
                checked = hasKey && settings.enabled,
                enabled = hasKey,
                onCheckedChange = { viewModel.onEvent(TmdbSettingsEvent.ToggleEnabled(it)) }
            )
        }
        item(key = "tmdb_language") {
            SettingsValueRow(
                title = stringResource(R.string.tmdb_language_title),
                subtitle = if (hasKey) stringResource(R.string.tmdb_language_rows_subtitle) else needsKey,
                value = AVAILABLE_TMDB_LANGUAGES.find { it.code == settings.language }?.displayName
                    ?: settings.language.uppercase(),
                enabled = hasKey,
                onClick = { showLanguageDialog = true }
            )
        }
        item(key = "tmdb_trailers") {
            SettingsSwitchRow(
                title = stringResource(R.string.tmdb_trailers_title),
                subtitle = if (hasKey) stringResource(R.string.tmdb_trailers_detail_subtitle) else needsKey,
                checked = hasKey && settings.useTrailers,
                enabled = hasKey,
                onCheckedChange = { viewModel.onEvent(TmdbSettingsEvent.ToggleTrailers(it)) }
            )
        }
        item(key = "tmdb_more_like_this") {
            SettingsSwitchRow(
                title = stringResource(R.string.tmdb_more_like_this_row_title),
                subtitle = when {
                    !hasKey -> needsKey
                    settings.enabled -> moreLikeThisSubtitle
                    else -> needsEnrichment
                },
                checked = hasKey && settings.useMoreLikeThis,
                enabled = hasKey && settings.enabled,
                onCheckedChange = { viewModel.onEvent(TmdbSettingsEvent.ToggleMoreLikeThis(it)) }
            )
        }
        item(key = "tmdb_collections") {
            SettingsSwitchRow(
                title = stringResource(R.string.tmdb_collections_title),
                subtitle = when {
                    !hasKey -> needsKey
                    settings.enabled -> collectionsSubtitle
                    else -> needsEnrichment
                },
                checked = hasKey && settings.useCollections,
                enabled = hasKey && settings.enabled,
                onCheckedChange = { viewModel.onEvent(TmdbSettingsEvent.ToggleCollections(it)) }
            )
        }
        item(key = "cloudstream_section") {
            SettingsSectionLabel(text = stringResource(R.string.cs3_title))
        }
        item(key = CLOUDSTREAM_ROW_KEY) {
            val returnRow = rememberSettingsReturnTarget(CLOUDSTREAM_ROW_KEY)
            val installedCount = cloudStreamState.plugins.count { it.isInstalled }
            SettingsValueRow(
                title = stringResource(R.string.cs3_title),
                subtitle = stringResource(R.string.cs3_subtitle),
                value = stringResource(R.string.cs3_plugins_installed_count, installedCount),
                onClick = { returnRow.launch(onNavigateToCloudStream) },
                modifier = Modifier.focusRequester(returnRow.requester)
            )
        }
    }

    if (keyPanel != null && keyState != null) {
        TmdbKeySidePanel(
            state = keyPanel,
            hasUserKey = keyState.hasUserKey,
            // Public builds blank this field (Spec 49 R3), so the note tells them to get a key.
            buildHasKey = BuildConfig.TMDB_API_KEY.isNotBlank(),
            phoneUrl = phoneUrl,
            onSubmit = { viewModel.onEvent(TmdbSettingsEvent.SubmitKey(it)) },
            onRemove = { viewModel.onEvent(TmdbSettingsEvent.RemoveKey) },
            onDismiss = { viewModel.onEvent(TmdbSettingsEvent.CloseKeyPanel) }
        )
    }

    if (showLanguageDialog && settings != null) {
        LanguageSelectionDialog(
            title = stringResource(R.string.tmdb_language_dialog_title),
            selectedLanguage = settings.language,
            showNoneOption = false,
            onLanguageSelected = { language ->
                viewModel.onEvent(TmdbSettingsEvent.SetLanguage(language ?: "en"))
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}
