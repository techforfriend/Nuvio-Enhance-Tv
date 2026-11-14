package com.nuvio.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.auth.DeviceSessionRegistration
import com.nuvio.tv.ui.components.PanelActionRow
import com.nuvio.tv.ui.components.marquee.MarqueeLoadingScreen
import com.nuvio.tv.ui.components.marquee.LocalMarqueeToast
import com.nuvio.tv.ui.components.marquee.MarqueeToastHost
import com.nuvio.tv.ui.components.marquee.rememberMarqueeToastState
import com.nuvio.tv.ui.components.rememberAmbientAccentProvider
import java.net.URI
import com.nuvio.tv.core.deeplink.DeepLinkHandler
import com.nuvio.tv.core.deeplink.DeepLinkParser
import com.nuvio.tv.core.profile.IdleProfileReturn
import com.nuvio.tv.core.profile.activeProfilePinState
import com.nuvio.tv.core.profile.profileReturnHasTarget
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.tip.tipEntryShown
import com.nuvio.tv.core.util.StartupLatencyTrace
import com.nuvio.tv.data.local.AppOnboardingDataStore
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.data.local.MyPhotosStore
import com.nuvio.tv.data.local.ProfileLookStore
import com.nuvio.tv.domain.model.resolveLookAvatar
import com.nuvio.tv.ui.components.LocalLookLibrary
import com.nuvio.tv.ui.components.toArt
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.resolveAppTheme
import com.nuvio.tv.domain.deeplink.AppDeepLink
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.ScreensaverOverlay
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.livetv.data.LiveTvUserStore
import com.nuvio.tv.ui.screens.account.AuthQrSignInScreen
import com.nuvio.tv.ui.screens.profile.ProfileSelectionScreen
import com.nuvio.tv.ui.screens.settings.SettingsAccess
import com.nuvio.tv.ui.screens.settings.SettingsEntry
import com.nuvio.tv.ui.screens.settings.openSettingsAt
import com.nuvio.tv.ui.screens.welcome.DeviceWelcomeHost
import com.nuvio.tv.ui.screens.welcome.WhatsNewHost
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.LocalFastHorizontalNavigationEnabled
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.os.SystemClock
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.core.profile.relockDue
import kotlinx.coroutines.flow.catch
import java.io.IOException
import kotlinx.coroutines.launch

