package com.nuvio.tv.ui.screens.profile

import com.nuvio.tv.ui.theme.NuvioMotion

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.annotation.StringRes
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.PinVerifyOutcome
import com.nuvio.tv.core.profile.PinLockouts
import android.os.SystemClock
import com.nuvio.tv.core.sync.SetProfilePinResult
import com.nuvio.tv.data.remote.supabase.ProfileBackgroundCatalogItem
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.CustomProfileBackgroundImage
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.ui.components.ProfileBackgroundImage
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.components.parseProfileColor
import com.nuvio.tv.domain.model.ProfileBackgroundSelection
import com.nuvio.tv.domain.model.resolveLookAvatar
import com.nuvio.tv.domain.model.resolveLookBackground
import com.nuvio.tv.ui.components.AvatarArt
import com.nuvio.tv.domain.model.ProfileLookPick
import com.nuvio.tv.ui.components.BundledBackground
import com.nuvio.tv.ui.components.BundledBackgroundImage
import com.nuvio.tv.ui.components.BundledBackgrounds
import com.nuvio.tv.data.local.MyPhoto
import com.nuvio.tv.domain.model.photoUsers
import com.nuvio.tv.ui.components.LocalLookLibrary
import com.nuvio.tv.ui.components.PhotoBackgroundImage
import com.nuvio.tv.ui.components.toArt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.components.PinKeypad
import androidx.compose.runtime.mutableIntStateOf
import com.nuvio.tv.ui.util.holdReleaseGuard
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.components.PanelRule
import com.nuvio.tv.ui.components.PanelEyebrow
import com.nuvio.tv.ui.components.marquee.MarqueeConfirmPanel
import com.nuvio.tv.ui.components.marquee.MarqueeSidePanel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import com.nuvio.tv.ui.components.marquee.MarqueeCard
import com.nuvio.tv.ui.components.marquee.MarqueeToastHost
import com.nuvio.tv.ui.components.marquee.rememberMarqueeToastState
import com.nuvio.tv.ui.screens.detail.DetailKeyHint
import com.nuvio.tv.ui.screens.home.MarqueeBreadcrumb
import com.nuvio.tv.TopBarClock
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.nuvio.tv.ui.util.BlurTransformation

private object ProfileSelectionSpacing {
    val ScreenPaddingHorizontal = NuvioTheme.spacing.huge
}

private sealed interface ProfileBackgroundArtwork {
    data class Catalog(val background: ProfileBackgroundCatalogItem) : ProfileBackgroundArtwork
    data class Custom(val url: String) : ProfileBackgroundArtwork
    data class Bundled(val background: BundledBackground) : ProfileBackgroundArtwork
    data class Photo(val photo: MyPhoto) : ProfileBackgroundArtwork
}

/** The artwork for a background choice; a catalog item not loaded yet (or failed) gives none. */
private fun ProfileBackgroundSelection?.toArtwork(
    catalogById: Map<String, ProfileBackgroundCatalogItem>,
    library: LocalLookLibrary
): ProfileBackgroundArtwork? = when (this) {
    is ProfileBackgroundSelection.Catalog -> catalogById[id]?.takeIf { it.imageFile != null }?.let(ProfileBackgroundArtwork::Catalog)
    is ProfileBackgroundSelection.Custom -> ProfileBackgroundArtwork.Custom(url)
    is ProfileBackgroundSelection.Local -> when (val pick = pick) {
        is ProfileLookPick.Bundled -> BundledBackgrounds.bySlug[pick.slug]?.let(ProfileBackgroundArtwork::Bundled)
        is ProfileLookPick.Photo -> library.photosById[pick.id]?.let(ProfileBackgroundArtwork::Photo)
    }
    null -> null
}

/** What the field shows: a chosen [artwork] wins, else the [avatarArt], else the colour gradient. */
private data class ProfileFieldTarget(
    val artwork: ProfileBackgroundArtwork?,
    val avatarArt: AvatarArt?
)

// The avatar-art field: decoded small (it is blurred and scaled up anyway), then stack-blurred once
// (the Person page's field values).
private const val ART_DECODE_WIDTH_PX = 160
private const val ART_DECODE_HEIGHT_PX = 240
private const val ART_BLUR_RADIUS = 16

private val ProfileCardFocusEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val ProfilePinLength = com.nuvio.tv.core.profile.ProfileManager.PIN_LENGTH

/**
 * An open options panel: its [profile], whether a hold opened it (its release must be swallowed),
 * and the PIN typed to open it on the startup picker (Q4; lets Change PIN skip its ask). One value,
 * so closing the panel drops the PIN with it.
 */
private class ProfileOptionsRequest(
    val profile: UserProfile,
    val openedByHold: Boolean,
    val verifiedPin: String? = null
)

enum class ProfileSelectionMode {
    Selection,
    Management
}


