package com.nuvio.tv.ui.screens.cloudstream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.cloudstream.CloudStreamPluginItem
import com.nuvio.tv.core.cloudstream.CloudStreamRepositoryItem
import com.nuvio.tv.core.cloudstream.CloudStreamRepositoryManifest
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.components.marquee.MarqueeConfirmPanel
import com.nuvio.tv.ui.screens.collection.CollectionFieldPill
import com.nuvio.tv.ui.screens.collection.CollectionRowAction
import com.nuvio.tv.ui.screens.collection.CollectionsHeaderButton
import com.nuvio.tv.ui.screens.collection.rememberCollectionFocusTargets
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

/** Focus target of the repo URL field. */
private const val FIELD_TARGET = "#field"

/**
 * CloudStream extensions route, modelled on AddonManagerScreen: breadcrumb,
 * Discover + Refresh top-right, the repo URL field, then repositories and
 * their plugins. Third-party .cs3 plugins run as downloaded code, so the
 * security warning gates every install until accepted (same gate as
 * Enhanced-2's full flavour).
 */
@Composable
fun CloudStreamManagerScreen(
    viewModel: CloudStreamManagerViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val repoUrl by viewModel.repoUrl.collectAsState()
    val isAdding by viewModel.isAddingRepository.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val busyPluginIds by viewModel.busyPluginIds.collectAsState()
    val transientMessage by viewModel.transientMessage.collectAsState()
    val toast = LocalMarqueeToast.current
    val marquee = NuvioTheme.marquee
    val tokens = marquee.collections
    val safeHorizontal = NuvioTheme.layout.tvSafeHorizontal
    val focusTargets = rememberCollectionFocusTargets()
    val addButton = remember { FocusRequester() }
    var repoPendingRemoval by remember { mutableStateOf<CloudStreamRepositoryItem?>(null) }

    BackHandler { onBackPress() }

    // First focus once loaded: the URL field (nothing installed yet) or the first repo row.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.repositories.isEmpty()) {
        if (!entered) {
            entered = true
            focusTargets.pending = FIELD_TARGET
        }
    }

    LaunchedEffect(transientMessage) {
        val message = transientMessage ?: return@LaunchedEffect
        toast?.show(message)
        viewModel.clearTransientMessage()
    }

    val installedCount = uiState.plugins.count { it.isInstalled }
    val pluginsByRepo = remember(uiState.plugins) {
        uiState.plugins.groupBy { it.metadata.repositoryManifestUrl }
    }

    Box(modifier = Modifier.fillMaxSize().background(NuvioPrimitives.black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = tokens.listTop)
                .verticalScroll(rememberScrollState())
                .padding(start = safeHorizontal, end = safeHorizontal, bottom = NuvioTheme.layout.tvSafeVertical),
            verticalArrangement = Arrangement.spacedBy(tokens.rowGap)
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Text(
                    text = stringResource(R.string.cs3_title),
                    style = NuvioTheme.textStyles.settingsTitle,
                    color = NuvioPrimitives.white
                )
                Text(
                    text = stringResource(R.string.cs3_plugins_installed_count, installedCount),
                    style = NuvioTheme.textStyles.meta,
                    color = marquee.onImage3,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.xxs)
                )
            }

            if (!uiState.securityWarningAccepted) {
                Text(
                    text = stringResource(R.string.cs3_security_body),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = marquee.onImage2
                )
                Row {
                    CollectionRowAction(
                        label = stringResource(R.string.cs3_security_accept),
                        onClick = { viewModel.acceptSecurityWarning() }
                    )
                }
            }

            // The URL field reads left to right in RTL locales too.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                CollectionFieldPill(
                    icon = Icons.Default.Add,
                    value = repoUrl,
                    onValueChange = viewModel::onRepoUrlChange,
                    placeholder = stringResource(R.string.cs3_add_repo_placeholder),
                    actionLabel = stringResource(
                        if (isAdding) R.string.cs3_adding else R.string.cs3_add_repo_btn
                    ),
                    onAction = { if (!isAdding) viewModel.addRepository() },
                    actionFocus = addButton,
                    fieldFocus = focusTargets.requester(FIELD_TARGET),
                    keyboardType = KeyboardType.Uri
                )
            }

            if (uiState.discoveredRepositories.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.cs3_discovered_section).uppercase(),
                    style = NuvioTheme.textStyles.eyebrow,
                    color = marquee.onImage3,
                    modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
                )
                uiState.discoveredRepositories.forEach { manifest ->
                    key(manifest.sourceUrl) {
                        CloudStreamDiscoveredRepoRow(
                            manifest = manifest,
                            onAdd = { viewModel.addRepository(manifest.sourceUrl) }
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.cs3_repos_section).uppercase(),
                style = NuvioTheme.textStyles.eyebrow,
                color = marquee.onImage3,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
            if (uiState.repositories.isEmpty()) {
                Text(
                    text = stringResource(R.string.cs3_empty_repos),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = marquee.onImage2
                )
            }
            uiState.repositories.forEach { repo ->
                key(repo.manifest.sourceUrl) {
                    CloudStreamRepoRow(
                        repo = repo,
                        pluginCount = pluginsByRepo[repo.manifest.sourceUrl]?.size ?: 0,
                        onToggle = { viewModel.setRepositoryEnabled(repo, !repo.enabled) },
                        onRefresh = { viewModel.refreshRepository(repo) },
                        onRemove = { repoPendingRemoval = repo }
                    )
                    pluginsByRepo[repo.manifest.sourceUrl].orEmpty().forEach { plugin ->
                        key(plugin.metadata.id.value) {
                            CloudStreamPluginRow(
                                plugin = plugin,
                                busy = plugin.metadata.id.value in busyPluginIds,
                                onInstall = { viewModel.installPlugin(plugin) },
                                onUpdate = { viewModel.updatePlugin(plugin) },
                                onToggle = { viewModel.setPluginEnabled(plugin, !plugin.enabled) },
                                onRemove = { viewModel.removePlugin(plugin) }
                            )
                        }
                    }
                }
            }
            if (uiState.repositories.isNotEmpty() && uiState.plugins.isEmpty()) {
                Text(
                    text = stringResource(R.string.cs3_empty_plugins),
                    style = NuvioTheme.textStyles.settingsNote,
                    color = marquee.onImage2
                )
            }
        }

        MarqueeBreadcrumb(listOf(stringResource(R.string.nav_settings), stringResource(R.string.cs3_title)))
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = tokens.headerTop, end = safeHorizontal),
            horizontalArrangement = Arrangement.spacedBy(tokens.headerButtonGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CollectionsHeaderButton(
                label = stringResource(if (isDiscovering) R.string.cs3_discovering else R.string.cs3_discover),
                onClick = { if (!isDiscovering) viewModel.discoverRepositories() }
            )
            CollectionsHeaderButton(
                label = stringResource(R.string.cs3_refresh_all),
                onClick = { viewModel.refreshAll() }
            )
        }
    }

    repoPendingRemoval?.let { repo ->
        MarqueeConfirmPanel(
            eyebrow = stringResource(R.string.cs3_repo_remove_eyebrow, repo.manifest.name),
            body = stringResource(R.string.cs3_repo_remove_body),
            confirmLabel = stringResource(R.string.cs3_remove),
            cancelLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                viewModel.removeRepository(repo)
                repoPendingRemoval = null
            },
            onDismiss = { repoPendingRemoval = null },
            destructive = true
        )
    }
}