private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.WHITE,
    val font: AppFont = AppFont.INTER,
    val amoledMode: Boolean = false,
    val amoledSurfacesMode: Boolean = false,
    // True once every batch has emitted; the app shell waits for it so the first frame
    // never draws with placeholder theme values.
    val loaded: Boolean = false,
    val discoverLocation: DiscoverLocation? = null,
    val smoothBringIntoViewEnabled: Boolean = true,
    val fastHorizontalNavigationEnabled: Boolean = false,
    val composeHighlighterEnabled: Boolean = false
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeDataStore: ThemeDataStore

    @Inject
    lateinit var layoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    lateinit var startupSyncService: StartupSyncService

    @Inject
    lateinit var nightlySelfTrim: com.nuvio.tv.core.runtime.NightlySelfTrim

    @Inject
    lateinit var profileSyncService: ProfileSyncService

    @Inject
    lateinit var profileManager: ProfileManager

    @Inject
    lateinit var profileLockStateDataStore: ProfileLockStateDataStore

    @Inject
    lateinit var liveTvUserStore: LiveTvUserStore

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var deviceSessionRegistration: DeviceSessionRegistration

    @Inject
    lateinit var authSessionNoticeDataStore: AuthSessionNoticeDataStore

    @Inject
    lateinit var appOnboardingDataStore: AppOnboardingDataStore

    @Inject
    lateinit var avatarRepository: AvatarRepository

    @Inject
    lateinit var profileLookStore: ProfileLookStore

    @Inject
    lateinit var myPhotosStore: MyPhotosStore

    @Inject
    lateinit var trailerPlayerPool: com.nuvio.tv.core.player.TrailerPlayerPool

    @Inject
    lateinit var externalPlaybackTracker: com.nuvio.tv.core.player.ExternalPlaybackTracker

    @Inject
    lateinit var deepLinkHandler: DeepLinkHandler

    @Inject
    lateinit var screensaverController: com.nuvio.tv.core.player.ScreensaverController

    @Inject
    lateinit var updateController: com.nuvio.tv.core.update.UpdateController

    @Inject
    lateinit var tipLinks: com.nuvio.tv.core.tip.TipLinkRepository

    @Inject
    lateinit var packScanner: com.nuvio.tv.livetv.source.PackScanner

    private val pendingDeepLinkUrl = MutableStateFlow<String?>(null)

    /** Continue Watching launcher extras awaiting navigation; set from onCreate AND onNewIntent (G4-1). */
    private val pendingLauncherLaunch = MutableStateFlow<ContinueWatchingLaunch?>(null)

    private lateinit var jankStats: JankStats

    /** Activity-level launcher for external video players. Survives all navigation changes. */
    private val externalPlayerLauncher = registerForActivityResult(
        com.nuvio.tv.core.player.ExternalPlayerResultContract()
    ) { result ->
        Log.d("MainActivity", "External player ActivityResult: $result")
        externalPlaybackTracker.onActivityResult(result)
    }

    /** True after a screensaver wake until the waking press's ACTION_UP has been swallowed. */
    private var swallowKeysUntilUp = false

    /** Spec 62 H3: the idle / wake return to Who's watching (main thread only). */
    private val idleProfileReturn = IdleProfileReturn()
    /** A screen-off broadcast since the last ON_START: a backup for ReturnToPickerOnWake's ON_STOP check. */
    private var screenOffSinceStart = false

    // Registered for the activity's whole life: standby stops the activity before the broadcast.
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Only while stopped: a screen-off while on screen is caught by the ON_STOP check,
                // and one delivered late (after a quick off/on's ON_START) must not mark the next start.
                Intent.ACTION_SCREEN_OFF ->
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) screenOffSinceStart = true
                Intent.ACTION_DREAMING_STARTED -> idleProfileReturn.onDreamingStarted()
                Intent.ACTION_DREAMING_STOPPED -> idleProfileReturn.onDreamingStopped()
            }
        }
    }

    @OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun attachBaseContext(newBase: Context) {
        val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET }

        if (!tag.isNullOrEmpty()) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            // Cache not ready yet (very early cold start) — use system locale
            // The IO coroutine in Application.onCreate will finish before any activity
            // is usually created, but if not, we just use system locale until next launch
            super.attachBaseContext(newBase)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        StartupLatencyTrace.beginColdStart()
        installSplashScreen()
        super.onCreate(savedInstanceState)
        StartupLatencyTrace.markOnce("activity_create")
        window?.setBackgroundDrawable(null)

        // Wire the Activity-level launcher to the tracker
        externalPlaybackTracker.activityLauncher = externalPlayerLauncher

        // Spec 52 P7: a public build checks its releases at most once a day at app start.
        updateController.autoCheckIfDue()
        // Spec 81 T1: the tip link (stored answer, then tip.json at most once a day).
        tipLinks.refreshIfDue()

        // OLED screensaver: 1 Hz idle ticker while STARTED. collectLatest restarts the
        // loop (and the idle clock) whenever the enable/timeout settings change, and the
        // clock also restarts every time the activity returns to STARTED.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    themeDataStore.screensaverEnabled,
                    themeDataStore.screensaverTimeoutMinutes
                ) { enabled, minutes -> enabled to minutes }
                    .collectLatest { (enabled, minutes) ->
                        if (!enabled) {
                            screensaverController.notifyWake()
                            return@collectLatest
                        }
                        screensaverController.notifyInteraction()
                        while (true) {
                            delay(1_000)
                            screensaverController.maybeEngage(minutes * 60_000L)
                        }
                    }
            }
        }

        // Spec 73 D15 (Q2): while a channel pack scan runs (not paused), the system screensaver stays off. It put
        // Nuvio in the background, where the system killed the scan's process (S3c, Shield).
        lifecycleScope.launch {
            packScanner.state
                // Not while paused: what plays (or sits paused) owns the screen then.
                .map { it is com.nuvio.tv.livetv.source.PackScanner.State.Running && !it.paused }
                .distinctUntilChanged()
                .collect { running ->
                    val keepOn = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    if (running) window.addFlags(keepOn) else window.clearFlags(keepOn)
                }
        }

        // Spec 62 H3: standby and system-screensaver detection, then the idle check while on
        // screen (each pass returns the wait to the next one).
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_DREAMING_STOPPED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(
                        idleProfileReturn.checkIdle(
                            enabled = profileManager.returnToPickerEnabled.value == true,
                            externalPlayback = externalPlaybackTracker.isTracking,
                            screensaverShowing = screensaverController.overlayVisible.value
                        )
                    )
                }
            }
        }

        window?.decorView?.post {
            val snapshot = com.nuvio.tv.core.player.DisplayCapabilities.detect(this)
            com.nuvio.tv.core.player.DisplayCapabilities.logSummary(snapshot)
        }

        // Extract extras set by the Continue Watching launcher channel preview programs.
        // G4-1: routed through pendingLauncherLaunch so re-taps while the app is
        // already running (onNewIntent) navigate too, not just the cold onCreate read.
        // Fresh starts only (L5-11): recreate() (language change) and process-death restore
        // re-deliver the original intent, which would replay the Watch Next launch or the
        // install consent over the restored back stack. Warm deliveries reach onNewIntent.
        if (savedInstanceState == null) {
            pendingLauncherLaunch.value = parseContinueWatchingLaunch(intent)
            captureDeepLinkIntent(intent)
        }

        setContent {
            var hasSelectedProfileThisSession by rememberSaveable { mutableStateOf(false) }
            // The PIN map for the two ON_START returns below, null until loaded or on a read error.
            val returnPinFlow = remember(profileLockStateDataStore) {
                profileLockStateDataStore.pinEnabled
                    .map<Map<Int, Boolean>, Map<Int, Boolean>?> { it }
                    .catch { if (it is IOException) emit(null) else throw it }
            }
            val returnPinEnabled = returnPinFlow.collectAsState(initial = null)
            ReturnToPickerAfterTimeAway(profileManager, returnPinEnabled, profileLockStateDataStore, externalPlaybackTracker) {
                hasSelectedProfileThisSession = false
            }
            // Spec 62 H3 (D13): Who's watching is showing because of a return (a wake, 30 min away,
            // or idle). Set only when there is someone to switch to; the picker gate reads it with
            // hasSelectedProfileThisSession, and picking a profile (or the gate finding no one) clears it.
            var profileReturnPending by rememberSaveable { mutableStateOf(false) }
            ReturnToPickerOnWake(
                profileManager = profileManager,
                pinEnabled = returnPinEnabled,
                idleProfileReturn = idleProfileReturn,
                externalPlayback = externalPlaybackTracker,
                takeScreenOffSinceStart = { screenOffSinceStart.also { screenOffSinceStart = false } }
            ) {
                profileReturnPending = true
            }
            var onboardingCompletedThisSession by remember { mutableStateOf(false) }
            var onboardingProfileSyncInProgress by remember { mutableStateOf(false) }
            val hasSeenAuthQrFlow = remember(appOnboardingDataStore) {
                appOnboardingDataStore.hasSeenAuthQrOnFirstLaunch.map<Boolean, Boolean?> { it }
            }
            val hasSeenAuthQrOnFirstLaunch by hasSeenAuthQrFlow.collectAsState(initial = null)
            val authState by authManager.authState.collectAsState()
            val context = LocalContext.current

            LaunchedEffect(authSessionNoticeDataStore, context) {
                authSessionNoticeDataStore.pendingNotice.collect { notice ->
                    if (notice == StartupAuthNotice.NUVIO) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.auth_notice_nuvio_logged_out),
                            Toast.LENGTH_LONG
                        ).show()
                        authSessionNoticeDataStore.consumeNotice(notice)
                    }
                }
            }

            LaunchedEffect(hasSeenAuthQrOnFirstLaunch, authState) {
                if (hasSeenAuthQrOnFirstLaunch == false && authState is AuthState.FullAccount) {
                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                    onboardingCompletedThisSession = true
                }
            }

            val activeProfileId by profileManager.activeProfileId.collectAsState()
            val profiles by profileManager.profiles.collectAsState()
            val storedProfiles by profileManager.storedProfiles.collectAsState()
            val hasEverSelectedProfile by profileManager.hasEverSelectedProfile.collectAsState()
            val rememberLastProfileEnabled by profileManager.rememberLastProfileEnabled.collectAsState()
            val activeProfile = remember(activeProfileId, profiles) {
                profiles.firstOrNull { it.id == activeProfileId }
            }
            // PIN gate (L9-27): the persisted lock map is the source of truth, replaced by each
            // successful pull and kept on failure, so an offline start cannot unlock a profile.
            // null = not loaded yet, which the gate treats as "maybe locked".
            val profilePinStates by remember(profileLockStateDataStore) {
                profileLockStateDataStore.pinEnabled.map<Map<Int, Boolean>, Map<Int, Boolean>?> { it }
            }.collectAsState(initial = null)

            LaunchedEffect(authState, profiles) {
                if (authState is AuthState.FullAccount) {
                    profileSyncService.refreshProfileLockStates()
                }
            }

            // activeProfileId starts at the default 1 until the stored id loads, so the gate reads
            // the nullable stored id (one flow for id + readiness): until it loads the PIN state is
            // unknown, and the gate cannot check (or auto-enter) the wrong profile.
            val storedActiveProfileId by profileManager.storedActiveProfileId.collectAsState()
            val activeProfileHasPin: Boolean? = remember(storedActiveProfileId, profilePinStates) {
                activeProfilePinState(storedActiveProfileId, profilePinStates)
            }

            LaunchedEffect(hasEverSelectedProfile, activeProfileHasPin, rememberLastProfileEnabled) {
                if (rememberLastProfileEnabled && hasEverSelectedProfile && activeProfileHasPin == false && !hasSelectedProfileThisSession) {
                    hasSelectedProfileThisSession = true
                    if (authManager.authState.value is AuthState.FullAccount) {
                        startupSyncService.requestSyncNow()
                    }
                }
            }

            val avatarCatalog by avatarRepository.catalog.collectAsState()
            // Re-keyed on sign-in: signed out, the load skips the supporter catalog.
            val isFullAccount = authState is AuthState.FullAccount
            LaunchedEffect(isFullAccount) {
                avatarRepository.loadCatalog()
            }

            // Tagged with its profile id: after a switch the old flow's last value stays in the state
            // until the new one emits, and must not be read as the new profile's entry.
            val activeLookFlow = remember(activeProfileId) {
                profileLookStore.entryFor(activeProfileId).map { activeProfileId to it }
            }
            val activeLook by activeLookFlow.collectAsState(initial = null)
            val activeLookEntry = activeLook?.takeIf { it.first == activeProfileId }?.second
            val myPhotos by myPhotosStore.photos.collectAsState()
            val lookLibrary = remember(myPhotos) { LocalLookLibrary(myPhotos.orEmpty()) }
            val activeProfileAvatarArt = remember(activeProfile, avatarCatalog, activeLookEntry, lookLibrary) {
                activeProfile?.let { profile ->
                    resolveLookAvatar(profile, activeLookEntry, lookLibrary.sets, avatarRepository::getAvatarImageUrl)
                        ?.toArt(lookLibrary)
                }
            }

            val mainUiPrefsFlow = remember(
                themeDataStore,
                layoutPreferenceDataStore
            ) {
                // Group flows into batches to reduce intermediate flow allocations.
                // Each batch uses a single combine() instead of chaining .combine() calls,
                // which avoids N intermediate flow objects and redundant emissions on startup.
                val themeFlow = combine(
                    themeDataStore.selectedThemePreference,
                    themeDataStore.selectedFont,
                    themeDataStore.amoledMode,
                    themeDataStore.amoledSurfacesMode,
                ) { selectedTheme, font, amoledMode, amoledSurfacesMode ->
                    MainUiPrefs(
                        theme = resolveAppTheme(selectedTheme),
                        font = font,
                        amoledMode = amoledMode,
                        amoledSurfacesMode = amoledSurfacesMode,
                    )
                }
                val layoutFlow = combine(
                    layoutPreferenceDataStore.discoverLocation,
                    layoutPreferenceDataStore.smoothBringIntoViewEnabled,
                    layoutPreferenceDataStore.fastHorizontalNavigationEnabled,
                    layoutPreferenceDataStore.composeHighlighterEnabled,
                ) { discoverLocation, smoothBringIntoView, fastHorizontalNav, composeHighlighter ->
                    MainUiPrefs(
                        discoverLocation = discoverLocation,
                        smoothBringIntoViewEnabled = smoothBringIntoView,
                        fastHorizontalNavigationEnabled = fastHorizontalNav,
                        composeHighlighterEnabled = composeHighlighter,
                    )
                }
                combine(themeFlow, layoutFlow) { themePrefs, layoutPrefs ->
                    themePrefs.copy(
                        loaded = true,
                        discoverLocation = layoutPrefs.discoverLocation,
                        smoothBringIntoViewEnabled = layoutPrefs.smoothBringIntoViewEnabled,
                        fastHorizontalNavigationEnabled = layoutPrefs.fastHorizontalNavigationEnabled,
                        composeHighlighterEnabled = layoutPrefs.composeHighlighterEnabled
                    )
                }
            }
            val mainUiPrefs by mainUiPrefsFlow.collectAsState(initial = MainUiPrefs())
            val discoverLocation = mainUiPrefs.discoverLocation

            // Spec 69 M1: when each blank-screen gate below has its data, so a cold start's wait
            // is attributed to the gate that held it.
            StartupMarkWhen(hasSeenAuthQrOnFirstLaunch != null, "gate_onboarding_flag")
            StartupMarkWhen(authState !is AuthState.Loading, "gate_auth_resolved")
            StartupMarkWhen(activeProfileHasPin != null, "gate_profile_lock")
            StartupMarkWhen(mainUiPrefs.loaded, "gate_ui_prefs")

            val uiScalePercent by com.nuvio.tv.data.local.UiScalePreference.flow(applicationContext).collectAsState(initial = 100)
            NuvioTheme(
                appTheme = mainUiPrefs.theme,
                appFont = mainUiPrefs.font,
                amoledMode = mainUiPrefs.amoledMode,
                amoledSurfacesMode = mainUiPrefs.amoledSurfacesMode,
                uiScalePercent = uiScalePercent
            ) {
                val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
                val bringIntoViewSpec = if (mainUiPrefs.smoothBringIntoViewEnabled) {
                    NuvioScrollDefaults.smoothScrollSpec
                } else {
                    defaultBringIntoViewSpec
                }
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides bringIntoViewSpec,
                    LocalFastHorizontalNavigationEnabled provides mainUiPrefs.fastHorizontalNavigationEnabled,
                    LocalRecompositionHighlighterEnabled provides (BuildConfig.IS_DEBUG_BUILD && mainUiPrefs.composeHighlighterEnabled),
                    com.nuvio.tv.core.player.LocalTrailerPlayerPool provides trailerPlayerPool
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(
                        containerColor = NuvioTheme.colors.Background
                    )
                ) {
                    val screensaverVisible by screensaverController.overlayVisible.collectAsState()
                    val screensaverDimPercent by themeDataStore.screensaverDimPercent.collectAsState(
                        initial = ThemeDataStore.DEFAULT_SCREENSAVER_DIM_PERCENT
                    )
                    ScreensaverOverlay(
                        visible = screensaverVisible,
                        dimPercent = screensaverDimPercent
                    )

                    // Spec 62 H3 (D13): a return decided while resumed (the idle timeout with no
                    // screensaver, or the key press dismissing it with a return owed). No launch
                    // can be in flight while resumed, so it applies at once.
                    val profileReturnRequested by idleProfileReturn.requested.collectAsState()
                    LaunchedEffect(profileReturnRequested) {
                        if (!profileReturnRequested) return@LaunchedEffect
                        idleProfileReturn.consume()
                        // Re-checked: playback may have started between the raise and this frame.
                        if (!idleProfileReturn.inPlayback &&
                            profileReturnHasTarget(storedProfiles?.size, activeProfileHasPin)
                        ) {
                            profileReturnPending = true
                        }
                    }

                    // Must start unloaded: ReturnToPickerAfterTimeAway's restored-process invariant.
                    if (hasSeenAuthQrOnFirstLaunch == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }

                    if (authState is AuthState.Loading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }

                    if (
                        hasSeenAuthQrOnFirstLaunch == false &&
                        authState !is AuthState.FullAccount &&
                        !onboardingCompletedThisSession
                    ) {
                        StartupFrameMark("first_frame_qr_signin")
                        AuthQrSignInScreen(
                            onBackPress = { finish() },
                            onContinue = {
                                lifecycleScope.launch {
                                    val shouldRunRemoteOnboardingSync =
                                        authManager.authState.value is AuthState.FullAccount

                                    if (shouldRunRemoteOnboardingSync) {
                                        if (onboardingProfileSyncInProgress) return@launch
                                        onboardingProfileSyncInProgress = true
                                        val maxAttempts = 3
                                        var synced = false
                                        for (attempt in 0 until maxAttempts) {
                                            val result = profileSyncService.pullFromRemote()
                                            if (result.isSuccess) {
                                                synced = true
                                                break
                                            }
                                            if (attempt < maxAttempts - 1) {
                                                delay(1_000)
                                            }
                                        }
                                        if (!synced) {
                                            android.util.Log.w(
                                                "MainActivity",
                                                "Onboarding profile sync failed after retries; continuing"
                                            )
                                        }
                                    }
                                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                                    onboardingCompletedThisSession = true
                                    onboardingProfileSyncInProgress = false
                                }
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                        return@Surface
                    }

                    val needsProfilePick = !hasSelectedProfileThisSession || profileReturnPending
                    if (needsProfilePick && (activeProfileHasPin == null || (profileReturnPending && storedProfiles == null))) {
                        // Lock map or active profile (or, for a return, the profile list) still loading:
                        // show neither the picker nor the app yet.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }

                    // At startup the placeholder list (one profile) counts until the stored one loads.
                    val shouldShowProfileSelection = needsProfilePick &&
                        profileReturnHasTarget(storedProfiles?.size ?: profiles.size, activeProfileHasPin)
                    if (profileReturnPending && !shouldShowProfileSelection) {
                        // No one to switch to after all (the PIN state or profile list was still
                        // loading when it was set): drop it, so a profile or PIN added later never
                        // pops the picker. Reached only once both have loaded (the gate above).
                        SideEffect { profileReturnPending = false }
                    }

                    if (shouldShowProfileSelection) {
                        StartupFrameMark("first_frame_profile_picker")
                        ProfileSelectionScreen(
                            onProfileSelected = {
                                hasSelectedProfileThisSession = true
                                profileReturnPending = false
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                        return@Surface
                    }

                    if (!mainUiPrefs.loaded) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }
                    val pendingDeepLink by pendingDeepLinkUrl.collectAsState()
                    // Set by the nav-level deep-link effect below. Consent gate (2026-08-26
                    // review): never install from a link without the user seeing the host and
                    // confirming.
                    var pendingInstallConsent by remember { mutableStateOf<String?>(null) }
                    PendingAddonInstallConsent(
                        pendingManifestUrl = pendingInstallConsent,
                        onConfirm = { manifestUrl -> installConsentedAddon(manifestUrl) },
                        onCleared = { pendingInstallConsent = null }
                    )

                    val navController = rememberNavController()
                    // G6: nav ViewModelStore hoisted beside its controller; see OwnedNavHostSection.
                    val navViewModelStoreOwner = remember {
                        object : ViewModelStoreOwner {
                            override val viewModelStore: ViewModelStore = ViewModelStore()
                        }
                    }
                    DisposableEffect(navViewModelStoreOwner) {
                        onDispose { navViewModelStoreOwner.viewModelStore.clear() }
                    }
                    var optimisticRoute by remember { mutableStateOf<String?>(null) }
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val actualRoute = navBackStackEntry?.destination?.route
                    val currentRoute = optimisticRoute ?: actualRoute

                    // Spec 62 H3: never return over playback; the idle time starts again when it
                    // ends. Collected in an effect, so a playback change does not recompose here.
                    LaunchedEffect(navController) {
                        try {
                            combine(
                                snapshotFlow {
                                    (optimisticRoute ?: navBackStackEntry?.destination?.route) == Screen.Player.route
                                },
                                screensaverController.playbackActive
                            ) { playerRoute, playing -> playerRoute || playing }
                                .distinctUntilChanged()
                                .collect { idleProfileReturn.setInPlayback(it) }
                        } finally {
                            idleProfileReturn.setInPlayback(false)
                        }
                    }

                    LaunchedEffect(actualRoute) {
                        optimisticRoute = null
                    }
                    // actualRoute settles a frame after the NavHost first composes Home, so this
                    // mark reads up to two frames after Home first drew; a launcher or deep-link
                    // start that lands past Home logs it only on the way back.
                    if (actualRoute == Screen.Home.route) {
                        StartupFrameMark("first_frame_home")
                    }

                    // Auto-play next episode for EXTERNAL players: the tracker resolves the
                    // next episode and we navigate into the same Screen.Stream auto-play route
                    // the internal onPlaybackEnded path uses. Collected from the root composable
                    // so it survives StreamScreen's self-pop and a process kill (metadata is
                    // recovered from disk and the event replayed).
                    LaunchedEffect(navController) {
                        externalPlaybackTracker.autoPlayNext.collect { next ->
                            if (!externalPlaybackTracker.claimAutoPlayNextNavigation(next)) {
                                return@collect
                            }
                            Log.d(
                                "MainActivity",
                                "autoPlayNext received: S${next.nextSeason}E${next.nextEpisode} " +
                                    "videoId=${next.nextVideoId}; navigating to Stream"
                            )
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = next.nextVideoId,
                                    contentType = next.contentType,
                                    title = next.contentName,
                                    poster = next.poster,
                                    backdrop = next.backdrop,
                                    logo = next.logo,
                                    season = next.nextSeason,
                                    episode = next.nextEpisode,
                                    year = next.year,
                                    contentId = next.contentId,
                                    contentName = next.contentName,
                                    returnToDetailOnBack = next.contentType.equals("series", ignoreCase = true)
                                )
                            ) {
                                // Replace any lingering Stream screen (e.g. the previous
                                // episode's, restored from the backstack after a process restart).
                                popUpTo(Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }

                    // Navigate to content when launched from the Continue Watching channel row.
                    // G4-1: keyed on the pending launch (like the deep-link effect below) so
                    // launcher taps while the app is open navigate instead of being dropped.
                    val launcherLaunch by pendingLauncherLaunch.collectAsState()
                    LaunchedEffect(navController, launcherLaunch) {
                        val launch = launcherLaunch ?: return@LaunchedEffect
                        pendingLauncherLaunch.value = null
                        if (launch.launchMode == "stream" && launch.videoId != null && launch.name != null) {
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = launch.videoId,
                                    contentType = launch.contentType,
                                    title = launch.name,
                                    poster = launch.poster,
                                    backdrop = launch.backdrop,
                                    logo = launch.logo,
                                    season = launch.season,
                                    episode = launch.episode,
                                    episodeName = launch.episodeTitle,
                                    contentId = launch.contentId,
                                    contentName = launch.name,
                                    returnToDetailOnBack = launch.contentType.equals("series", ignoreCase = true),
                                    returnToHomeOnBack = true
                                )
                            ) {
                                launchSingleTop = true
                            }
                        } else {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = launch.contentId,
                                    itemType = launch.contentType
                                )
                            ) {
                                launchSingleTop = true
                            }
                        }
                    }

                    LaunchedEffect(navController, pendingDeepLink) {
                        val url = pendingDeepLink ?: return@LaunchedEffect
                        when (val deepLink = DeepLinkParser.parse(url)) {
                            is AppDeepLink.Meta -> {
                                pendingDeepLinkUrl.value = null
                                // Skip only a repeat of the title already on top. launchSingleTop
                                // matched any Details (one route pattern) and reused that entry and
                                // its ViewModel, so a link for another title was dropped (F-3).
                                val top = navController.currentBackStackEntry
                                val alreadyOnTop = top?.destination?.route == Screen.Detail.route &&
                                    top.arguments?.getString("itemId") == deepLink.id &&
                                    top.arguments?.getString("itemType") == deepLink.type
                                if (!alreadyOnTop) {
                                    navController.navigate(
                                        Screen.Detail.createRoute(
                                            itemId = deepLink.id,
                                            itemType = deepLink.type
                                        )
                                    )
                                }
                            }
                            is AppDeepLink.AddonInstall -> {
                                pendingDeepLinkUrl.value = null
                                navController.navigate(Screen.AddonManager.route) {
                                    launchSingleTop = true
                                }
                                pendingInstallConsent = deepLink.manifestUrl
                            }
                            null -> {
                                pendingDeepLinkUrl.value = null
                            }
                        }
                    }

                    val view = LocalView.current
                    LaunchedEffect(currentRoute) {
                        val holder = PerformanceMetricsState.getHolderForHierarchy(view)
                        if (currentRoute != null) {
                            holder.state?.putState("Screen", currentRoute)
                        }
                    }

                    // Back to Home with a fresh stack: the route-closing effects below share it.
                    val ejectToHome: () -> Unit = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = false }
                            launchSingleTop = true
                        }
                    }
                    LaunchedEffect(discoverLocation, currentRoute) {
                        if (discoverLocation == null) return@LaunchedEffect
                        val onDiscoverRoute = currentRoute == Screen.Discover.route ||
                            currentRoute?.startsWith("${Screen.Discover.route}/") == true
                        if (discoverLocation == DiscoverLocation.OFF && onDiscoverRoute) {
                            ejectToHome()
                        }
                    }

                    // T1 settings lock: non-primary profiles cannot enter Settings.
                    // Blocked Settings-tab clicks show a notice instead of navigating. The
                    // eject below guards the live deep-link path into AddonManager (the
                    // install consent dialog survives over the ejected Home) and stays
                    // as defense-in-depth: a profile switch rebuilds this nav subtree
                    // from Home (see the navViewModelStoreOwner note), so a settings
                    // route cannot otherwise be on top for a non-primary.
                    // T5: the primary's Settings-access grant (Settings → Profiles)
                    // lifts the whole lock; absent/false keeps it. Initial false is
                    // the fail-safe while the store loads.
                    val isPrimaryProfileActive = activeProfileId == 1
                    val settingsAccessGranted by profileLockStateDataStore.settingsAccessGranted
                        .collectAsState(initial = false)
                    val blockedTopBarRoutes = remember(isPrimaryProfileActive, settingsAccessGranted) {
                        SettingsAccess.blockedTopBarRoutes(isPrimaryProfileActive, settingsAccessGranted)
                    }
                    LaunchedEffect(isPrimaryProfileActive, settingsAccessGranted, currentRoute) {
                        if (SettingsAccess.shouldEjectFromRoute(currentRoute, isPrimaryProfileActive, settingsAccessGranted)) {
                            ejectToHome()
                        }
                    }
                    // Spec 34 D17: a profile with Live TV off has no tab. The eject is defence in depth,
                    // as the settings one: a profile switch already rebuilds this nav subtree from Home,
                    // and the switch is only changed from Manage profiles. Null while loading: the tab
                    // waits, nothing ejects.
                    val liveTvEnabled by remember { liveTvUserStore.activeEnabled }
                        .collectAsState(initial = null)
                    LaunchedEffect(liveTvEnabled, currentRoute) {
                        if (liveTvEnabled == false && isLiveTvRoute(currentRoute)) {
                            ejectToHome()
                        }
                    }
                    val toast = rememberMarqueeToastState()
                    val onBlockedTopBarRoute: () -> Unit = {
                        toast.show(context.getString(R.string.settings_managed_by_primary))
                    }

                    val strNavHome = stringResource(R.string.nav_home)
                    val strNavLiveTv = stringResource(R.string.nav_live_tv)
                    val strNavDiscover = stringResource(R.string.nav_discover)
                    val strNavSearch = stringResource(R.string.nav_search)
                    val strNavLibrary = stringResource(R.string.nav_library)
                    val strNavSettings = stringResource(R.string.nav_settings)
                    val topBarTabs = remember(
                        strNavHome,
                        strNavLiveTv,
                        strNavDiscover,
                        strNavSearch,
                        strNavLibrary,
                        strNavSettings,
                        discoverLocation,
                        liveTvEnabled
                    ) {
                        val labels = mapOf(
                            Screen.Home.route to strNavHome,
                            Screen.LiveTv.route to strNavLiveTv,
                            Screen.Search.route to strNavSearch,
                            Screen.Discover.route to strNavDiscover,
                            Screen.Library.route to strNavLibrary,
                            Screen.Settings.route to strNavSettings
                        )
                        topBarTabRoutes(discoverLocation, liveTvEnabled).map { route ->
                            TopBarTab(route = route, label = labels.getValue(route))
                        }
                    }
                    val topBarProfile = activeProfile?.let { profile ->
                        TopBarProfile(
                            name = profile.name,
                            colorHex = profile.avatarColorHex,
                            avatarArt = activeProfileAvatarArt
                        )
                    }

                    // Spec 52 T2: the top bar's Update pill.
                    val showUpdatePill by updateController.notice.collectAsState()
                    val pulseUpdatePill by updateController.pulseRelease.collectAsState()

                    Box(modifier = Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalMarqueeToast provides toast) {
                            TopBarScaffold(
                                navController = navController,
                                navViewModelStoreOwner = navViewModelStoreOwner,
                                startDestination = Screen.Home.route,
                                currentRoute = currentRoute,
                                tabs = topBarTabs,
                                blockedRoutes = blockedTopBarRoutes,
                                onBlockedRoute = onBlockedTopBarRoute,
                                profile = topBarProfile,
                                onSwitchProfile = { hasSelectedProfileThisSession = false },
                                onNavigate = { optimisticRoute = it },
                                onExitApp = {
                                    finishAffinity()
                                    finishAndRemoveTask()
                                },
                                showUpdate = showUpdatePill,
                                pulseUpdate = pulseUpdatePill,
                                onUpdatePulsed = updateController::pillPulsed
                            )
                        }

                        val autoNextOverlay by externalPlaybackTracker.autoNextOverlay.collectAsState()
                        autoNextOverlay?.let { ov ->
                            MarqueeLoadingScreen(
                                visible = true,
                                backdropUrl = ov.backdrop,
                                title = ov.title.orEmpty(),
                                logo = ov.logo,
                                contentType = ov.contentType,
                                season = null,
                                episode = null,
                                episodeName = null,
                                year = ov.year,
                                accent = rememberAmbientAccentProvider(ov.backdrop),
                                message = ov.message ?: stringResource(R.string.external_auto_next_loading),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        // The cards' ways into Settings (tab-style from Home); null while Settings
                        // is locked for this profile, which hides those buttons.
                        val settingsLocked = Screen.Settings.route in blockedTopBarRoutes
                        val openSettingsFromCard: ((SettingsEntry, Int?) -> Unit)? =
                            if (settingsLocked) null else {
                                { entry, release ->
                                    optimisticRoute = Screen.Settings.route
                                    navController.openSettingsAt(entry, release) {
                                        navigateToTopBarRoute(navController, Screen.Settings.route)
                                    }
                                }
                            }
                        // Spec 81 D4: the cards' Tip, hidden with no link and on a locked profile.
                        val tipLink by tipLinks.link.collectAsState()
                        val cardTipLink = tipLink.takeIf { tipEntryShown(it, settingsLocked) }
                        // Spec 33 D8: the first-launch welcome card, after every gate above; it
                        // waits on Home (a launcher or deep-link start lands elsewhere first).
                        // Spec 80 D15: its Changelog opens About › Changelog with the list open.
                        DeviceWelcomeHost(
                            onHome = currentRoute == Screen.Home.route,
                            onReview = openSettingsFromCard?.let { open -> { open(SettingsEntry.NETWORK, null) } },
                            onChangelog = openSettingsFromCard?.let { open -> { open(SettingsEntry.CHANGELOG, null) } },
                            tipLink = cardTipLink
                        )
                        // Spec 80 D1: the What's new card, once after an update, after Welcome (it
                        // waits for that card to settle) and on Home like it.
                        WhatsNewHost(
                            onHome = currentRoute == Screen.Home.route,
                            onWatchVideo = { url, title ->
                                navController.navigate(
                                    Screen.Player.createRoute(streamUrl = url, title = title, releaseVideo = true)
                                )
                            },
                            onReadNotes = openSettingsFromCard?.let { open ->
                                { versionCode -> open(SettingsEntry.CHANGELOG, versionCode) }
                            },
                            tipLink = cardTipLink
                        )
                        MarqueeToastHost(state = toast, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
            }
        }

        jankStats = JankStats.createAndTrack(window) { frameData ->
            if (frameData.isJank) {
                Log.w(
                    "JankStats",
                    "JANK: ${frameData.frameDurationUiNanos / 1_000_000}ms | states: ${frameData.states}"
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = true
        lifecycleScope.launch {
            deviceSessionRegistration.requestForegroundRegistration()
            startupSyncService.requestForegroundSync()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureDeepLinkIntent(intent)
        // G4-1: launcher "Continue Watching" extras were only read in onCreate,
        // so the shortcut was inert whenever the app was already running.
        // Conditional write mirrors captureDeepLinkIntent: a plain MAIN re-launch
        // (no extras) must not cancel a still-pending episode launch.
        parseContinueWatchingLaunch(intent)?.let { pendingLauncherLaunch.value = it }
    }

    /** Launcher-channel extras for one pending Continue Watching navigation (G4-1). */
    private data class ContinueWatchingLaunch(
        val contentId: String,
        val contentType: String,
        val launchMode: String?,
        val videoId: String?,
        val name: String?,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val season: Int?,
        val episode: Int?,
        val episodeTitle: String?
    )

    private fun parseContinueWatchingLaunch(intent: Intent?): ContinueWatchingLaunch? {
        val contentId = intent?.getStringExtra("contentId") ?: return null
        val contentType = intent.getStringExtra("contentType") ?: return null
        return ContinueWatchingLaunch(
            contentId = contentId,
            contentType = contentType,
            launchMode = intent.getStringExtra("launchMode"),
            videoId = intent.getStringExtra("videoId"),
            name = intent.getStringExtra("name"),
            poster = intent.getStringExtra("poster"),
            backdrop = intent.getStringExtra("backdrop"),
            logo = intent.getStringExtra("logo"),
            season = intent.getIntExtra("season", -1).takeIf { it >= 0 },
            episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 },
            episodeTitle = intent.getStringExtra("episodeTitle")
        )
    }

    private fun captureDeepLinkIntent(intent: Intent?) {
        val url = intent?.dataString?.trim()?.takeIf(String::isNotBlank) ?: return
        pendingDeepLinkUrl.value = url
    }

    /**
     * Runs a consented deep-link addon install on the Activity scope (L5-01): confirming
     * removes the consent dialog from composition, so a dialog-owned scope would cancel the
     * install mid-fetch and the addon would never be added.
     */
    private fun installConsentedAddon(manifestUrl: String) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, getString(R.string.addon_installing), Toast.LENGTH_SHORT).show()
            val result = deepLinkHandler.installAddon(manifestUrl)
            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = false
    }

    // Intercept Back at the Activity level, before any Compose BackHandler, so the auto-next loader
    // can always be dismissed. Compose back-dispatch ordering kept putting the destination screen's
    // handler above the loader's, so Back never reached it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        idleProfileReturn.onUse()
        // OLED screensaver: while the dim overlay is visible, the first key press wakes
        // it and the whole press (down, repeats, and the matching up) is swallowed so
        // waking never also navigates. Stray key-ups after wake are swallowed too, so
        // ACTION_UP handlers below never see an up without its down.
        if (screensaverController.overlayVisible.value) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // Spec 62 H3 (D13): a return owed behind the screensaver is decided at this press.
                idleProfileReturn.onScreensaverDismissed(profileManager.returnToPickerEnabled.value == true)
                screensaverController.notifyWake()
                swallowKeysUntilUp = true
            }
            return true
        }
        if (swallowKeysUntilUp) {
            if (event.action == KeyEvent.ACTION_UP) {
                swallowKeysUntilUp = false
            }
            return true
        }
        screensaverController.notifyInteraction()
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            externalPlaybackTracker.autoNextOverlay.value != null
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                Log.d("ExtAutoNext", "dispatchKeyEvent BACK -> dismissAutoNextOverlay (loader showing)")
                externalPlaybackTracker.dismissAutoNextOverlay()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Compose Dialogs are separate platform windows: their keys never reach this
        // Activity, so idle detection is blind while one is focused. Pause screensaver
        // eligibility while this window lacks focus; refocus restarts the idle clock.
        screensaverController.setWindowFocused(hasFocus)
        // Spec 62 H3: the idle check pauses likewise, except under the system screensaver.
        idleProfileReturn.onWindowFocusChanged(hasFocus)
    }

    override fun onStart() {
        // Returning from an external player: raise the auto-next loader before the player's
        // result is dispatched and before the window repaints, so the transition shows the
        // loader instantly with no episode-list flash. No-op unless a series episode is being
        // tracked; onActivityResult keeps it for a completion or dismisses it otherwise.
        externalPlaybackTracker.raiseAutoNextOverlayOnReturn()
        super.onStart()
        startupSyncService.startPeriodicSurfacePulls()
        nightlySelfTrim.onAppForegrounded()
    }

    override fun onStop() {
        externalPlaybackTracker.onExternalPlayerCoveredApp()
        super.onStop()
        startupSyncService.stopPeriodicSurfacePulls()
        // M3: first background after the 04:00 window runs the nightly self-trim
        // (disk-cache trim + idle exit); no-op while foreground by construction.
        runCatching { nightlySelfTrim.onAppBackgrounded() }
    }

    override fun onDestroy() {
        // The @Singleton tracker must not retain this destroyed activity through its launcher;
        // a newer activity may already have installed its own in onCreate.
        if (externalPlaybackTracker.activityLauncher === externalPlayerLauncher) {
            externalPlaybackTracker.activityLauncher = null
        }
        unregisterReceiver(screenStateReceiver)
        super.onDestroy()
    }
}

