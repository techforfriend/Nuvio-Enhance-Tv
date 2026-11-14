package com.nuvio.tv.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.components.AutoResizeText
import com.nuvio.tv.ui.components.PanelEyebrowText
import com.nuvio.tv.ui.components.marquee.MarqueeGlassTextButton
import com.nuvio.tv.ui.screens.profile.ProfileWordmark
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.formatMinutesSecondsCountdown
import com.nuvio.tv.ui.util.requestFocusAfterFrames
import kotlinx.coroutines.delay

/**
 * QR sign-in (sandbox u11-settings-marquee v6, stages `qr` / `qr-expired`; also the first-launch
 * screen when [onContinue] is set). Settings' field, the wordmark top-left, the Server pill
 * top-right; the tagline on the left; one opaque card on the right with the QR, its code, the
 * countdown, the status line, Refresh QR / Back and the Terms line. Onboarding has no Back: its
 * Continue (without account) sits under the intro instead, where every translation fits (sandbox
 * spec-49-sign-in-buttons v2; beside Refresh QR it clipped in the card). A dead code dims (expired:
 * "Code expired" over it) and nothing regenerates it but Refresh. On a custom server with email
 * auth the card holds the email form instead. From Settings, a sign-in returns straight to
 * Settings › Account; onboarding continues as before.
 */
@Composable
fun AuthQrSignInScreen(
    onBackPress: () -> Unit = {},
    onContinue: (() -> Unit)? = null,
    viewModel: AccountViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isSignedIn = uiState.authState is AuthState.FullAccount
    val isOnboardingMode = onContinue != null
    val useEmailLogin = viewModel.usesEmailPasswordLogin
    var exitRequested by remember { mutableStateOf(false) }
    // Exactly one owner at a time: Refresh QR, the email field, Back (Settings, signed in) or
    // onboarding's Continue (signed in).
    val loginFocusRequester = remember { FocusRequester() }

    fun leaveAuthScreen() {
        exitRequested = true
        viewModel.clearQrLoginSession()
        onBackPress()
    }

    fun continueFromAuthScreen() {
        exitRequested = true
        if (onContinue != null && !isSignedIn) {
            viewModel.signOut()
        }
        viewModel.clearQrLoginSession()
        if (onContinue != null) {
            onContinue()
        } else {
            onBackPress()
        }
    }

    BackHandler {
        leaveAuthScreen()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearQrLoginSession()
        }
    }

    // Auto-start only from Idle: a failed, expired or ended session waits for Refresh.
    val qrIdle = uiState.qrLogin is QrLogin.Idle
    LaunchedEffect(uiState.authState, isSignedIn, qrIdle, exitRequested) {
        if (
            !useEmailLogin &&
            !exitRequested &&
            uiState.authState !is AuthState.Loading &&
            !isSignedIn &&
            qrIdle
        ) {
            viewModel.startQrLogin()
        }
    }

    LaunchedEffect(isOnboardingMode, isSignedIn) {
        if (!isSignedIn || exitRequested) return@LaunchedEffect
        if (!isOnboardingMode) {
            // Q1: the Account pane shows the signed-in account.
            leaveAuthScreen()
        } else {
            exitRequested = true
            viewModel.clearQrLoginSession()
            onContinue.invoke()
        }
    }

    val nowMillis by produceState(initialValue = System.currentTimeMillis(), key1 = uiState.qrLogin.displayCode) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val tokens = NuvioTheme.marquee.settings
    val safeHorizontal = NuvioTheme.layout.tvSafeHorizontal
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        ProfileWordmark()
        SignInIntro(
            useEmailLogin = useEmailLogin,
            modifier = Modifier
                .padding(start = safeHorizontal, top = tokens.signInIntroTop)
                .width(tokens.signInIntroWidth)
        ) {
            if (isOnboardingMode) {
                MarqueeGlassTextButton(
                    label = stringResource(
                        if (isSignedIn) R.string.auth_qr_continue else R.string.auth_qr_continue_without_account
                    ),
                    onClick = { continueFromAuthScreen() },
                    modifier = Modifier
                        .padding(top = tokens.signInIntroActionTop)
                        .then(if (isSignedIn) Modifier.focusRequester(loginFocusRequester) else Modifier)
                )
            }
        }
        SignInCard(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = safeHorizontal, top = tokens.signInCardTop, bottom = tokens.signInCardBottom)
                .width(tokens.signInCardWidth)
                .fillMaxHeight()
        ) {
            Text(
                text = stringResource(
                    if (useEmailLogin) R.string.auth_email_instruction else R.string.auth_qr_scan_steps
                ),
                style = NuvioTheme.textStyles.meta,
                color = NuvioTheme.marquee.onImage2,
                textAlign = TextAlign.Center
            )
            when {
                // Onboarding only: a Settings sign-in has already left the screen.
                isSignedIn -> SignInStatusLine(text = stringResource(R.string.auth_qr_finishing), error = false)
                useEmailLogin -> SignInEmailForm(
                    uiState = uiState,
                    onSignIn = viewModel::signIn,
                    initialFocusRequester = loginFocusRequester
                )
                else -> SignInQrBlock(uiState = uiState, nowMillis = nowMillis)
            }
            val showRefresh = !isSignedIn && !useEmailLogin
            SignInActions(
                showRefresh = showRefresh,
                refreshBusy = uiState.qrLogin.isBusy,
                refreshFocus = loginFocusRequester.takeIf { showRefresh },
                showBack = !isOnboardingMode,
                backFocus = loginFocusRequester.takeIf { isSignedIn },
                onRefresh = viewModel::startQrLogin,
                onBack = { leaveAuthScreen() }
            )
            // The Terms line at the card's foot (`.l11terms{margin-top:auto}`): one box taking the
            // free height, so the stack has no extra gap a Spacer item would add.
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                Text(
                    text = stringResource(R.string.auth_qr_terms_text),
                    style = NuvioTheme.textStyles.profileLine,
                    color = NuvioTheme.marquee.onImage3,
                    textAlign = TextAlign.Center
                )
            }
        }
        ServerPill(
            viewModel = hiltViewModel(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = tokens.signInPillTop, end = safeHorizontal)
        )
    }

    LaunchedEffect(Unit) {
        loginFocusRequester.requestFocusAfterFrames(frames = 3)
    }
}