@Composable
fun ProfileSelectionScreen(
    onProfileSelected: () -> Unit,
    screenMode: ProfileSelectionMode = ProfileSelectionMode.Selection,
    onBackPress: (() -> Unit)? = null,
    viewModel: ProfileSelectionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val avatarCatalog by viewModel.avatarCatalog.collectAsState()
    val profileBackgroundCatalog by viewModel.profileBackgroundCatalog.collectAsState()
    val profilePinEnabled by viewModel.profilePinEnabled.collectAsState()
    val dpadUnlockState by viewModel.dpadUnlockState.collectAsState()
    val isPinOperationInProgress by viewModel.isPinOperationInProgress.collectAsState()
    val pinLockedUntil by viewModel.pinLockedUntil.collectAsState()
    val liveTvEnabledById by viewModel.liveTvEnabledById.collectAsState()
    val storedActiveProfileId by viewModel.storedActiveProfileId.collectAsState()
    // Spec 64 K2: null until loaded; Edit, and Add / Delete outside primary-managed Manage, wait for it.
    val ratingCapById by viewModel.ratingCapById.collectAsState()
    val loadedLookEntries by viewModel.lookEntriesById.collectAsState()
    val lookEntriesById = loadedLookEntries.orEmpty()
    // My photos (Spec 44): null until read; Edit waits for it as for the look entries.
    val loadedPhotos by viewModel.myPhotos.collectAsState()
    val library = remember(loadedPhotos) { LocalLookLibrary(loadedPhotos.orEmpty()) }
    val avatarImageUrlsById = remember(avatarCatalog, profiles) {
        buildMap {
            avatarCatalog.forEach { avatar -> put(avatar.id, avatar.imageUrl) }
            profiles.mapNotNull(UserProfile::avatarId).forEach { avatarId ->
                viewModel.getAvatarImageUrl(avatarId)?.let { imageUrl -> putIfAbsent(avatarId, imageUrl) }
            }
        }
    }
    // Every profile's avatar through the one resolver (Spec 44): a TV-only look that applies wins.
    val avatarArtById: Map<Int, AvatarArt?> = remember(avatarImageUrlsById, profiles, lookEntriesById, library) {
        profiles.associate { profile ->
            profile.id to resolveLookAvatar(profile, lookEntriesById[profile.id], library.sets, avatarImageUrlsById::get)?.toArt(library)
        }
    }
    val profileBackgroundsById = remember(profileBackgroundCatalog) {
        profileBackgroundCatalog.associateBy { it.id }
    }
    var focusedAvatarColor by remember { mutableStateOf(Color(0xFF1E88E5)) }
    var focusedProfileId by remember { mutableStateOf<Int?>(null) }
    var optionsRequest by remember { mutableStateOf<ProfileOptionsRequest?>(null) }
    var profileToDelete by remember { mutableStateOf<UserProfile?>(null) }
    // The open editor (stages 7, 8): a new profile or an edit; null when closed.
    var editorDraft by remember { mutableStateOf<ProfileEditorDraft?>(null) }
    var pinOverlayState by remember { mutableStateOf<ProfilePinOverlayState?>(null) }
    var pinOverlayError by remember { mutableStateOf<String?>(null) }
    // One-way fallback: once the user picks the PIN pad on an unlock overlay, it
    // stays the PIN pad until that overlay is dismissed (reset on any state change).
    var dpadUnlockFallbackToPin by remember(pinOverlayState) { mutableStateOf(false) }
    val toast = rememberMarqueeToastState()
    // A current-PIN check for the [asking] overlay: [onUnlocked] after a match (error cleared), else
    // the overlay's error line; [wrongRes] is the state's wrong-PIN copy (invalid vs incorrect).
    // A reply that lands after Back closed (or replaced) that overlay is dropped.
    fun verifyPin(asking: ProfilePinOverlayState, pin: String, @StringRes wrongRes: Int, onUnlocked: () -> Unit) {
        viewModel.verifyProfilePin(asking.profile.id, pin) { outcome ->
            if (pinOverlayState !== asking) return@verifyProfilePin
            pinOverlayError = when (outcome) {
                PinVerifyOutcome.Unlocked -> null
                PinVerifyOutcome.Wrong -> context.getString(wrongRes)
                is PinVerifyOutcome.LockedOut -> context.getString(R.string.profile_pin_locked, outcome.seconds)
                PinVerifyOutcome.Failed -> context.getString(R.string.profile_pin_verify_error)
            }
            if (outcome == PinVerifyOutcome.Unlocked) onUnlocked()
        }
    }
    // Only profiles report focus: on Add the field keeps the last profile's (Spec 43f F1; a null
    // profile used to draw a flat grey gradient that banded visibly with no art over it).
    val onProfileFocusedChange = remember {
        { profile: UserProfile ->
            focusedProfileId = profile.id
            focusedAvatarColor = parseProfileColor(profile.avatarColorHex)
        }
    }
    val focusedProfile = profiles.firstOrNull { it.id == focusedProfileId }
    val isManagementMode = screenMode == ProfileSelectionMode.Management
    // The active id this screen opened under, pinned once loaded (Spec 64 D14): deleting the active
    // profile makes the store fall back to 1 a few frames before the app returns to Who's watching,
    // and the screen must not turn primary-managed in between.
    // Keyed on loaded-or-not: computed at null, again once at load, then never (it stays loaded).
    val openedActiveProfileId = remember(storedActiveProfileId == null) { storedActiveProfileId }
    // Manage profiles with the primary active: where the Live TV switch and the rating limit are
    // editable, and where Add / Delete ignore limits (Spec 64 D9, D10).
    val managedByPrimary = liveTvEditable(isManagementMode, openedActiveProfileId)
    // Q9: Manage gets a breadcrumb, a subtitle that no longer promises a switch, and "OK · Manage profile".
    val manageHeading = stringResource(R.string.profile_manage_heading)
    val header = if (isManagementMode) {
        ProfilePickerHeader(
            title = manageHeading,
            subtitle = stringResource(R.string.profile_manage_sub),
            crumbs = listOf(stringResource(R.string.nav_settings), stringResource(R.string.settings_profiles), manageHeading),
            hintKey = stringResource(R.string.profile_hint_key_ok),
            hintLabel = stringResource(R.string.profile_manage_hint_manage)
        )
    } else {
        ProfilePickerHeader(
            title = stringResource(R.string.profile_selection_title),
            subtitle = null,
            crumbs = null,
            hintKey = stringResource(R.string.profile_hint_key_hold_ok),
            hintLabel = stringResource(R.string.profile_selection_hint_manage)
        )
    }

    if (onBackPress != null) {
        BackHandler(onBack = onBackPress)
    }

    LaunchedEffect(profiles, activeProfileId) {
        val targetProfile = profiles.firstOrNull { it.id == focusedProfileId }
            ?: profiles.firstOrNull { it.id == activeProfileId }
            ?: profiles.firstOrNull()
        targetProfile?.let { profile ->
            focusedProfileId = profile.id
            focusedAvatarColor = parseProfileColor(profile.avatarColorHex)
        }
    }

    LaunchedEffect(editorDraft) {
        if (editorDraft?.isCreate == false) {
            viewModel.preloadProfileBackgrounds()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val overlayProfileColor = pinOverlayState?.profile?.avatarColorHex?.let(::parseProfileColor)
        val backgroundProfile = pinOverlayState?.profile ?: focusedProfile
        val backgroundSelection = resolveLookBackground(backgroundProfile, backgroundProfile?.let { lookEntriesById[it.id] }, library.sets)
        LaunchedEffect(backgroundSelection) {
            val selectedId = (backgroundSelection as? ProfileBackgroundSelection.Catalog)?.id
            if (selectedId != null) viewModel.loadProfileBackground(selectedId)
        }
        val profileBackground = backgroundSelection.toArtwork(profileBackgroundsById, library)
        ProfileSelectionBackground(
            focusedAvatarColor = overlayProfileColor ?: focusedAvatarColor,
            profileBackground = profileBackground,
            // Gated on the selection, not the loaded artwork: a chosen background still loading (or
            // failed) keeps the gradient, never the avatar art (P1).
            avatarArt = backgroundProfile?.takeIf { backgroundSelection == null }?.let { avatarArtById[it.id] }
        )

        AnimatedContent(
            targetState = pinOverlayState,
            transitionSpec = {
                val entering = fadeIn(tween(240)) + slideInHorizontally(
                    animationSpec = tween(320, easing = ProfileCardFocusEasing),
                    initialOffsetX = { fullWidth ->
                        if (targetState == null) -fullWidth / 10 else fullWidth / 12
                    }
                )
                val exiting = fadeOut(tween(NuvioMotion.tokens.durations.fast)) + slideOutHorizontally(
                    animationSpec = tween(240),
                    targetOffsetX = { fullWidth ->
                        if (targetState == null) fullWidth / 12 else -fullWidth / 12
                    }
                )
                entering togetherWith exiting
            },
            modifier = Modifier.fillMaxSize(),
            label = "profilePinTransition"
        ) { activePinOverlay ->
            if (activePinOverlay == null) {
                ProfileSelectionMainContent(
                    header = header,
                    showHint = !toast.onScreen,
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    canAddProfile = showAddProfile(viewModel.canAddProfile, managedByPrimary, profiles, ratingCapById),
                    profilePinEnabled = profilePinEnabled,
                    ratingCapById = ratingCapById.orEmpty(),
                    avatarArtById = avatarArtById,
                    onProfileFocused = onProfileFocusedChange,
                    onProfileSelected = { profile ->
                        if (isManagementMode) {
                            optionsRequest = ProfileOptionsRequest(profile, openedByHold = false)
                        } else {
                            if (profilePinEnabled[profile.id] == true) {
                                pinOverlayError = null
                                pinOverlayState = ProfilePinOverlayState.Unlock(profile)
                            } else {
                                viewModel.selectProfile(profile.id, onComplete = onProfileSelected)
                            }
                        }
                    },
                    onProfileLongPress = { profile ->
                        if (optionsNeedPin(isManagementMode, hasPin = profilePinEnabled[profile.id] == true)) {
                            pinOverlayError = null
                            pinOverlayState = ProfilePinOverlayState.UnlockForOptions(profile)
                        } else {
                            optionsRequest = ProfileOptionsRequest(profile, openedByHold = true)
                        }
                    },
                    onAddProfileClick = { editorDraft = ProfileEditorDraft.create() }
                )
            } else {
                val dpadUnlock = dpadUnlockState
                if (activePinOverlay is ProfilePinOverlayState.Unlock &&
                    dpadUnlock != null &&
                    dpadUnlock.enabled &&
                    dpadUnlock.isComboSet &&
                    !dpadUnlockFallbackToPin
                ) {
                    val unlockProfile = activePinOverlay.profile
                    val unlockCapture = remember(unlockProfile, dpadUnlock.comboLength) {
                        DpadUnlockCapture(dpadUnlock.comboLength)
                    }
                    DpadUnlockOverlay(
                        profile = unlockProfile,
                        avatarArt = avatarArtById[unlockProfile.id],
                        capture = unlockCapture,
                        onDismiss = {
                            pinOverlayState = null
                            pinOverlayError = null
                        },
                        onUsePinInstead = { dpadUnlockFallbackToPin = true },
                        onComboEntered = { sequence ->
                            viewModel.verifyDpadCombo(sequence) { matched ->
                                if (matched) unlockCapture.markSuccess() else unlockCapture.markFail()
                            }
                        },
                        onUnlocked = {
                            pinOverlayError = null
                            pinOverlayState = null
                            viewModel.selectProfile(
                                unlockProfile.id,
                                onComplete = onProfileSelected
                            )
                        }
                    )
                } else {
                    ProfilePinOverlay(
                        state = activePinOverlay,
                        avatarArt = avatarArtById[activePinOverlay.profile.id],
                        isWorking = isPinOperationInProgress,
                        errorMessage = pinOverlayError,
                        lockedUntil = pinLockedUntil[activePinOverlay.profile.id],
                        onClearError = { pinOverlayError = null },
                        onDismiss = {
                            pinOverlayState = null
                            pinOverlayError = null
                        },
                        onSubmit = { pin ->
                            when (activePinOverlay) {
                                is ProfilePinOverlayState.Set -> {
                                    viewModel.setProfilePin(
                                        activePinOverlay.profile.id,
                                        pin,
                                        activePinOverlay.currentPin
                                    ) { result ->
                                        // The PIN is saved even if Back closed the screen meanwhile; only the toast then.
                                        val current = pinOverlayState === activePinOverlay
                                        when (result) {
                                            is SetProfilePinResult.Success -> {
                                                if (current) pinOverlayState = null
                                                pinOverlayError = null
                                                toast.show(context.getString(R.string.profile_pin_saved_for_profile, activePinOverlay.profile.name))
                                            }
                                            is SetProfilePinResult.CurrentPinRequired -> if (current) {
                                                pinOverlayState = ProfilePinOverlayState.VerifyCurrentForChange(
                                                    activePinOverlay.profile
                                                )
                                                pinOverlayError = context.getString(R.string.profile_pin_current_required)
                                            }
                                            is SetProfilePinResult.Failure -> if (current) {
                                                pinOverlayError = context.getString(R.string.profile_pin_save_error)
                                            }
                                        }
                                    }
                                }

                                is ProfilePinOverlayState.Unlock -> {
                                    verifyPin(activePinOverlay, pin, R.string.profile_pin_invalid) {
                                        pinOverlayState = null
                                        viewModel.selectProfile(
                                            activePinOverlay.profile.id,
                                            onComplete = onProfileSelected
                                        )
                                    }
                                }

                                is ProfilePinOverlayState.UnlockForOptions -> {
                                    verifyPin(activePinOverlay, pin, R.string.profile_pin_invalid) {
                                        pinOverlayState = null
                                        // Opened by a keypad press, not a hold.
                                        optionsRequest = ProfileOptionsRequest(
                                            activePinOverlay.profile,
                                            openedByHold = false,
                                            verifiedPin = pin
                                        )
                                    }
                                }

                                is ProfilePinOverlayState.VerifyCurrentForChange -> {
                                    verifyPin(activePinOverlay, pin, R.string.profile_pin_incorrect) {
                                        pinOverlayState = ProfilePinOverlayState.Set(
                                            profile = activePinOverlay.profile,
                                            currentPin = pin
                                        )
                                    }
                                }

                                is ProfilePinOverlayState.VerifyCurrentForRemove -> {
                                    viewModel.clearProfilePin(
                                        profileId = activePinOverlay.profile.id,
                                        currentPin = pin
                                    ) { success ->
                                        val current = pinOverlayState === activePinOverlay
                                        if (success) {
                                            if (current) {
                                                pinOverlayError = null
                                                pinOverlayState = null
                                            }
                                            toast.show(context.getString(R.string.profile_pin_lock_removed_for_profile, activePinOverlay.profile.name))
                                        } else if (current) {
                                            pinOverlayError = context.getString(R.string.profile_pin_incorrect)
                                        }
                                    }
                                }

                                is ProfilePinOverlayState.VerifyCurrentForDelete -> {
                                    verifyPin(activePinOverlay, pin, R.string.profile_pin_incorrect) {
                                        pinOverlayState = null
                                        profileToDelete = activePinOverlay.profile
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        editorDraft?.let { draft ->
            val editorRatingAccess = ratingLimitAccess(managedByPrimary, isPrimary = draft.original?.isPrimary == true)
            ProfileEditor(
                draft = draft,
                avatarCatalog = avatarCatalog,
                profileBackgroundCatalog = profileBackgroundCatalog,
                catalogImageUrl = viewModel::getAvatarImageUrl,
                background = { color, preview ->
                    ProfileSelectionBackground(
                        focusedAvatarColor = color,
                        profileBackground = preview.toArtwork(profileBackgroundsById, library),
                        gradientVeil = NuvioTheme.marquee.profiles.editorVeil
                    )
                },
                liveTvEditable = managedByPrimary,
                ratingLimitAccess = editorRatingAccess,
                openProfiles = noPinUnlimitedProfiles(draft.original?.id, profiles, profilePinEnabled, ratingCapById)
                    .map(UserProfile::name),
                library = library,
                photoUserNames = { photo -> photoUsers(photo.id, profiles, lookEntriesById, library.sets).map { it.name } },
                onRemovePhoto = { photo ->
                    viewModel.removePhoto(photo.id) { removed ->
                        if (removed) {
                            draft.onPhotoRemoved(photo.id)
                        } else {
                            toast.show(context.getString(R.string.my_photo_remove_failed))
                        }
                    }
                },
                onAddFromPhone = {
                    val failure = when (viewModel.photoUploads.open()) {
                        PhotoUploadOpen.Opened -> null
                        PhotoUploadOpen.Full -> R.string.profile_my_photos_full
                        PhotoUploadOpen.NoNetwork -> R.string.error_network_required
                        PhotoUploadOpen.NoPort -> R.string.error_server_ports_unavailable
                    }
                    failure?.let { toast.show(context.getString(it)) }
                },
                onDismiss = { editorDraft = null },
                onSubmit = { onResult ->
                    // A result may arrive after Back closed the editor: success then changes nothing, failure still toasts.
                    val liveTvNotSaved = { toast.show(context.getString(R.string.profile_live_tv_save_failed)) }
                    val lookNotSaved = { toast.show(context.getString(R.string.profile_look_save_failed)) }
                    val ratingCapNotSaved = { toast.show(context.getString(R.string.rating_limit_save_failed)) }
                    val report: (Boolean) -> Unit = { saved ->
                        onResult(saved)
                        if (saved) {
                            if (editorDraft === draft) editorDraft = null
                        } else {
                            toast.show(
                                context.getString(
                                    if (draft.isCreate) R.string.profile_create_failed else R.string.profile_save_failed
                                )
                            )
                        }
                    }
                    if (draft.isCreate) {
                        viewModel.createProfile(
                            draft.name, draft.colorHex, draft.avatarId, draft.liveTvEnabled, liveTvNotSaved,
                            localAvatar = draft.localAvatar, onLookNotSaved = lookNotSaved,
                            ratingCap = draft.ratingCap, onRatingCapNotSaved = ratingCapNotSaved, onResult = report
                        )
                    } else {
                        viewModel.updateProfile(
                            draft.toProfile(), draft.liveTvEnabled.takeIf { draft.liveTvChanged }, liveTvNotSaved,
                            localAvatar = draft.localAvatar, localBackground = draft.localBackground,
                            onLookNotSaved = lookNotSaved,
                            ratingCap = ratingCapEditFor(editorRatingAccess, draft.ratingCap),
                            onRatingCapNotSaved = ratingCapNotSaved, onResult = report
                        )
                    }
                }
            )
            // Add from phone (stages 3, 5): lives and dies with the editor.
            PhotoUploadHost(
                controller = viewModel.photoUploads,
                onClosedNotice = { notice ->
                    // Added needs no toast: the photo shows first in My photos.
                    if (notice != PhotoUploadNotice.Added) toast.show(context.getString(notice.message))
                }
            )
        }

        // Toast (stage 12): over the key hint's spot.
        MarqueeToastHost(state = toast, modifier = Modifier.align(Alignment.BottomCenter))

        // Profile options (stage 6): the side panel, opened by a hold (guarded) or by OK in Manage.
        optionsRequest?.let { request ->
            val profile = request.profile
            val hasPin = profilePinEnabled[profile.id] == true
            ProfileOptionsPanel(
                profile = profile,
                avatarArt = avatarArtById[profile.id],
                sharesAddonsOf = sharesAddonsOf(profile, profiles),
                options = profileOptionRows(
                    canDelete = profileDeletable(profile, managedByPrimary, ratingCapById),
                    hasPin = hasPin
                ),
                openedByHold = request.openedByHold,
                onDismiss = { optionsRequest = null },
                onOption = { option ->
                    val target = profileOptionTarget(option, profile, hasPin, request.verifiedPin)
                    optionsRequest = null
                    when (target) {
                        // Not before the look entries, My photos and the rating cap load: a save would
                        // clear a look (or a photo pick) the draft never saw, and the editor would show
                        // the wrong limit.
                        ProfileOptionTarget.Edit -> loadedLookEntries
                            ?.takeIf { loadedPhotos != null && ratingCapLoaded(profile.id, ratingCapById) }
                            ?.let { looks ->
                                editorDraft = ProfileEditorDraft.edit(
                                    profile,
                                    liveTvEnabledById[profile.id] ?: true,
                                    ratingCapById?.get(profile.id),
                                    looks[profile.id],
                                    library.sets
                                )
                            }
                        ProfileOptionTarget.ConfirmDelete -> profileToDelete = profile
                        is ProfileOptionTarget.Pin -> {
                            pinOverlayError = null
                            pinOverlayState = target.state
                        }
                    }
                }
            )
        }

        // Delete confirmation (stage 10): Cancel focused, Delete in red.
        profileToDelete?.let { profile ->
            MarqueeConfirmPanel(
                eyebrow = stringResource(R.string.profile_delete_confirm_title),
                body = stringResource(R.string.profile_delete_confirm_subtitle),
                confirmLabel = stringResource(R.string.profile_options_delete),
                cancelLabel = stringResource(R.string.action_cancel),
                onConfirm = {
                    viewModel.deleteProfile(profile.id)
                    profileToDelete = null
                },
                onDismiss = { profileToDelete = null },
                destructive = true
            )
        }
    }
}

/**
 * The profile options panel (u10 stage 6): eyebrow, the profile at its head (avatar 44, name,
 * "Shares <primary>'s addons" when it uses the primary's addons), then [options] as rows. A
 * panel opened by a hold swallows that hold's release ([openedByHold]).
 */
@Composable
private fun ProfileOptionsPanel(
    profile: UserProfile,
    avatarArt: AvatarArt?,
    sharesAddonsOf: String?,
    options: List<ProfileOption>,
    openedByHold: Boolean,
    onDismiss: () -> Unit,
    onOption: (ProfileOption) -> Unit
) {
    val tokens = NuvioTheme.marquee.profiles
    val firstRow = remember(profile.id) { FocusRequester() }
    MarqueeSidePanel(
        onDismiss = onDismiss,
        initialFocus = firstRow,
        openedByHold = openedByHold,
        width = tokens.optionsPanelWidth
    ) {
        PanelEyebrow(text = stringResource(R.string.profile_selection_options_title))
        ProfilePanelHead(
            title = profile.name,
            subtitle = sharesAddonsOf?.let { stringResource(R.string.profile_shares_addons, it) }
        ) {
            ProfileAvatarCircle(
                name = profile.name,
                colorHex = profile.avatarColorHex,
                size = tokens.optionsAvatarSize,
                art = avatarArt
            )
        }
        PanelRule()
        options.forEachIndexed { index, option ->
            val (title, subtitle, icon) = when (option) {
                ProfileOption.EDIT -> Triple(
                    stringResource(R.string.profile_options_edit),
                    stringResource(R.string.profile_options_edit_sub),
                    Icons.Filled.Edit
                )
                ProfileOption.SET_PIN -> Triple(
                    stringResource(R.string.profile_pin_set),
                    stringResource(R.string.profile_options_set_pin_sub, profile.name),
                    Icons.Filled.Lock
                )
                ProfileOption.CHANGE_PIN -> Triple(
                    stringResource(R.string.profile_pin_change),
                    stringResource(R.string.profile_options_change_pin_sub),
                    Icons.Filled.LockReset
                )
                ProfileOption.REMOVE_PIN -> Triple(
                    stringResource(R.string.profile_pin_remove),
                    stringResource(R.string.profile_options_remove_pin_sub, profile.name),
                    Icons.Filled.LockOpen
                )
                ProfileOption.DELETE -> Triple(
                    stringResource(R.string.profile_options_delete),
                    stringResource(R.string.profile_options_delete_sub),
                    Icons.Filled.Delete
                )
            }
            PlayerPanelRow(
                title = title,
                subtitle = subtitle,
                selected = false,
                trailingIcon = icon,
                destructive = option == ProfileOption.DELETE,
                focusRequester = if (index == 0) firstRow else null,
                onClick = { onOption(option) }
            )
        }
    }
}

/**
 * The screen's field (picker, PIN and D-pad screens, and the editor with its previewed background,
 * never avatar art): a chosen [profileBackground] under the one background veil (Spec 44 D3); else
 * the [avatarArt] blurred over the colour gradient under a veil tinted by [focusedAvatarColor]
 * (Spec 43 P1); else the gradient alone. [gradientVeil] covers the gradient branch only (the
 * editor's veil), so it crossfades with the artwork it gives way to.
 */
@Composable
private fun ProfileSelectionBackground(
    focusedAvatarColor: Color,
    profileBackground: ProfileBackgroundArtwork?,
    avatarArt: AvatarArt? = null,
    gradientVeil: Color = Color.Transparent
) {
    val animatedAvatarColor by animateColorAsState(
        targetValue = focusedAvatarColor,
        animationSpec = tween(durationMillis = 520),
        label = "focusedAvatarColor"
    )
    val gradientTop = lerp(NuvioTheme.colors.BackgroundElevated, animatedAvatarColor, 0.3f)
    val gradientMid = lerp(NuvioTheme.colors.Background, animatedAvatarColor, 0.14f)
    val halfFadeStrong = animatedAvatarColor.copy(alpha = 0.26f)
    val halfFadeSoft = animatedAvatarColor.copy(alpha = 0.08f)

    AnimatedContent(
        targetState = ProfileFieldTarget(profileBackground, avatarArt),
        transitionSpec = {
            fadeIn(tween(320)) togetherWith fadeOut(tween(240))
        },
        contentKey = { target ->
            when (val background = target.artwork) {
                is ProfileBackgroundArtwork.Catalog -> "catalog:${background.background.id}"
                is ProfileBackgroundArtwork.Custom -> "custom:${background.url}"
                is ProfileBackgroundArtwork.Bundled -> "bundled:${background.background.slug}"
                is ProfileBackgroundArtwork.Photo -> "photo:${background.photo.id}"
                null -> target.avatarArt?.let { "avatar:${it.model}" }
            }
        },
        modifier = Modifier.fillMaxSize(),
        label = "profileBackground"
    ) { target ->
        when (val background = target.artwork) {
            is ProfileBackgroundArtwork.Catalog -> VeiledBackground {
                ProfileBackgroundImage(
                    background = background.background,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is ProfileBackgroundArtwork.Custom -> VeiledBackground {
                CustomProfileBackgroundImage(
                    imageUrl = background.url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is ProfileBackgroundArtwork.Bundled -> VeiledBackground {
                BundledBackgroundImage(
                    background = background.background,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is ProfileBackgroundArtwork.Photo -> VeiledBackground {
                PhotoBackgroundImage(
                    photo = background.photo,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to gradientTop,
                                    0.42f to gradientMid,
                                    1f to NuvioTheme.colors.Background
                                )
                            )
                        )
                        .background(
                            brush = Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0f to halfFadeStrong,
                                    0.45f to halfFadeSoft,
                                    0.72f to Color.Transparent,
                                    1f to Color.Transparent
                                )
                            )
                        )
                ) {
                    target.avatarArt?.let { art -> ProfileAvatarArt(art.model, animatedAvatarColor) }
                    Box(modifier = Modifier.fillMaxSize().background(gradientVeil))
                }
            }
        }
    }
}

/** A chosen background [image] under the one dark veil every background shares (Spec 44 D3). */
@Composable
private fun VeiledBackground(image: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        image()
        Box(modifier = Modifier.fillMaxSize().background(NuvioTheme.marquee.profiles.backgroundVeil))
    }
}

/** The avatar art pre-blurred at the token alpha (the gradient below shows while it loads), then the tinted veil. */
@Composable
private fun ProfileAvatarArt(model: Any, avatarColor: Color) {
    val tokens = NuvioTheme.marquee.profiles
    val context = LocalContext.current
    val request = remember(context, model) {
        ImageRequest.Builder(context)
            .data(model)
            .size(ART_DECODE_WIDTH_PX, ART_DECODE_HEIGHT_PX)
            .transformations(BlurTransformation(ART_BLUR_RADIUS))
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        alpha = tokens.artImageAlpha,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Brushes rebuilt only when the size or the (tweening) avatar colour changes.
            .drawWithCache {
                val veil = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to avatarColor.copy(alpha = tokens.artTintAlpha),
                        0.55f to NuvioPrimitives.black.copy(alpha = tokens.artVeilMid),
                        1f to NuvioPrimitives.black.copy(alpha = tokens.artVeilEdge)
                    ),
                    center = size.center,
                    radius = size.maxDimension * 0.6f
                )
                val scrims = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to NuvioPrimitives.black.copy(alpha = tokens.artTopScrim),
                        0.3f to Color.Transparent,
                        0.7f to Color.Transparent,
                        1f to NuvioPrimitives.black.copy(alpha = tokens.artBottomScrim)
                    )
                )
                onDrawBehind {
                    drawRect(veil)
                    drawRect(scrims)
                }
            }
    )
}

/** The picker's copy for one mode: [crumbs] replaces the wordmark (Manage), [subtitle] only in Manage. */
private class ProfilePickerHeader(
    val title: String,
    val subtitle: String?,
    val crumbs: List<String>?,
    val hintKey: String,
    val hintLabel: String
)

/** The wordmark at the top-bar spot (picker, PIN and D-pad screens; the picker gates the app, so no top bar). */
@Composable
internal fun ProfileWordmark() {
    BrandWordmark(
        contentDescription = stringResource(R.string.cd_nuvio_logo),
        modifier = Modifier
            .padding(start = NuvioTheme.layout.tvSafeHorizontal, top = NuvioTheme.marquee.home.breadcrumbTop)
            .height(NuvioTheme.marquee.profiles.wordmarkHeight)
    )
}

/** Picker (u10 stages 1, 2, 11): wordmark or breadcrumb, title, Manage subtitle, the row, key hint ([showHint] false under a toast). */
@Composable
private fun ProfileSelectionMainContent(
    header: ProfilePickerHeader,
    showHint: Boolean,
    profiles: List<UserProfile>,
    activeProfileId: Int,
    canAddProfile: Boolean,
    profilePinEnabled: Map<Int, Boolean>,
    ratingCapById: Map<Int, ContentRatingCap?>,
    avatarArtById: Map<Int, AvatarArt?>,
    onProfileFocused: (UserProfile) -> Unit,
    onProfileSelected: (UserProfile) -> Unit,
    onProfileLongPress: (UserProfile) -> Unit,
    onAddProfileClick: () -> Unit
) {
    val tokens = NuvioTheme.marquee.profiles
    Box(modifier = Modifier.fillMaxSize()) {
        if (header.crumbs != null) {
            MarqueeBreadcrumb(header.crumbs)
        } else {
            ProfileWordmark()
            // The startup picker gates the app (no top bar), so it carries the bar's clock (stage picker).
            TopBarClock(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = NuvioTheme.layout.tvSafeHorizontal, top = NuvioTheme.marquee.home.breadcrumbTop)
            )
        }
        Text(
            text = header.title,
            style = NuvioTheme.textStyles.stateTitle,
            color = NuvioPrimitives.white,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(
                top = if (header.subtitle != null) tokens.titleTopWithSubtitle else tokens.titleTop
            )
        )
        if (header.subtitle != null) {
            Text(
                text = header.subtitle,
                style = NuvioTheme.textStyles.profileSubtitle,
                color = NuvioTheme.marquee.onImage2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = tokens.subtitleTop)
            )
        }
        ProfileGrid(
            profiles = profiles,
            activeProfileId = activeProfileId,
            canAddProfile = canAddProfile,
            profilePinEnabled = profilePinEnabled,
            ratingCapById = ratingCapById,
            avatarArtById = avatarArtById,
            onProfileFocused = onProfileFocused,
            onProfileSelected = onProfileSelected,
            onProfileLongPress = onProfileLongPress,
            onAddProfileClick = onAddProfileClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (header.subtitle != null) tokens.rowTopWithSubtitle else tokens.rowTop)
        )
        if (showHint) {
            DetailKeyHint(
                key = header.hintKey,
                label = header.hintLabel,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = tokens.hintBottom)
            )
        }
    }
}

/** One centred row: full-size circles up to five items, the compact size at six (profiles + Add; [profileRowIsCompact]). */
@Composable
private fun ProfileGrid(
    profiles: List<UserProfile>,
    activeProfileId: Int,
    canAddProfile: Boolean,
    profilePinEnabled: Map<Int, Boolean>,
    ratingCapById: Map<Int, ContentRatingCap?>,
    avatarArtById: Map<Int, AvatarArt?>,
    onProfileFocused: (UserProfile) -> Unit,
    onProfileSelected: (UserProfile) -> Unit,
    onProfileLongPress: (UserProfile) -> Unit,
    onAddProfileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalItems = profiles.size + if (canAddProfile) 1 else 0
    val initialFocusIndex = remember(profiles, activeProfileId, canAddProfile) {
        profiles.indexOfFirst { it.id == activeProfileId }
            .takeIf { it >= 0 }
            ?: if (profiles.isNotEmpty()) 0 else if (canAddProfile) 0 else -1
    }
    val focusRequesters = remember(totalItems) {
        List(totalItems) { FocusRequester() }
    }

    LaunchedEffect(totalItems, initialFocusIndex) {
        if (focusRequesters.isNotEmpty() && initialFocusIndex in focusRequesters.indices) {
            focusRequesters[initialFocusIndex].requestFocusAfterFrames()
        }
    }

    if (profiles.isEmpty() && !canAddProfile) {
        Text(
            text = stringResource(R.string.profile_selection_empty),
            style = NuvioTheme.textStyles.profileSubtitle,
            color = NuvioTheme.marquee.onImage2,
            textAlign = TextAlign.Center,
            modifier = modifier
        )
    } else {
        val tokens = NuvioTheme.marquee.profiles
        val compact = profileRowIsCompact(totalItems)
        val avatarSize = if (compact) tokens.avatarSizeCompact else tokens.avatarSize
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(
                if (compact) tokens.cardGapCompact else tokens.cardGap,
                Alignment.CenterHorizontally
            ),
            verticalAlignment = Alignment.Top
        ) {
            profiles.forEachIndexed { index, profile ->
                ProfileCard(
                    profile = profile,
                    avatarSize = avatarSize,
                    avatarArt = avatarArtById[profile.id],
                    hasPin = profilePinEnabled[profile.id] == true,
                    ratingCap = ratingCapById[profile.id],
                    sharesAddonsOf = sharesAddonsOf(profile, profiles),
                    focusRequester = focusRequesters[index],
                    onFocused = { onProfileFocused(profile) },
                    onClick = { onProfileSelected(profile) },
                    onLongPress = { onProfileLongPress(profile) }
                )
            }
            if (canAddProfile) {
                AddProfileCard(
                    avatarSize = avatarSize,
                    focusRequester = focusRequesters[profiles.size],
                    onClick = onAddProfileClick
                )
            }
        }
    }
}

