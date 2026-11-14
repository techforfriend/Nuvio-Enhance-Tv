package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Typography
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AppFont

val DMSansFamily = FontFamily(
    Font(R.font.dm_sans_variable, FontWeight.Normal),
    Font(R.font.dm_sans_variable, FontWeight.Medium),
    Font(R.font.dm_sans_variable, FontWeight.SemiBold),
    Font(R.font.dm_sans_variable, FontWeight.Bold),
    Font(R.font.dm_sans_variable, FontWeight.ExtraBold)
)

val InterFamily = FontFamily(
    Font(R.font.inter_variable, FontWeight.Normal),
    Font(R.font.inter_variable, FontWeight.Medium),
    Font(R.font.inter_variable, FontWeight.SemiBold),
    Font(R.font.inter_variable, FontWeight.Bold),
    Font(R.font.inter_variable, FontWeight.ExtraBold)
)

val OpenSansFamily = FontFamily(
    Font(R.font.opensans_variable, FontWeight.Normal),
    Font(R.font.opensans_variable, FontWeight.Medium),
    Font(R.font.opensans_variable, FontWeight.SemiBold),
    Font(R.font.opensans_variable, FontWeight.Bold),
    Font(R.font.opensans_variable, FontWeight.ExtraBold)
)

fun getFontFamily(appFont: AppFont): FontFamily = when (appFont) {
    AppFont.INTER -> InterFamily
    AppFont.DM_SANS -> DMSansFamily
    AppFont.OPEN_SANS -> OpenSansFamily
}