/** `.l11ql`: the tagline and how sign-in works, then [action] (onboarding's Continue). */
@Composable
private fun SignInIntro(useEmailLogin: Boolean, modifier: Modifier, action: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NuvioTheme.marquee.settings.signInIntroGap)) {
        Text(
            text = stringResource(R.string.auth_qr_tagline),
            style = NuvioTheme.textStyles.signInTitle,
            color = NuvioPrimitives.white
        )
        Text(
            text = stringResource(if (useEmailLogin) R.string.auth_email_hint else R.string.auth_qr_phone_note),
            style = NuvioTheme.textStyles.signInIntro,
            color = NuvioTheme.marquee.onImage2
        )
        action()
    }
}

/** `.l11qc`: the opaque card (Spec 80 D9), ACCOUNT LOGIN at its top. */
@Composable
private fun SignInCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tokens = NuvioTheme.marquee.settings
    val shape = RoundedCornerShape(tokens.signInCardRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(tokens.signInCardFill, shape)
            .border(NuvioTheme.spacing.hairline, tokens.signInCardEdge, shape)
            .padding(
                start = tokens.signInCardPaddingHorizontal,
                end = tokens.signInCardPaddingHorizontal,
                top = tokens.signInCardPaddingTop,
                bottom = tokens.signInCardPaddingBottom
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(tokens.signInCardGap)
    ) {
        PanelEyebrowText(text = stringResource(R.string.auth_qr_account_login))
        content()
    }
}