@Composable
private fun CloudStreamDiscoveredRepoRow(
    manifest: CloudStreamRepositoryManifest,
    onAdd: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        manifest.iconUrl?.let { iconUrl ->
            AsyncImage(
                model = iconUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = manifest.name,
                style = NuvioTheme.textStyles.settingsNote,
                color = NuvioPrimitives.white
            )
            manifest.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = NuvioTheme.textStyles.meta,
                    color = NuvioTheme.marquee.onImage3
                )
            }
        }
        CollectionRowAction(
            label = stringResource(R.string.cs3_add_repo_btn),
            onClick = onAdd
        )
    }
}

@Composable
private fun CloudStreamRepoRow(
    repo: CloudStreamRepositoryItem,
    pluginCount: Int,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = repo.manifest.name,
                style = NuvioTheme.textStyles.settingsNote,
                color = NuvioPrimitives.white,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (repo.isRefreshing) {
                    stringResource(R.string.cs3_refreshing)
                } else {
                    stringResource(R.string.cs3_repo_plugin_count, pluginCount)
                },
                style = NuvioTheme.textStyles.meta,
                color = NuvioTheme.marquee.onImage3
            )
        }
        repo.manifest.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = NuvioTheme.textStyles.meta,
                color = NuvioTheme.marquee.onImage3
            )
        }
        repo.errorMessage?.let { error ->
            Text(
                text = error,
                style = NuvioTheme.textStyles.meta,
                color = NuvioPrimitives.errorOnImage
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
            CollectionRowAction(
                label = stringResource(if (repo.enabled) R.string.cs3_disable else R.string.cs3_enable),
                onClick = onToggle
            )
            CollectionRowAction(
                label = stringResource(R.string.cs3_refresh),
                onClick = onRefresh
            )
            CollectionRowAction(
                label = stringResource(R.string.cs3_remove),
                onClick = onRemove,
                destructive = true
            )
        }
    }
}