@Immutable
data class NuvioTextStyleTokens(
    val display: TextStyle,
    val sectionTitle: TextStyle,
    val body: TextStyle,
    val bodyCompact: TextStyle,
    val metadata: TextStyle,
    val badge: TextStyle,
    val button: TextStyle,
    val tab: TextStyle,
    /** Marquee top-bar brand: 15sp ExtraBold, +3.5 tracking (Marquee spec §Tokens text styles). */
    val wordmark: TextStyle,
    /** Marquee home (same table): hero title 48/52, eyebrow 11/14 caps, meta 13/18, row title
     *  15/20, card label 13/18 (Marquee `cardTitle`), chip 10/14, home description 14/20. */
    val heroTitle: TextStyle,
    val eyebrow: TextStyle,
    val meta: TextStyle,
    val rowTitle: TextStyle,
    val cardLabel: TextStyle,
    val chip: TextStyle,
    val bodyHome: TextStyle,
    /** Home empty / error block title, 36/44 Bold (Marquee Home states). */
    val stateTitle: TextStyle,
    /** Search's compact hero title (Spec 38 Q4 B, `.l38hc h1`), 26/32 Bold -0.4. */
    val heroTitleCompact: TextStyle,
    /** Marquee detail (same table): title 56/60, description 14/21, tech badge 10/16 Bold, caption
     *  12/16 Medium (button suffix, source line), block title 32/38 (About sheet, U2c block),
     *  long body 16/26 (About sheet description). */
    val displayHero: TextStyle,
    val bodyDetail: TextStyle,
    val techBadge: TextStyle,
    val caption: TextStyle,
    val blockTitle: TextStyle,
    val bodyLong: TextStyle,
    /** Marquee player OSD (same table): title + clock 18/24 SemiBold, elapsed/remaining 14/20
     *  SemiBold, both tabular figures. The episode line uses [meta], "Ends at" [caption] and the
     *  small glass pill [cardLabel] (same sizes as the spec's osdSub / smallPill). */
    val osdTitle: TextStyle,
    val osdTime: TextStyle,
    /** Marquee Stream screen: the episode name under the show title, 16/22 SemiBold; a source
     *  row's languages line, 11/16 SemiBold +0.6, its "Last played" mark, 11/16 Bold +1.2
     *  (uppercased by the caller), the addon's own text with "Use addon formatting", 12/17, and
     *  the sources panel's empty / error title, 24/30 Bold -0.3, and dialog body text, 13/19.
     *  Player panels (U6): a stepper row's helper line, 11/15; toggle and segment pills, 12/16
     *  SemiBold; the delay strip's value, 20/26 SemiBold tabular; small glass buttons, 13/18
     *  SemiBold. Pause overlay: the episode name, 18/24 SemiBold; a cast chip's initials, 11/11
     *  Bold; the cast detail's name, 36/42 Bold -0.6, and role, 16/22 Medium. Loading hand-off:
     *  the percentage, 13/18 SemiBold tabular. */
    val episodeName: TextStyle,
    val sideLabel: TextStyle,
    val lastPlayed: TextStyle,
    val addonText: TextStyle,
    val panelStateTitle: TextStyle,
    val panelBody: TextStyle,
    val panelHelper: TextStyle,
    val panelPill: TextStyle,
    val panelValueLarge: TextStyle,
    val panelButtonSmall: TextStyle,
    val pauseEpisodeName: TextStyle,
    val castInitials: TextStyle,
    val castName: TextStyle,
    val castRole: TextStyle,
    val handOffPercent: TextStyle,
    /** Marquee PIN screen (u10 stages 3, 4, 9; the D-pad screen, stage 5, uses the title and the support
     *  line at 14): title 28/34 Bold -0.4, support line 13/18 Medium, keypad digits 18/22 SemiBold. The
     *  footer uses [caption]. */
    val pinTitle: TextStyle,
    val pinSupport: TextStyle,
    val pinKey: TextStyle,
    /** The profile options panel's head name (u10 stage 6), 18/24 Bold. */
    val panelHeadName: TextStyle,
    /** Profiles: Manage subtitle 14/20 Medium; [profileName] 15/20 SemiBold (editor, account rows);
     *  PRIMARY tag 10/14 SemiBold +1.4; [profileLine] 11/15 Medium (counters, small data lines). */
    val profileSubtitle: TextStyle,
    val profileName: TextStyle,
    val profileTag: TextStyle,
    val profileLine: TextStyle,
    /** Picker row names (Spec 43, sandbox u43 v4): 16/22 SemiBold, focused 18/22 (same line box). */
    val profileRowName: TextStyle,
    val profileRowNameFocused: TextStyle,
    /** Profile editor (u10 stages 7, 8): preview name 26/32 Bold -0.4; Name field text 15/20 Medium;
     *  the focused avatar's category 12/16 Medium (its name uses [profileName], the counter [profileLine]). */
    val profileEditorName: TextStyle,
    val profileField: TextStyle,
    val profileAvatarCategory: TextStyle,
    /** A typed TMDB API key (Spec 52 `.s52k`): 13.5/20 Medium tabular, +0.4 tracked, so all 32
     *  characters fit the side panel's field. */
    val keyField: TextStyle,
    /** Settings (u11): pane title 28/34 Bold -0.5; note 12/17 Regular; stepper value 13/18
     *  SemiBold tabular. Subtitles and values use [meta], section labels [eyebrow]. */
    val settingsTitle: TextStyle,
    val settingsNote: TextStyle,
    val settingsStepValue: TextStyle,
    /** `.l11pass` result line label; its value uses [settingsStepValue]. */
    val settingsResultLabel: TextStyle,
    /** QR sign-in route (u11 stages 4, 5): tagline 40/46 Bold -0.8; intro 15/22 Regular; the code
     *  15/20 SemiBold tracked 1.6. */
    val signInTitle: TextStyle,
    val signInIntro: TextStyle,
    val signInCode: TextStyle,
    /** First-launch welcome card (u33 stage a-summary): device name 22/28 Bold -0.3; row label 13/18
     *  SemiBold; row value and the device line 12/17 Medium (the note uses [settingsNote]). */
    val welcomeDevice: TextStyle,
    val welcomeRowLabel: TextStyle,
    val welcomeRowValue: TextStyle,
    /** Addon certificate panel (Spec 68 X7, sandbox `s068-x7-tls-confirm` v1): host 17/22 SemiBold;
     *  fingerprint caption 10/14 Medium +1.4 (uppercased by the caller); fingerprint 12/17 Medium
     *  monospace +0.3 (the mockup's DM Mono falls back to the platform monospace). */
    val certHost: TextStyle,
    val certCaption: TextStyle,
    val certFingerprint: TextStyle
)

@OptIn(ExperimentalTvMaterial3Api::class)
fun buildNuvioTypography(fontFamily: FontFamily): Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )
)

@OptIn(ExperimentalTvMaterial3Api::class)
val NuvioTypography = buildNuvioTypography(InterFamily)

