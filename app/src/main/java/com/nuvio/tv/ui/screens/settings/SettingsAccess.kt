package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.navigation.Screen

/**
 * T1 settings lock (docs/specs/006-profile-guard-features.md): non-primary
 * profiles cannot enter Settings. [blockedTopBarRoutes] gates top-bar tabs
 * (a blocked click shows a notice instead of navigating); [settingsOwnedRoutes]
 * backstops a mid-session profile switch away from primary while a settings
 * surface is on top (eject to Home). T5 adds the primary-owned grant: while
 * [settingsAccessGranted] is true, non-primary profiles keep full Settings
 * access (the lock is frozen, never reset — the grant changes only the gate).
 */
internal object SettingsAccess {

    /**
     * Routes reachable only from gated Settings-family surfaces — the transitive
     * closure of Settings: every in-app entry screen for these routes (Settings,
     * AddonManager, Collections) is itself in this set, except Home's empty-state
     * "Open addons", which gates through [isBlockedFromTopBar]. AddonManager is included even
     * though addon-install deep links target it: the install consent dialog
     * still works over the ejected Home. Collections is entered only from
     * AddonManager, CollectionEditor only from Collections (NuvioNavHost).
     */
    val settingsOwnedRoutes: Set<String> = setOf(
        Screen.Settings.route,
        Screen.ManageProfiles.route,
        Screen.AuthQrSignIn.route,
        Screen.AddonManager.route,
        Screen.CloudStreamManager.route,
        Screen.CatalogOrder.route,
        Screen.Collections.route,
        Screen.CollectionEditor.route,
        Screen.DpadComboSetup.route
    )

    fun blockedTopBarRoutes(isPrimaryProfileActive: Boolean, settingsAccessGranted: Boolean): Set<String> =
        if (isPrimaryProfileActive || settingsAccessGranted) emptySet() else setOf(Screen.Settings.route)

    /**
     * Whether a navigation from the top-bar scaffold to [route] is blocked, given its
     * [blockedTopBarRoutes]: settings-owned routes are blocked exactly when Settings is.
     */
    fun isBlockedFromTopBar(route: String, blockedTopBarRoutes: Set<String>): Boolean =
        route in blockedTopBarRoutes ||
            (route in settingsOwnedRoutes && Screen.Settings.route in blockedTopBarRoutes)

    fun shouldEjectFromRoute(route: String?, isPrimaryProfileActive: Boolean, settingsAccessGranted: Boolean): Boolean =
        !settingsAccessGranted && !isPrimaryProfileActive && route != null && route in settingsOwnedRoutes
}