/**
 * Spec 64 K3 (D15, D16): when the app comes back on screen after the Ask for PIN again time and the
 * active profile has a PIN, [onRelock] sends it to Who's watching, which asks for it. ON_STOP stamps
 * the since-boot clock (saveable, so a restored process is checked too: ON_START replays to the new
 * observer), except while an external player is tracked (a film on screen never counts). ON_START
 * decides synchronously from values collected beforehand ([relockDue]); unloaded or unreadable
 * state fails closed (re-locks sooner). Composed at the top of setContent, above every gate, so no
 * gate flip while stopped can drop the stamp. Invariant (review 6d2a2348): the main UI must never
 * compose in the same pass as this effect's first run, or a restored process's Continue Watching /
 * deep-link launch effect runs in the same apply as the replayed ON_START and is lost behind the
 * PIN. Today the loading gates start unloaded (hasSeenAuthQrOnFirstLaunch null), so the first pass
 * never reaches the main UI and those launches stay pending until a profile is picked.
 */
@Composable
private fun ReturnToPickerAfterTimeAway(
    profileManager: ProfileManager,
    pinEnabled: State<Map<Int, Boolean>?>,
    lockStateDataStore: ProfileLockStateDataStore,
    externalPlayback: com.nuvio.tv.core.player.ExternalPlaybackTracker,
    onRelock: () -> Unit
) {
    val minutesFlow = remember(lockStateDataStore) {
        lockStateDataStore.relockAfterMinutes
            .map<Int, Int?> { it }
            .catch { if (it is IOException) emit(null) else throw it }
    }
    // The State objects themselves ([pinEnabled] too), read in the observer: rememberUpdatedState
    // copies refresh only on recomposition, which is paused while the activity is stopped (a PIN
    // added meanwhile).
    val relockMinutes = minutesFlow.collectAsState(initial = null)
    val latestOnRelock by rememberUpdatedState(onRelock)
    val lifecycleOwner = LocalLifecycleOwner.current
    var stoppedAtMs by rememberSaveable { mutableStateOf<Long?>(null) }
    DisposableEffect(lifecycleOwner, profileManager, externalPlayback) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP ->
                    stoppedAtMs = if (externalPlayback.isTracking) null else SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    val stoppedAt = stoppedAtMs
                    stoppedAtMs = null
                    val activeId = profileManager.storedActiveProfileId.value
                    if (relockDue(stoppedAt, SystemClock.elapsedRealtime(), relockMinutes.value, pinEnabled.value, activeId)) {
                        latestOnRelock()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Spec 62 H3 (D3, D8, D13): at ON_START, after a standby, 30 minutes away or with a return owed
 * behind the screensaver, [onReturn] sends the app to Who's watching when there is someone to
 * switch to. Decided synchronously in the observer, as [ReturnToPickerAfterTimeAway] does,
 * so a launcher or deep-link launch arriving with the wake waits behind the picker instead of
 * composing first. Standby is read at ON_STOP (the screen is no longer interactive) into saveable
 * state, so a process that dies during standby still returns on wake (unless the setting has not
 * loaded yet at that ON_START: unknown counts as off); [takeScreenOffSinceStart] (a screen-off
 * broadcast while stopped, not saved) backs it up. Composed beside that relock, under the same
 * invariant.
 */
@Composable
private fun ReturnToPickerOnWake(
    profileManager: ProfileManager,
    pinEnabled: State<Map<Int, Boolean>?>,
    idleProfileReturn: IdleProfileReturn,
    externalPlayback: com.nuvio.tv.core.player.ExternalPlaybackTracker,
    takeScreenOffSinceStart: () -> Boolean,
    onReturn: () -> Unit
) {
    val context = LocalContext.current
    val latestOnReturn by rememberUpdatedState(onReturn)
    val latestTakeScreenOff by rememberUpdatedState(takeScreenOffSinceStart)
    val lifecycleOwner = LocalLifecycleOwner.current
    var sleptSinceStop by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, profileManager, idleProfileReturn, externalPlayback) {
        val power = context.getSystemService(PowerManager::class.java)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP ->
                    if (power?.isInteractive == false) sleptSinceStop = true
                Lifecycle.Event.ON_START -> {
                    val slept = sleptSinceStop or latestTakeScreenOff()
                    sleptSinceStop = false
                    if (idleProfileReturn.onAppStart(
                            enabled = profileManager.returnToPickerEnabled.value == true,
                            slept = slept,
                            externalPlayback = externalPlayback.isTracking
                        )
                    ) {
                        val activeHasPin = activeProfilePinState(profileManager.storedActiveProfileId.value, pinEnabled.value)
                        if (profileReturnHasTarget(profileManager.storedProfiles.value?.size, activeHasPin)) latestOnReturn()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** Spec 69 M1: logs [name] once per process, the first time [ready] holds in composition. */
@Composable
private fun StartupMarkWhen(ready: Boolean, name: String) {
    if (ready) {
        LaunchedEffect(Unit) { StartupLatencyTrace.markOnce(name) }
    }
}

/**
 * Spec 69 M1: logs [name] once per process when the frame that first composed the caller has
 * been drawn (the next frame's callback, so at most one frame late).
 */
@Composable
private fun StartupFrameMark(name: String) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        StartupLatencyTrace.markOnce(name)
    }
}

/**
 * Composes the install-consent dialog when a pending manifest URL exists. Composed
 * once in MainActivity, fed by the nav-level deep-link effect.
 */
@Composable
private fun PendingAddonInstallConsent(
    pendingManifestUrl: String?,
    onConfirm: (String) -> Unit,
    onCleared: () -> Unit
) {
    pendingManifestUrl?.let { manifestUrl ->
        AddonInstallConsentDialog(
            manifestUrl = manifestUrl,
            onConfirm = onConfirm,
            onDismiss = onCleared
        )
    }
}

/**
 * Consent gate for addon installs arriving via nuvio:// / stremio:// deep links
 * (2026-08-26 review major: links could install addons with only a toast). Shows
 * the resolved host; install runs only on explicit confirm. Presentational only:
 * [onConfirm] runs the install on a scope that outlives this dialog.
 */
@Composable
private fun AddonInstallConsentDialog(
    manifestUrl: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val host = runCatching { URI(manifestUrl).host }.getOrNull() ?: manifestUrl
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.addon_install_confirm_title),
        subtitle = stringResource(R.string.addon_install_confirm_subtitle, host)
    ) {
        PanelActionRow(
            label = stringResource(R.string.addon_install_confirm_install),
            onClick = {
                onDismiss()
                onConfirm(manifestUrl)
            }
        )
        PanelActionRow(
            label = stringResource(R.string.addon_install_confirm_cancel),
            onClick = onDismiss
        )
    }
}

object LocaleCache {
    const val UNSET = "__UNSET__"

    /** Where the chosen app language lives: a SharedPreferences file read before any Activity. */
    const val PREFS_NAME = "app_locale"
    const val PREFS_KEY_TAG = "locale_tag"

    @Volatile
    var localeTag: String = UNSET
}
