package com.nuvio.tv.ui.screens.settings

import androidx.navigation.NavController
import com.nuvio.tv.ui.navigation.Screen

/** The rail's categories, in rail order (approved sandbox `u11-settings-marquee` v6). */
internal enum class SettingsCategory {
    ACCOUNT,
    PROFILES,
    APPEARANCE,
    LAYOUT,
    CONTENT_DISCOVERY,
    INTEGRATION,
    PLAYBACK,
    ABOUT,
    ADVANCED,
    DEBUG
}

/** Account and Profiles belong to the primary profile; Debug exists only in debug builds. */
internal fun visibleSettingsCategories(
    isPrimaryProfile: Boolean,
    isDebugBuild: Boolean
): List<SettingsCategory> = SettingsCategory.entries.filter { category ->
    when (category) {
        SettingsCategory.ACCOUNT, SettingsCategory.PROFILES -> isPrimaryProfile
        SettingsCategory.DEBUG -> isDebugBuild
        else -> true
    }
}

/** The category shown when the wanted one is hidden (or none is saved yet). */
internal fun defaultSettingsCategory(visible: List<SettingsCategory>): SettingsCategory =
    visible.firstOrNull() ?: SettingsCategory.APPEARANCE

/** The row that opened a sub-route: focus goes back to it when Settings composes again. */
internal data class SettingsReturnTarget(val category: SettingsCategory, val rowKey: String)

/** Saveable form: `CATEGORY/rowKey`. */
internal fun SettingsReturnTarget.encode(): String = "${category.name}/$rowKey"

internal fun decodeSettingsReturnTarget(encoded: String?): SettingsReturnTarget? {
    if (encoded == null) return null
    val slash = encoded.indexOf('/')
    if (slash <= 0 || slash == encoded.lastIndex) return null
    val category = SettingsCategory.entries.firstOrNull { it.name == encoded.substring(0, slash) }
        ?: return null
    return SettingsReturnTarget(category, encoded.substring(slash + 1))
}

/**
 * A place Settings opens at from outside it (Spec 33 D5): the row focus lands on and, in the
 * Playback pane, the chip that row lives under. [opensPanel]: once the row has focus, its pane
 * opens the row's panel too (Spec 52 P5).
 */
internal enum class SettingsEntry(
    val target: SettingsReturnTarget,
    val playbackSection: PlaybackSection?,
    val opensPanel: Boolean = false
) {
    /** Playback › Buffer & network › This TV: the welcome card's Review (D8), the pause chip (D7). */
    NETWORK(SettingsReturnTarget(SettingsCategory.PLAYBACK, THIS_TV_ENTRY_ROW_KEY), PlaybackSection.BUFFER_NETWORK),

    /** Integrations › TMDB API key with its panel open: a no-key page's Add a TMDB key (Spec 52 T1). */
    TMDB_KEY(SettingsReturnTarget(SettingsCategory.INTEGRATION, TMDB_KEY_ROW_KEY), null, opensPanel = true),

    /** About › Update available with the update panel open: the top bar's Update pill (Spec 52 T2). */
    UPDATE(SettingsReturnTarget(SettingsCategory.ABOUT, ABOUT_UPDATE_ROW_KEY), null, opensPanel = true),

    /**
     * About › Changelog with the list open (Spec 80): the What's new card's Read notes, which also
     * names the release whose page opens ([openSettingsAt]'s release), and the Welcome card's
     * Changelog (D15), which names none.
     */
    CHANGELOG(SettingsReturnTarget(SettingsCategory.ABOUT, ABOUT_CHANGELOG_ROW_KEY), null);

    companion object {
        fun fromName(name: String?): SettingsEntry? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Opens Settings at [entry]. [navigate] puts the Settings destination on top (tab-style from
 * Home, pushed over the player or a no-key page); the entry then rides that back-stack entry's SavedStateHandle
 * ([Screen.Settings.ENTRY_KEY]), so it also reaches a Settings screen already composed. [release]
 * (a versionCode, [SettingsEntry.CHANGELOG] only) rides beside it ([Screen.Settings.ENTRY_RELEASE_KEY]),
 * written first so it is there when the entry is read.
 */
internal fun NavController.openSettingsAt(
    entry: SettingsEntry,
    release: Int? = null,
    navigate: NavController.() -> Unit
) {
    navigate()
    val top = currentBackStackEntry ?: return
    if (top.destination.route == Screen.Settings.route) {
        top.savedStateHandle[Screen.Settings.ENTRY_RELEASE_KEY] = release
        top.savedStateHandle[Screen.Settings.ENTRY_KEY] = entry.name
    }
}

/** Where focus lands when Settings composes. */
internal sealed interface SettingsEntryFocus {
    /** The launching row, in its own category's pane. */
    data class Row(val target: SettingsReturnTarget) : SettingsEntryFocus

    /** A rail item. */
    data class Rail(val category: SettingsCategory) : SettingsEntryFocus
}

/**
 * A saved return target wins while its category is still shown (a profile switch can hide
 * Account and Profiles); otherwise the selected rail item, else the first one. The caller falls
 * back to the rail when the row itself is gone.
 */
internal fun settingsEntryFocus(
    saved: SettingsReturnTarget?,
    selected: SettingsCategory,
    visible: List<SettingsCategory>
): SettingsEntryFocus = when {
    saved != null && saved.category in visible -> SettingsEntryFocus.Row(saved)
    selected in visible -> SettingsEntryFocus.Rail(selected)
    else -> SettingsEntryFocus.Rail(defaultSettingsCategory(visible))
}