@Composable
private fun CloudStreamPluginRow(
    plugin: CloudStreamPluginItem,
    busy: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val status = when {
        !plugin.isInstalled -> stringResource(R.string.cs3_not_installed)
        plugin.hasUpdate -> stringResource(
            R.string.cs3_update_available,
            plugin.installedVersion ?: 0,
            plugin.metadata.version
        )
        !plugin.isRunnable -> plugin.compatibility.reason
        !plugin.enabled -> stringResource(R.string.cs3_disabled)
        else -> stringResource(R.string.cs3_installed_version, plugin.metadata.version)
    }
    val details = listOfNotNull(
        plugin.metadata.language?.takeIf { it.isNotBlank() },
        plugin.metadata.tvTypes.joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
        plugin.metadata.authors.joinToString(", ").takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            plugin.metadata.iconUrl?.let { iconUrl ->
                AsyncImage(
                    model = iconUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp)
                )
            }
            Text(
                text = plugin.metadata.name,
                style = NuvioTheme.textStyles.settingsNote,
                color = NuvioPrimitives.white,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = status,
                style = NuvioTheme.textStyles.meta,
                color = NuvioTheme.marquee.onImage3
            )
        }
        if (details.isNotBlank()) {
            Text(
                text = details,
                style = NuvioTheme.textStyles.meta,
                color = NuvioTheme.marquee.onImage3
            )
        }
        plugin.metadata.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = NuvioTheme.textStyles.meta,
                color = NuvioTheme.marquee.onImage3
            )
        }
        plugin.errorMessage?.let { error ->
            Text(
                text = error,
                style = NuvioTheme.textStyles.meta,
                color = NuvioPrimitives.errorOnImage
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
            when {
                !plugin.isInstalled -> CollectionRowAction(
                    label = stringResource(R.string.cs3_install),
                    onClick = onInstall,
                    dimmed = busy
                )
                plugin.hasUpdate -> CollectionRowAction(
                    label = stringResource(R.string.cs3_update),
                    onClick = onUpdate,
                    dimmed = busy
                )
            }
            if (plugin.isInstalled) {
                CollectionRowAction(
                    label = stringResource(if (plugin.enabled) R.string.cs3_disable else R.string.cs3_enable),
                    onClick = onToggle,
                    dimmed = busy || !plugin.compatibility.isRunnable
                )
                CollectionRowAction(
                    label = stringResource(R.string.cs3_remove),
                    onClick = onRemove,
                    destructive = true,
                    dimmed = busy
                )
            }
        }
    }
}