/** The QR tile (dimmed once dead, "Code expired" over an expired one), the code, the countdown and the status line. */
@Composable
private fun SignInQrBlock(uiState: AccountUiState, nowMillis: Long) {
    val tokens = NuvioTheme.marquee.settings
    val qrLogin = uiState.qrLogin
    val expired = qrCodeExpired(qrLogin, nowMillis)
    val dimmed = qrCodeDimmed(qrLogin, nowMillis)
    val tileShape = RoundedCornerShape(tokens.signInQrRadius)
    Box(
        modifier = Modifier
            .size(tokens.qrSize)
            .clip(tileShape),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = uiState.qrLoginBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_qr_login),
                modifier = Modifier
                    .fillMaxSize()
                    .background(NuvioPrimitives.white)
                    .alpha(if (dimmed && !expired) DEAD_QR_ALPHA else 1f),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NuvioTheme.marquee.formRow.fill),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(
                        if (qrLogin is QrLogin.Starting) R.string.auth_qr_generating else R.string.auth_qr_unavailable
                    ),
                    style = NuvioTheme.textStyles.meta,
                    color = NuvioTheme.marquee.onImage2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(NuvioTheme.spacing.md)
                )
            }
        }
        if (expired && bitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(tokens.signInExpiredVeil),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.auth_qr_code_expired),
                    style = NuvioTheme.textStyles.profileName,
                    color = NuvioPrimitives.white
                )
            }
        }
    }
    qrLogin.displayCode?.takeIf { it.isNotBlank() }?.let { code ->
        // One line, as the mockup's `.l11code`: the server's code is 32 hex characters (the mockup
        // drew "K7Q4-MD2X"), two lines at full size, which pushed the Terms line out of the card
        // (Shield 2026-11-02, 12/N-5). It shrinks to fit instead.
        AutoResizeText(
            text = stringResource(R.string.auth_qr_code_label, code),
            style = NuvioTheme.textStyles.signInCode,
            color = NuvioPrimitives.white
        )
    }
    val expiresAtMs = (qrLogin as? QrLogin.Showing)?.expiresAtMs
    val countdown = when {
        expired -> stringResource(R.string.auth_qr_expired_short)
        expiresAtMs != null -> stringResource(R.string.auth_qr_expires, formatMinutesSecondsCountdown(expiresAtMs - nowMillis))
        else -> null
    }
    countdown?.let {
        Text(text = it, style = NuvioTheme.textStyles.settingsNote, color = NuvioTheme.marquee.onImage3)
    }
    val failure = (qrLogin as? QrLogin.Failed)?.message
    val status = failure ?: qrStatusRes(qrLogin, nowMillis)?.let { stringResource(it) }
    if (!status.isNullOrBlank()) {
        SignInStatusLine(text = status, error = dimmed)
    }
}

/** A dead (used, cancelled, failed) code under no overlay. */
private const val DEAD_QR_ALPHA = 0.3f

/** `.l11stt`: a dot (amber, red on [error]) and the status. */
@Composable
private fun SignInStatusLine(text: String, error: Boolean) {
    val color = if (error) NuvioPrimitives.errorOnImage else NuvioTheme.marquee.onImage2
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(NuvioTheme.marquee.settings.signInStatusDot)
                .background(if (error) NuvioPrimitives.errorOnImage else NuvioPrimitives.warning, CircleShape)
        )
        // Two lines at most: a failure message is server or exception text and must not push the
        // Terms line out of the card.
        Text(
            text = text,
            style = NuvioTheme.textStyles.meta,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** `.l11qb`: Refresh QR (QR mode) and, from Settings, Back. */
@Composable
private fun SignInActions(
    showRefresh: Boolean,
    refreshBusy: Boolean,
    refreshFocus: FocusRequester?,
    showBack: Boolean,
    backFocus: FocusRequester?,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    // No row when both are off (onboarding's email form or finishing): an empty Row would still
    // take its top padding and a card gap.
    if (!showRefresh && !showBack) return
    Row(
        modifier = Modifier.padding(top = NuvioTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        if (showRefresh) {
            MarqueeGlassTextButton(
                label = stringResource(if (refreshBusy) R.string.auth_qr_please_wait else R.string.auth_qr_refresh),
                // A press while a request is in flight is ignored by the ViewModel.
                onClick = onRefresh,
                modifier = if (refreshFocus != null) Modifier.focusRequester(refreshFocus) else Modifier
            )
        }
        if (showBack) {
            MarqueeGlassTextButton(
                label = stringResource(R.string.auth_qr_back),
                onClick = onBack,
                modifier = if (backFocus != null) Modifier.focusRequester(backFocus) else Modifier
            )
        }
    }
}

/** Custom server with email auth: email, password, Sign In, the error line. */
@Composable
private fun SignInEmailForm(
    uiState: AccountUiState,
    onSignIn: (String, String) -> Unit,
    initialFocusRequester: FocusRequester
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (email.isNotBlank() && password.isNotBlank() && !uiState.isLoading) {
            onSignIn(email.trim(), password)
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        InputField(
            value = email,
            onValueChange = { email = it },
            placeholder = stringResource(R.string.auth_email_placeholder),
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            modifier = Modifier.focusRequester(initialFocusRequester)
        )
        InputField(
            value = password,
            onValueChange = { password = it },
            placeholder = stringResource(R.string.auth_password_placeholder),
            keyboardType = KeyboardType.Password,
            isPassword = true,
            imeAction = ImeAction.Done,
            onImeAction = submit
        )
        MarqueeGlassTextButton(
            label = stringResource(if (uiState.isLoading) R.string.auth_email_signing_in else R.string.auth_email_sign_in),
            onClick = submit
        )
        uiState.error?.takeIf { it.isNotBlank() }?.let { SignInStatusLine(text = it, error = true) }
    }
}
