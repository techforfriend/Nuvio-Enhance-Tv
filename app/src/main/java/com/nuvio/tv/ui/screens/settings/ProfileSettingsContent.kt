package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.PinRelock

private const val ROW_MANAGE = "profiles_manage"
private const val ROW_DPAD_COMBO = "profiles_dpad_combo"
private const val ROW_DPAD_REMOVE = "profiles_dpad_remove"

/**
 * Spec 31, Profiles pane (sandbox u11-settings-marquee v6, stage `profiles`): Manage profiles,
 * Settings access, Remember last profile (moved in from Advanced, Q12), Ask for PIN again (Spec 64
 * D15, sandbox spec-64-k3-pin-relock v3), then the D-pad unlock group. Without a button combination the unlock switch and Remove dim with the reason.
 */
@Composable
internal fun ProfileSettingsContent(
    onManageProfiles: () -> Unit,
    onNavigateToDpadComboSetup: (String) -> Unit = {},
    initialFocusRequester: FocusRequester? = null,
    viewModel: ProfileSettingsViewModel = hiltViewModel()
) {
    val manageRow = rememberSettingsReturnTarget(ROW_MANAGE)
    val comboRow = rememberSettingsReturnTarget(ROW_DPAD_COMBO)
    val removeRow = rememberSettingsReturnTarget(ROW_DPAD_REMOVE)
    // Null until loaded (see the ViewModel); rows wait for it.
    val state = viewModel.paneState.collectAsStateWithLifecycle().value
    val dpadUnlockTitle = stringResource(R.string.settings_dpad_unlock_title)

    SettingsPane(title = stringResource(R.string.settings_profiles), subtitle = stringResource(R.string.profiles_pane_subtitle)) {
        if (state == null) return@SettingsPane
        val comboSet = state.dpadUnlock.isComboSet
        item(key = ROW_MANAGE) {
            SettingsValueRow(
                title = stringResource(R.string.profiles_manage_title),
                subtitle = stringResource(R.string.profiles_manage_subtitle),
                value = pluralStringResource(R.plurals.profiles_count, state.profileCount, state.profileCount),
                onClick = { manageRow.launch(onManageProfiles) },
                modifier = Modifier
                    .focusRequester(manageRow.requester)
                    .optionalFocusRequester(initialFocusRequester)
            )
        }
        // T5: primary-owned by construction. Settings is primary-only unless this grant is on,
        // and the Profiles category stays primary-only even then, so only the primary flips it.
        item(key = "profiles_settings_access") {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_access_title),
                subtitle = stringResource(R.string.settings_access_others_subtitle),
                checked = state.settingsAccessGranted,
                onCheckedChange = viewModel::setSettingsAccessGranted
            )
        }
        item(key = "profiles_remember_last") {
            SettingsSwitchRow(
                title = stringResource(R.string.profiles_remember_last_title),
                subtitle = stringResource(R.string.profiles_remember_last_subtitle),
                checked = state.rememberLastProfile,
                onCheckedChange = viewModel::setRememberLastProfile
            )
        }
        // Spec 62 H3 (D8): stored on this TV like Remember last profile, so no "This TV" tag either.
        item(key = "profiles_return_to_picker") {
            SettingsSwitchRow(
                title = stringResource(R.string.profiles_return_to_picker_title),
                subtitle = stringResource(R.string.profiles_return_to_picker_subtitle),
                checked = state.returnToPicker,
                onCheckedChange = viewModel::setReturnToPicker
            )
        }
        item(key = "profiles_relock") {
            var pickerOpen by rememberSaveable { mutableStateOf(false) }
            SettingsValueRow(
                title = stringResource(R.string.pin_relock_title),
                subtitle = stringResource(R.string.pin_relock_subtitle),
                value = relockLabel(state.relockMinutes),
                thisTv = true,
                onClick = { pickerOpen = true }
            )
            if (pickerOpen) {
                PinRelockPanel(
                    minutes = state.relockMinutes,
                    onSelect = viewModel::setRelockAfterMinutes,
                    onDismiss = { pickerOpen = false }
                )
            }
        }
        item(key = "profiles_dpad_section") { SettingsSectionLabel(text = dpadUnlockTitle) }
        item(key = "profiles_dpad_unlock") {
            SettingsSwitchRow(
                title = dpadUnlockTitle,
                subtitle = stringResource(
                    if (comboSet) R.string.settings_dpad_unlock_subtitle else R.string.settings_dpad_needs_combo
                ),
                checked = state.dpadUnlock.enabled,
                enabled = comboSet,
                onCheckedChange = viewModel::setDpadUnlockEnabled
            )
        }
        item(key = ROW_DPAD_COMBO) {
            SettingsValueRow(
                title = stringResource(R.string.settings_dpad_combo_row),
                subtitle = stringResource(R.string.settings_dpad_combo_this_tv),
                value = if (comboSet) {
                    pluralStringResource(
                        R.plurals.settings_dpad_combo_set_count,
                        state.dpadUnlock.comboLength,
                        state.dpadUnlock.comboLength
                    )
                } else {
                    stringResource(R.string.settings_dpad_combo_not_set)
                },
                onClick = { comboRow.launch { onNavigateToDpadComboSetup(DpadComboSetupMode.SETUP) } },
                modifier = Modifier.focusRequester(comboRow.requester)
            )
        }
        item(key = ROW_DPAD_REMOVE) {
            SettingsValueRow(
                title = stringResource(R.string.settings_dpad_remove_row),
                subtitle = stringResource(
                    if (comboSet) R.string.settings_dpad_remove_row_sub else R.string.settings_dpad_no_combo
                ),
                value = null,
                enabled = comboSet,
                onClick = { removeRow.launch { onNavigateToDpadComboSetup(DpadComboSetupMode.REMOVE) } },
                modifier = Modifier.focusRequester(removeRow.requester)
            )
        }
    }
}

/** The row's value: Off, "After N min" or "After 1 hour". */
@Composable
private fun relockLabel(minutes: Int): String = when (minutes) {
    PinRelock.OFF -> stringResource(R.string.pin_relock_off)
    PinRelock.ONE_HOUR -> stringResource(R.string.pin_relock_after_hour)
    else -> stringResource(R.string.pin_relock_after_minutes, minutes)
}

/** Ask for PIN again picker (sandbox spec-64-k3-pin-relock v3 stage 1b): the six choices, then the note. */
@Composable
private fun PinRelockPanel(minutes: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    SettingsPickerPanel(
        title = stringResource(R.string.pin_relock_title),
        options = PinRelock.OPTIONS.map { value ->
            SettingsPickerOption(
                value = value,
                title = relockLabel(value),
                description = when (value) {
                    PinRelock.OFF -> stringResource(R.string.pin_relock_off_sub)
                    PinRelock.DEFAULT_MINUTES -> stringResource(R.string.pin_relock_default_sub)
                    else -> null
                }
            )
        },
        selectedValue = minutes,
        onSelect = onSelect,
        onDismiss = onDismiss,
        footnote = stringResource(R.string.pin_relock_note)
    )
}