@OptIn(ExperimentalTvMaterial3Api::class)
fun buildNuvioTextStyles(typography: Typography): NuvioTextStyleTokens = NuvioTextStyleTokens(
    display = typography.displayLarge,
    sectionTitle = typography.headlineMedium,
    body = typography.bodyLarge,
    bodyCompact = typography.bodyMedium,
    metadata = typography.labelMedium,
    badge = typography.labelSmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp
    ),
    button = typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    tab = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    wordmark = typography.titleSmall.copy(
        fontSize = 15.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 3.5.sp
    ),
    heroTitle = typography.displayLarge.copy(
        fontSize = 48.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1).sp
    ),
    eyebrow = typography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.8.sp
    ),
    meta = typography.labelLarge.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    ),
    rowTitle = typography.titleSmall.copy(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    cardLabel = typography.labelLarge.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    chip = typography.labelSmall.copy(
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp
    ),
    bodyHome = typography.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    stateTitle = typography.displayMedium.copy(
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp
    ),
    heroTitleCompact = typography.displayMedium.copy(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp
    ),
    displayHero = typography.displayLarge.copy(
        fontSize = 56.sp,
        lineHeight = 60.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.2).sp
    ),
    bodyDetail = typography.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    techBadge = typography.labelSmall.copy(
        fontSize = 10.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp
    ),
    caption = typography.labelMedium.copy(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    ),
    blockTitle = typography.displayMedium.copy(
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp
    ),
    bodyLong = typography.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    osdTitle = typography.titleMedium.copy(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    osdTime = typography.labelLarge.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    episodeName = typography.titleMedium.copy(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    sideLabel = typography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp
    ),
    lastPlayed = typography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp
    ),
    addonText = typography.labelMedium.copy(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    ),
    panelStateTitle = typography.headlineMedium.copy(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp
    ),
    panelBody = typography.bodyMedium.copy(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp
    ),
    panelHelper = typography.bodySmall.copy(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp
    ),
    panelPill = typography.labelMedium.copy(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    panelValueLarge = typography.titleLarge.copy(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    panelButtonSmall = typography.labelLarge.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    pauseEpisodeName = typography.titleMedium.copy(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    castInitials = typography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp
    ),
    castName = typography.displayMedium.copy(
        fontSize = 36.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp
    ),
    castRole = typography.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    ),
    handOffPercent = typography.labelLarge.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    pinTitle = typography.headlineLarge.copy(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp
    ),
    pinSupport = typography.bodyMedium.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    ),
    pinKey = typography.titleMedium.copy(
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    panelHeadName = typography.titleMedium.copy(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp
    ),
    profileSubtitle = typography.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    ),
    profileName = typography.titleSmall.copy(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    profileTag = typography.labelSmall.copy(
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp
    ),
    profileLine = typography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    ),
    profileRowName = typography.titleSmall.copy(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    profileRowNameFocused = typography.titleSmall.copy(
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    profileEditorName = typography.headlineMedium.copy(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp
    ),
    profileField = typography.bodyLarge.copy(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    ),
    profileAvatarCategory = typography.bodySmall.copy(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    ),
    keyField = typography.bodyLarge.copy(
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    settingsTitle = typography.headlineLarge.copy(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp
    ),
    settingsNote = typography.bodySmall.copy(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp
    ),
    settingsStepValue = typography.labelLarge.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    settingsResultLabel = typography.bodyMedium.copy(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    signInTitle = typography.displayLarge.copy(
        fontSize = 40.sp,
        lineHeight = 46.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.8).sp
    ),
    signInIntro = typography.bodyLarge.copy(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp
    ),
    signInCode = typography.titleSmall.copy(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp
    ),
    welcomeDevice = typography.titleLarge.copy(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp
    ),
    welcomeRowLabel = typography.labelLarge.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    welcomeRowValue = typography.bodySmall.copy(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    ),
    certHost = typography.titleSmall.copy(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    certCaption = typography.labelSmall.copy(
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp
    ),
    certFingerprint = typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp
    )
)

private const val TABULAR_FIGURES = "tnum"

@OptIn(ExperimentalTvMaterial3Api::class)
val NuvioTextStyles = buildNuvioTextStyles(NuvioTypography)