/**
 * A profile card (Spec 43 `.cD`): the avatar on a circular Marquee card (scale, ring, a glow in the
 * avatar's colour; hold OK or MENU = [onLongPress]), the lock badge on PIN profiles, then the name in
 * a fixed line box. Only while focused: the name grows to 18 and PRIMARY (profile 1) or the
 * profile's rating limit ([ratingCap], Spec 64 K2 stage 4: "OLDER KIDS", grey) and the addon line
 * ("Shares <primary>'s addons" or "Own addons") appear under it.
 */
@Composable
private fun ProfileCard(
    profile: UserProfile,
    avatarSize: Dp,
    avatarArt: AvatarArt?,
    hasPin: Boolean,
    ratingCap: ContentRatingCap?,
    sharesAddonsOf: String?,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.profiles
    var isFocused by remember { mutableStateOf(false) }
    val avatarColor = remember(profile.avatarColorHex) { parseProfileColor(profile.avatarColorHex) }
    Column(
        modifier = Modifier.width(avatarSize),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            MarqueeCard(
                onClick = onClick,
                onLongPress = onLongPress,
                width = avatarSize,
                height = avatarSize,
                shape = CircleShape,
                ringGap = tokens.ringGap,
                focusedScale = tokens.cardFocusScale,
                glowColor = if (isFocused) avatarColor.copy(alpha = marquee.glowCardAlpha) else Color.Transparent,
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        isFocused = it.isFocused
                        if (it.isFocused) onFocused()
                    }
            ) {
                ProfileAvatarCircle(
                    name = profile.name,
                    colorHex = profile.avatarColorHex,
                    size = avatarSize,
                    art = avatarArt
                )
            }
            if (hasPin) {
                ProfileLockBadge(
                    focused = isFocused,
                    avatarSize = avatarSize,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }
        Spacer(modifier = Modifier.height(tokens.avatarToName))
        ProfileRowName(text = profile.name, focused = isFocused)
        if (isFocused) {
            Spacer(modifier = Modifier.height(tokens.nameToDetails))
            if (profile.isPrimary) {
                Text(
                    text = stringResource(R.string.profile_selection_primary_badge),
                    style = NuvioTheme.textStyles.profileTag,
                    color = tokens.primaryTag
                )
                Spacer(modifier = Modifier.height(tokens.cardLineGap))
            } else if (ratingCap != null) {
                Text(
                    text = stringResource(ratingCap.labelRes()).uppercase(),
                    style = NuvioTheme.textStyles.profileTag,
                    color = marquee.onImage2
                )
                Spacer(modifier = Modifier.height(tokens.cardLineGap))
            }
            Text(
                text = if (sharesAddonsOf != null) {
                    stringResource(R.string.profile_shares_addons, sharesAddonsOf)
                } else {
                    stringResource(R.string.profile_own_addons)
                },
                style = NuvioTheme.textStyles.caption,
                color = marquee.onImage2,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A row name in its fixed `nameLineHeight` box: 16 at onImage3, focused 18 white. */
@Composable
private fun ProfileRowName(text: String, focused: Boolean) {
    val styles = NuvioTheme.textStyles
    Box(
        modifier = Modifier.height(NuvioTheme.marquee.profiles.nameLineHeight),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = if (focused) styles.profileRowNameFocused else styles.profileRowName,
            color = if (focused) NuvioPrimitives.white else NuvioTheme.marquee.onImage3,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The lock badge in the avatar's bottom-end corner, drawn above the card (whose content is clipped
 * to the circle) and scaled about the [avatarSize] avatar's centre while [focused]. The tv Card runs
 * its own scale animation, so the focus tween here approximates its timing (both end at
 * `cardFocusScale`).
 */
@Composable
private fun ProfileLockBadge(focused: Boolean, avatarSize: Dp, modifier: Modifier = Modifier) {
    val tokens = NuvioTheme.marquee.profiles
    val scale by animateFloatAsState(
        targetValue = if (focused) tokens.cardFocusScale else 1f,
        animationSpec = NuvioMotion.focusTween(),
        label = "profileLockBadgeScale"
    )
    // The avatar's centre in badge-size units from the badge's top-left (it sits in the corner).
    val pivot = (avatarSize / 2 - (avatarSize - tokens.lockBadgeSize)) / tokens.lockBadgeSize
    val edgeWidth = tokens.lockBadgeEdgeWidth
    Box(
        modifier = modifier
            .zIndex(2f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(pivot, pivot)
            }
            .size(tokens.lockBadgeSize)
            // The edge sits outside the disc (box-shadow spread in the mockup).
            .drawBehind { drawCircle(color = tokens.lockBadgeEdge, radius = size.minDimension / 2 + edgeWidth.toPx()) }
            .background(NuvioPrimitives.black, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = NuvioPrimitives.white,
            modifier = Modifier.size(tokens.lockIconSize)
        )
    }
}

/** Add profile (`.l10add`): a glass circle with a +, the same card shell and ring as a profile. */
@Composable
private fun AddProfileCard(
    avatarSize: Dp,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.profiles
    var isFocused by remember { mutableStateOf(false) }
    val edge = remember(tokens.addEdge, tokens.addEdgeWidth) {
        Border(border = BorderStroke(tokens.addEdgeWidth, tokens.addEdge), shape = CircleShape)
    }
    val contentColor = if (isFocused) NuvioPrimitives.white else marquee.onImage2
    Column(
        modifier = Modifier.width(avatarSize),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MarqueeCard(
            onClick = onClick,
            width = avatarSize,
            height = avatarSize,
            shape = CircleShape,
            ringGap = tokens.ringGap,
            focusedScale = tokens.cardFocusScale,
            containerColor = tokens.addFill,
            edge = edge,
            modifier = Modifier
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused }
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.align(Alignment.Center).size(tokens.addIconSize)
            )
        }
        Spacer(modifier = Modifier.height(tokens.avatarToName))
        ProfileRowName(text = stringResource(R.string.profile_add_card), focused = isFocused)
    }
}

private fun keyCodeToDigit(keyCode: Int): Char? {
    return when (keyCode) {
        AndroidKeyEvent.KEYCODE_0,
        AndroidKeyEvent.KEYCODE_NUMPAD_0 -> '0'
        AndroidKeyEvent.KEYCODE_1,
        AndroidKeyEvent.KEYCODE_NUMPAD_1 -> '1'
        AndroidKeyEvent.KEYCODE_2,
        AndroidKeyEvent.KEYCODE_NUMPAD_2 -> '2'
        AndroidKeyEvent.KEYCODE_3,
        AndroidKeyEvent.KEYCODE_NUMPAD_3 -> '3'
        AndroidKeyEvent.KEYCODE_4,
        AndroidKeyEvent.KEYCODE_NUMPAD_4 -> '4'
        AndroidKeyEvent.KEYCODE_5,
        AndroidKeyEvent.KEYCODE_NUMPAD_5 -> '5'
        AndroidKeyEvent.KEYCODE_6,
        AndroidKeyEvent.KEYCODE_NUMPAD_6 -> '6'
        AndroidKeyEvent.KEYCODE_7,
        AndroidKeyEvent.KEYCODE_NUMPAD_7 -> '7'
        AndroidKeyEvent.KEYCODE_8,
        AndroidKeyEvent.KEYCODE_NUMPAD_8 -> '8'
        AndroidKeyEvent.KEYCODE_9,
        AndroidKeyEvent.KEYCODE_NUMPAD_9 -> '9'
        else -> null
    }
}

/**
 * The PIN screen (u10 stages 3, 4, 9): avatar, heading, four boxes, the support line, the
 * on-screen [PinKeypad] (focus on 5) and the footer. Remote number keys, DEL / CLEAR and a
 * keyboard type too; one Back leaves. While the profile is locked out ([lockedUntil] in
 * elapsedRealtime ms) the seconds count down, the boxes stay red and the keypad dims; at 0 the
 * error clears and the support line returns. The server's lockout reply also sets [errorMessage]
 * (the shake and the cleared boxes); reopening a locked profile only counts down.
 */
@Composable
private fun ProfilePinOverlay(
    state: ProfilePinOverlayState,
    avatarArt: AvatarArt?,
    isWorking: Boolean,
    errorMessage: String?,
    lockedUntil: Long?,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val tokens = NuvioTheme.marquee.profiles
    val keypadFocus = remember(state) { FocusRequester() }
    val isSingleEntryMode = state !is ProfilePinOverlayState.Set
    val isUnlock = state is ProfilePinOverlayState.Unlock || state is ProfilePinOverlayState.UnlockForOptions
    val pinEntry = remember(state) { ProfilePinFlow(confirmNew = !isSingleEntryMode) }
    val scope = rememberCoroutineScope()
    var internalErrorMessage by remember(state) { mutableStateOf<String?>(null) }
    // Seeded in composition so a locked profile never shows a frame unlocked.
    var lockedSeconds by remember(state, lockedUntil) {
        mutableIntStateOf(PinLockouts.secondsLeft(lockedUntil, SystemClock.elapsedRealtime()))
    }
    val shakeOffset = remember(state) { androidx.compose.animation.core.Animatable(0f) }
    val cursorAlpha by rememberInfiniteTransition(label = "pinCursor").animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(520),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pinCursorAlpha"
    )
    val isLockedOut = lockedSeconds > 0
    val resolvedErrorMessage = when {
        isLockedOut -> stringResource(R.string.profile_pin_locked, lockedSeconds)
        isSingleEntryMode -> errorMessage
        else -> internalErrorMessage ?: errorMessage
    }
    val isErrorState = !resolvedErrorMessage.isNullOrEmpty()
    val mismatchMessage = stringResource(R.string.profile_pin_overlay_mismatch)

    BackHandler(onBack = onDismiss)

    suspend fun playErrorAnimation() {
        shakeOffset.snapTo(0f)
        listOf(-22f, 18f, -14f, 10f, -6f, 0f).forEach { offset ->
            shakeOffset.animateTo(offset, animationSpec = tween(42))
        }
    }

    LaunchedEffect(state) {
        keypadFocus.requestFocusAfterFrames()
    }

    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrEmpty()) {
            pinEntry.clear()
            playErrorAnimation()
        }
    }

    LaunchedEffect(state, lockedUntil) {
        if (lockedSeconds == 0) return@LaunchedEffect
        while (lockedSeconds > 0) {
            delay(250)
            lockedSeconds = PinLockouts.secondsLeft(lockedUntil, SystemClock.elapsedRealtime())
        }
        // The lockout's own error line (set when the server locked) goes with it.
        onClearError()
    }

    // Input is taken only while no request runs and no lockout holds; errors clear before the
    // edit so a mismatch it raises stays shown.
    fun edit(change: () -> PinEntryOutcome) {
        if (isWorking || isLockedOut) return
        if (!errorMessage.isNullOrEmpty()) onClearError()
        if (!isSingleEntryMode) internalErrorMessage = null
        when (val outcome = change()) {
            PinEntryOutcome.Typing, PinEntryOutcome.AdvancedToConfirm -> Unit
            PinEntryOutcome.Mismatch -> {
                internalErrorMessage = mismatchMessage
                scope.launch { playErrorAnimation() }
            }
            is PinEntryOutcome.Submit -> onSubmit(outcome.pin)
        }
    }

    // An empty entry has nothing to delete, so it leaves an error line standing (keypad and DEL alike).
    fun backspace() {
        if (pinEntry.pin.isNotEmpty()) edit(pinEntry::backspace)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Opened by a hold on the card: its repeats and release must not press "5".
            .holdReleaseGuard(enabled = state is ProfilePinOverlayState.UnlockForOptions)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != AndroidKeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_BACK,
                    AndroidKeyEvent.KEYCODE_ESCAPE -> {
                        onDismiss()
                        true
                    }

                    AndroidKeyEvent.KEYCODE_DEL,
                    AndroidKeyEvent.KEYCODE_CLEAR -> {
                        backspace()
                        true
                    }

                    else -> {
                        val digit = keyCodeToDigit(native.keyCode)
                        if (digit != null) {
                            edit { pinEntry.type(digit) }
                            true
                        } else {
                            false
                        }
                    }
                }
            }
    ) {
        val headingText = when {
            isUnlock -> stringResource(R.string.profile_pin_overlay_unlock_heading, state.profile.name)
            state is ProfilePinOverlayState.VerifyCurrentForChange -> stringResource(R.string.profile_pin_overlay_change_verify_heading, state.profile.name)
            state is ProfilePinOverlayState.VerifyCurrentForRemove -> stringResource(R.string.profile_pin_overlay_remove_verify_heading, state.profile.name)
            state is ProfilePinOverlayState.VerifyCurrentForDelete -> stringResource(R.string.profile_pin_overlay_delete_verify_heading, state.profile.name)
            pinEntry.stage == ProfilePinEntryStage.Confirm -> stringResource(R.string.profile_pin_overlay_confirm_heading)
            else -> stringResource(R.string.profile_pin_overlay_set_heading, state.profile.name)
        }
        val supportText = when {
            !resolvedErrorMessage.isNullOrEmpty() -> resolvedErrorMessage
            isWorking && isSingleEntryMode -> stringResource(R.string.profile_pin_verifying)
            isWorking -> stringResource(R.string.action_saving)
            isUnlock -> stringResource(R.string.profile_pin_keypad_support)
            state is ProfilePinOverlayState.VerifyCurrentForChange -> stringResource(R.string.profile_pin_overlay_change_verify_support)
            state is ProfilePinOverlayState.VerifyCurrentForRemove -> stringResource(R.string.profile_pin_overlay_remove_verify_support)
            state is ProfilePinOverlayState.VerifyCurrentForDelete -> stringResource(R.string.profile_pin_overlay_delete_verify_support)
            pinEntry.stage == ProfilePinEntryStage.Confirm -> stringResource(R.string.profile_pin_overlay_confirm_support)
            else -> stringResource(R.string.profile_pin_overlay_set_support)
        }
        val backHint = stringResource(R.string.profile_pin_back_to_cancel)
        // No forgot line while setting a new PIN (stage 9).
        val footer = if (isSingleEntryMode) {
            stringResource(R.string.profile_pin_forgot) + "  ·  " + backHint
        } else {
            backHint
        }

        ProfileWordmark()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = tokens.pinTop, start = ProfileSelectionSpacing.ScreenPaddingHorizontal, end = ProfileSelectionSpacing.ScreenPaddingHorizontal),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ProfileAvatarCircle(
                name = state.profile.name,
                colorHex = state.profile.avatarColorHex,
                size = tokens.pinAvatarSize,
                art = avatarArt
            )
            Spacer(modifier = Modifier.height(tokens.pinAvatarToTitle))
            Text(
                text = headingText,
                style = NuvioTheme.textStyles.pinTitle,
                color = NuvioPrimitives.white,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(tokens.pinTitleToBoxes))
            ProfilePinBoxes(
                value = pinEntry.pin,
                showCursor = !isWorking && !isErrorState,
                isErrorState = isErrorState,
                cursorAlpha = { cursorAlpha },
                modifier = Modifier.graphicsLayer {
                    translationX = shakeOffset.value
                }
            )
            Spacer(modifier = Modifier.height(tokens.pinBoxesToSupport))
            Text(
                text = supportText,
                style = NuvioTheme.textStyles.pinSupport,
                color = if (isErrorState) tokens.pinErrorText else NuvioTheme.marquee.onImage2,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(tokens.pinSupportToKeypad))
            PinKeypad(
                enabled = !isLockedOut,
                initialFocus = keypadFocus,
                onDigit = { digit -> edit { pinEntry.type(digit) } },
                onBackspace = ::backspace
            )
            Spacer(modifier = Modifier.height(tokens.pinKeypadToFooter))
            Text(
                text = footer,
                style = NuvioTheme.textStyles.caption,
                color = NuvioTheme.marquee.onImage3,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** The four PIN boxes (`.l10pin`): dots for typed digits, a blinking cursor in the next box. */
@Composable
private fun ProfilePinBoxes(
    value: String,
    showCursor: Boolean,
    isErrorState: Boolean,
    cursorAlpha: () -> Float,
    modifier: Modifier = Modifier
) {
    val tokens = NuvioTheme.marquee.profiles
    val shape = RoundedCornerShape(tokens.boxRadius)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(tokens.boxGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(ProfilePinLength) { index ->
            val isFilled = index < value.length
            val isActive = showCursor && index == value.length
            val border = when {
                isErrorState -> BorderStroke(tokens.boxActiveOutlineWidth, tokens.boxErrorOutline)
                isActive -> BorderStroke(tokens.boxActiveOutlineWidth, NuvioPrimitives.white)
                isFilled -> BorderStroke(tokens.boxOutlineWidth, tokens.boxFilledOutline)
                else -> BorderStroke(tokens.boxOutlineWidth, tokens.boxOutline)
            }
            val fill = when {
                isErrorState -> tokens.boxErrorFill
                isActive -> tokens.boxActiveFill
                isFilled -> tokens.boxFilledFill
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .size(tokens.boxWidth, tokens.boxHeight)
                    .clip(shape)
                    .background(fill)
                    .border(border, shape),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isErrorState -> Unit
                    isFilled -> Box(
                        modifier = Modifier
                            .size(tokens.boxDot)
                            .clip(CircleShape)
                            .background(NuvioPrimitives.white)
                    )
                    isActive -> Box(
                        modifier = Modifier
                            .size(tokens.boxCursorWidth, tokens.boxCursorHeight)
                            .graphicsLayer { alpha = cursorAlpha() }
                            .background(NuvioPrimitives.white)
                    )
                }
            }
        }
    }
}
