package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Marquee design-system tokens (docs/design/marquee-design-system-2026-10-06.md §Tokens).
 * Fixed values, independent of the palette theme (§Palette independence): Marquee surfaces
 * sit on artwork, so they never read `FocusRing`/`FocusBackground`. Each token lands with its
 * first consumer (wave U rule); U1b adds the top bar's, U1c the home's. Top-level values are
 * shared by every Marquee screen; [home] holds Home-only geometry.
 */
@Immutable
data class NuvioMarqueeTokens(
    /** `marquee.ring`: focus ring colour on every Marquee surface. */
    val ring: Color,
    val ringWidth: Dp,
    /** `text.onImage2` / `text.onImage3`: secondary / tertiary text over artwork. */
    val onImage2: Color,
    val onImage3: Color,
    /** `text.onImageBody`: descriptions over artwork. */
    val onImageBody: Color,
    /** `media.scrimTop`: [scrimTopStart] fading to transparent over [scrimTopHeight]. */
    val scrimTopStart: Color,
    val scrimTopHeight: Dp,
    /** `media.scrimLeft` / `media.scrimBottom`: (fraction, black alpha) stops. */
    val scrimLeftStops: List<Pair<Float, Float>>,
    val scrimBottomStops: List<Pair<Float, Float>>,
    /** `media.scrimLeftDetail` / `media.scrimBottomDetail`: the detail first frame's scrims. */
    val scrimLeftDetailStops: List<Pair<Float, Float>>,
    val scrimBottomDetailStops: List<Pair<Float, Float>>,
    /** `marquee.glassFill`: glass buttons, pills and key hints (fixed, not the palette's). */
    val glassFill: Color,
    /** `status.readyText`: the source line's "Ready" label (its dot stays `success`). */
    val readyText: Color,
    /** `focus.cardScale` (centre pivot). */
    val cardScale: Float,
    /** `focus.glowCard`: shadow elevation standing in for y14 blur34, ambient accent at [glowCardAlpha]. */
    val glowCardElevation: Dp,
    val glowCardAlpha: Float,
    /** Landscape/portrait card corner radius (spec §Components "Landscape card"). */
    val cardRadius: Dp,
    /** Progress bars: card bottom inset bar and the hero/info progress bar. */
    val progressHeight: Dp,
    /** Meta row: gap between parts and 3 dp dot separators. */
    val metaGap: Dp,
    /** Skeleton bars and placeholder cards (Home states "loading", Detail loading). */
    val skeletonFill: Color,
    /** Small loading spinner (Detail source line, Stream chips): [spinnerSize] ring, [spinnerStroke] wide. */
    val spinnerSize: Dp,
    val spinnerStroke: Dp,
    val topBar: NuvioTopBarTokens,
    val home: NuvioMarqueeHomeTokens,
    val detail: NuvioMarqueeDetailTokens,
    val person: NuvioMarqueePersonTokens,
    val entity: NuvioMarqueeEntityTokens,
    val message: NuvioMarqueeMessageTokens,
    val primaryButton: NuvioMarqueePrimaryButtonTokens,
    val glassButton: NuvioMarqueeGlassButtonTokens,
    val techBadge: NuvioMarqueeTechBadgeTokens,
    val osd: NuvioMarqueeOsdTokens,
    val search: NuvioMarqueeSearchTokens,
    val library: NuvioMarqueeLibraryTokens,
    val stream: NuvioMarqueeStreamTokens,
    val playerPanel: NuvioMarqueePlayerPanelTokens,
    val collections: NuvioMarqueeCollectionsTokens,
    val profiles: NuvioMarqueeProfilesTokens,
    val formRow: NuvioMarqueeFormRowTokens,
    val settings: NuvioMarqueeSettingsTokens,
    val toast: NuvioMarqueeToastTokens,
    val welcome: NuvioMarqueeWelcomeTokens,
    val liveTv: NuvioMarqueeLiveTvTokens,
    val tip: NuvioMarqueeTipTokens
)

/**
 * Live TV layout A (approved sandbox `u34-live-tv-marquee` v3, generator `r2.py`; Spec 34 D16), dp
 * on the 960x540 canvas. [accent]: the screen's fixed ambient (live pictures have no artwork):
 * eyebrows, the info progress, the playing row's number, the focused row's glow.
 * Pills (`.lvpills`): [pillsTop] under the bar's `contentTop`, [pillHeight] tall, padded
 * [pillPaddingHorizontal], [pillGap] apart, the count [pillCountGap] after the label; "All
 * countries" outlined [pillOutline]; Sources has a [pillIconSize] icon [pillIconGap] before its label.
 * List (`.lvlist`/`.lvr`): from [listStart] x [listTop] (screen top), [listWidth] wide; rows
 * [rowHeight] tall, [rowGap] apart, [rowRadius], padded [rowPaddingStart]/[rowPaddingEnd], parts
 * [rowInnerGap] apart: a [numberWidth] number, a [logoSize] tile ([logoRadius]), the text (a
 * [rowProgressWidth] bar after the programme). Focus keeps at most [focusRowsAbove] rows above it.
 * A row whose preview shows while focus is elsewhere is [selectedFill]; a muted row's text and tile
 * draw at [mutedAlpha]; a source-lost row's number, tile and text at [lostAlpha] ([lostFocusedAlpha]
 * focused, L8). A favorite's star after the name: [favoriteSize] in [favorite]. The SOURCE LOST tag
 * (`.lvtag.warn`): [warn] text on [warnTagFill]; focused, [warnTagFocusedText] on
 * [warnTagFocusedFill]. [fadeHeight]: the bottom fade. Preview (`.lvpv`): [previewWidth] x
 * [previewHeight] at [previewStart] x [previewTop], [previewRadius], [previewFill]; badges
 * [previewInset] in, a [previewLogoSize] tile; tuning veil [tuningVeil] with a [tuningSpinnerSize]
 * spinner; unavailable = [errorFill] + [errorEdge], a [errorIconSize] icon in [warn], [buttonHeight]
 * buttons padded [buttonPaddingHorizontal]. Info (`.lvnn`): [infoTop], [infoGap], a
 * [infoProgressWidth] bar. LIVE badge (`.lvlive`): [liveRed], [badgeHeight], padded
 * [badgePaddingHorizontal], radius [badgeRadius], a [liveDotSize] dot. Empty state (`.lvempty`):
 * [emptyTop], [emptyWidth], parts [emptyGap] apart.
 * Full screen (L7c, stages `lv-full`, `lv-zap`, `lv-list-overlay`): content [fullInset] from the
 * sides; the OSD's channel at [fullTop], the clock at [clockTop], the programme block [fullBottom]
 * above the bottom with a [fullProgressWidth] bar; scrims [osdScrimTop]/[osdScrimBottom] tall
 * (zap banner: [zapScrimTop]/[zapScrimBottom]). Banner (`.lvban`): [bannerWidth], [bannerRadius],
 * padded [bannerPaddingHorizontal] x [bannerPaddingVertical], parts [bannerGap] apart, a
 * [bannerLogoSize] tile, a [bannerProgressWidth] bar. Banner and list overlay: [chromeFill] with a
 * [chromeEdge] hairline. List overlay (`.lvpanel`): [overlayWidth] at [overlayStart] x
 * [overlayTop] to [overlayBottom] above the bottom, [overlayRadius]; rows from [overlayListTop],
 * [overlayListInset] in; over a left [overlayShade] fading out by mid-screen. Chips (`.lvch`):
 * [chipHeight], padded [chipPaddingHorizontal], [chipGap] apart, [chipFill]; selected = glass + a
 * [chipRing] white ring. Hold-OK side panel (L7d, stages `lv-options`, `lv-guide-pick`):
 * [optionsPanelWidth], its Choose guide page [guidePanelWidth], at least [panelInsetVertical] from the
 * top and bottom. Sources route (L7e, stages `lv-sources`, `lv-add`): rows' type column
 * [sourcesTypeWidth], status chevron [sourcesIconSize]; buttons padded [sourcesButtonPaddingHorizontal],
 * [sourcesButtonGap] apart; the Add source dialog [addPanelWidth] wide over [addVeil]. Suggested
 * guide row (Spec 38 stage 6, `.lvsr.l38sug`): [suggestFill] with a [suggestEdge] hairline, its
 * type in `readyText`. Choose guides panel (Spec 55 S1 stage `s55-pick`): [guidesPickWidth] wide,
 * [guidesPickFill], over [guidesPickVeil]. Channel pack scan (Spec 73 stages 3-8, `.s73pk`, `.s73cp`): the
 * country list [scanPickWidth] wide, the confirmation, progress and result cards [scanCardWidth] (the
 * whole-world confirmation over [addVeil], the rest over [guidesPickVeil], all in [guidesPickFill]); the
 * progress bar [scanBarHeight] on [scanBarTrack]. Picker pills (Spec 38 stage 1, `.l38p`): `pillPaddingHorizontal` before the
 * label, [pickerPaddingEnd] after the chevron, parts [pickerInnerGap] apart, the value at most
 * [pickerValueMaxWidth] (ellipsised). Day schedule column (Spec 67 G2, sandbox `spec-67-day-schedule` v2,
 * `.s67col`): the picture shrinks to a [scheduleHeadWidth] x [scheduleHeadHeight] head (radius
 * [scheduleHeadRadius]) with the channel [scheduleHeadGap] to its right; parts [scheduleGap] apart.
 * Rows (`.s67r`): at least [scheduleRowMinHeight], radius [scheduleRowRadius], padded
 * [scheduleRowPaddingHorizontal], time column [scheduleTimeWidth] then [scheduleRowInnerGap]; the airing
 * row on [scheduleNowFill] with a [scheduleProgressWidth] bar; rows [scheduleListGap] apart.
 * Guide grid (Spec 67 G3, sandbox `spec-67-guide-grid` v2, D7): the band (`.s67band`) at [guideBandTop],
 * [guideBandHeight] tall: the picture [guideBandPictureWidth] wide (radius [guideBandRadius]), the focused
 * programme [guideBandGap] to its right with a [guideBandProgressWidth] bar. The grid (`.s67g`) from
 * [guideTop]: a [guideRulerHeight] ruler (day chips [guideChipHeight] tall, padded [guideChipPaddingHorizontal]),
 * rows [guideRowHeight] (Compact [guideCompactRowHeight]) tall, [guideGap] apart and [guideGap] from the
 * ruler; channel column [guideChannelWidth] (number [guideNumberWidth], tile [guideLogoSize] / Compact
 * [guideCompactLogoSize]) then the strip. Cells radius [guideCellRadius] ([guideCutRadius] at a cut
 * edge), padded [guideCellPaddingHorizontal], [guideCellFill], airing [guideAiringFill]; the no-guide cell
 * a [guideNoneEdge] hairline; the guide-end cell [guideEndStripe] stripes. Ruler marks a [guideMarkEdge]
 * hairline ([guideMidnightEdge] at midnight); the now-line [guideNowLineWidth] in `accent`.
 */
@Immutable
data class NuvioMarqueeLiveTvTokens(
    val accent: Color,
    val pillsTop: Dp,
    val pillHeight: Dp,
    val pillPaddingHorizontal: Dp,
    val pillGap: Dp,
    val pillCountGap: Dp,
    val pillOutline: Color,
    val pillIconSize: Dp,
    val pillIconGap: Dp,
    val listStart: Dp,
    val listTop: Dp,
    val listWidth: Dp,
    val rowHeight: Dp,
    val rowGap: Dp,
    val rowRadius: Dp,
    val rowPaddingStart: Dp,
    val rowPaddingEnd: Dp,
    val rowInnerGap: Dp,
    val numberWidth: Dp,
    val logoSize: Dp,
    val logoRadius: Dp,
    val rowProgressWidth: Dp,
    val focusRowsAbove: Int,
    val selectedFill: Color,
    val mutedAlpha: Float,
    val lostAlpha: Float,
    val lostFocusedAlpha: Float,
    val favorite: Color,
    val favoriteSize: Dp,
    val warnTagFill: Color,
    val warnTagFocusedText: Color,
    val warnTagFocusedFill: Color,
    val fadeHeight: Dp,
    val previewStart: Dp,
    val previewTop: Dp,
    val previewWidth: Dp,
    val previewHeight: Dp,
    val previewRadius: Dp,
    val previewFill: Color,
    val previewInset: Dp,
    val previewLogoSize: Dp,
    val tuningVeil: Color,
    val tuningSpinnerSize: Dp,
    val errorFill: Color,
    val errorEdge: Color,
    val errorIconSize: Dp,
    val warn: Color,
    val buttonHeight: Dp,
    val buttonPaddingHorizontal: Dp,
    val infoTop: Dp,
    val infoGap: Dp,
    val infoProgressWidth: Dp,
    val liveRed: Color,
    val badgeHeight: Dp,
    val badgePaddingHorizontal: Dp,
    val badgeRadius: Dp,
    val liveDotSize: Dp,
    val emptyTop: Dp,
    val emptyWidth: Dp,
    val emptyGap: Dp,
    val fullInset: Dp,
    val fullTop: Dp,
    val clockTop: Dp,
    val fullBottom: Dp,
    val fullProgressWidth: Dp,
    val osdScrimTop: Dp,
    val osdScrimBottom: Dp,
    val zapScrimTop: Dp,
    val zapScrimBottom: Dp,
    val bannerWidth: Dp,
    val bannerRadius: Dp,
    val bannerPaddingHorizontal: Dp,
    val bannerPaddingVertical: Dp,
    val bannerGap: Dp,
    val bannerLogoSize: Dp,
    val bannerProgressWidth: Dp,
    val chromeFill: Color,
    val chromeEdge: Color,
    val overlayStart: Dp,
    val overlayTop: Dp,
    val overlayBottom: Dp,
    val overlayWidth: Dp,
    val overlayRadius: Dp,
    val overlayListTop: Dp,
    val overlayListInset: Dp,
    val overlayShade: Color,
    val chipHeight: Dp,
    val chipPaddingHorizontal: Dp,
    val chipGap: Dp,
    val chipFill: Color,
    val chipRing: Dp,
    val optionsPanelWidth: Dp,
    val guidePanelWidth: Dp,
    val panelInsetVertical: Dp,
    val sourcesTypeWidth: Dp,
    val sourcesIconSize: Dp,
    val sourcesButtonPaddingHorizontal: Dp,
    val sourcesButtonGap: Dp,
    val addPanelWidth: Dp,
    val addVeil: Color,
    val suggestFill: Color,
    val suggestEdge: Color,
    val guidesPickWidth: Dp,
    val guidesPickFill: Color,
    val guidesPickVeil: Color,
    val scanPickWidth: Dp,
    val scanCardWidth: Dp,
    val scanBarHeight: Dp,
    val scanBarTrack: Color,
    val pickerPaddingEnd: Dp,
    val pickerInnerGap: Dp,
    val pickerValueMaxWidth: Dp,
    val scheduleHeadWidth: Dp,
    val scheduleHeadHeight: Dp,
    val scheduleHeadRadius: Dp,
    val scheduleHeadGap: Dp,
    val scheduleGap: Dp,
    val scheduleRowMinHeight: Dp,
    val scheduleRowRadius: Dp,
    val scheduleRowPaddingHorizontal: Dp,
    val scheduleRowInnerGap: Dp,
    val scheduleTimeWidth: Dp,
    val scheduleProgressWidth: Dp,
    val scheduleNowFill: Color,
    val scheduleListGap: Dp,
    val guideBandTop: Dp,
    val guideBandHeight: Dp,
    val guideBandPictureWidth: Dp,
    val guideBandRadius: Dp,
    val guideBandGap: Dp,
    val guideBandProgressWidth: Dp,
    val guideTop: Dp,
    val guideRulerHeight: Dp,
    val guideChipHeight: Dp,
    val guideChipPaddingHorizontal: Dp,
    val guideRowHeight: Dp,
    val guideCompactRowHeight: Dp,
    val guideGap: Dp,
    val guideChannelWidth: Dp,
    val guideNumberWidth: Dp,
    val guideLogoSize: Dp,
    val guideCompactLogoSize: Dp,
    val guideCellRadius: Dp,
    val guideCutRadius: Dp,
    val guideCellPaddingHorizontal: Dp,
    val guideCellFill: Color,
    val guideAiringFill: Color,
    val guideNoneEdge: Color,
    val guideEndStripe: Color,
    val guideMarkEdge: Color,
    val guideMidnightEdge: Color,
    val guideNowLineWidth: Dp
)

/**
 * Spec 81 tip card and thank-you (sandbox spec-81-donate v3, stages d-tip-us, e0-trigger, e1-thanks),
 * dp on the 960x540 canvas. Card: the sign-in card's fill, edge, radius and padding over [veil],
 * [cardTop] / [cardBottom], parts [cardGap] apart; a [qrTile] white tile at [qrRadius], the code
 * [qrInset] in from its edge. Intro (`.s81l`): [introTop], [introWidth], [introGap], the title
 * [titleSize] / [titleLineHeight]. Ladder chips: [chipHeight], [chipPaddingHorizontal], [chipGap],
 * [chipFill] ([chipMidFill] for the middle one) with a [chipEdge] hairline; [ladderGap] under the label.
 * [heart]: the Tip icon on dark; [heartOnFocus] on a white (focused) button or a lit row. About's
 * row (`.s81lead`, stage a-about): a [rowHeart] heart before the label, [rowHeartEnd] more after it
 * than the row's gap. Thank-you: a
 * [thanksHeart] heart in [heartGradient], [thanksTitleSize] / [thanksTitleLineHeight] title,
 * [thanksLineWidth] line, the signature in [signature], gaps [thanksHeartGap], [thanksLineTop],
 * [thanksSignatureTop], [thanksDoneTop]; sparks in [sparkColors].
 */
@Immutable
data class NuvioMarqueeTipTokens(
    val veil: Color,
    val cardTop: Dp,
    val cardBottom: Dp,
    val cardGap: Dp,
    val qrTile: Dp,
    val qrRadius: Dp,
    val qrInset: Dp,
    val introTop: Dp,
    val introWidth: Dp,
    val introGap: Dp,
    val titleSize: TextUnit,
    val titleLineHeight: TextUnit,
    val chipHeight: Dp,
    val chipPaddingHorizontal: Dp,
    val chipGap: Dp,
    val chipFill: Color,
    val chipMidFill: Color,
    val chipEdge: Color,
    val ladderGap: Dp,
    val termsWidth: Dp,
    val heart: Color,
    val heartOnFocus: Color,
    val rowHeart: Dp,
    val rowHeartEnd: Dp,
    val thanksHeart: Dp,
    val heartGradient: List<Color>,
    val thanksTitleSize: TextUnit,
    val thanksTitleLineHeight: TextUnit,
    val thanksLineWidth: Dp,
    val signature: Color,
    val thanksHeartGap: Dp,
    val thanksLineTop: Dp,
    val thanksSignatureTop: Dp,
    val thanksDoneTop: Dp,
    val sparkColors: List<Color>
)

/**
 * First-launch welcome card (`.l11qc.l33c`, sandbox u33-welcome-setup v3 stage a-summary), dp on
 * the 960x540 canvas: the sign-in card's fill, edge and radius over a [veil], [cardWidth] wide
 * from [cardTop] to [cardBottom] above the bottom, padded [cardPaddingHorizontal] x
 * [cardPaddingTop] / [cardPaddingBottom], parts [cardGap] apart, [rowsTop] more above the first
 * row. `.l33r` rows: at least [rowMinHeight], [rowFill] at [rowRadius], padded
 * [rowPaddingHorizontal], a [rowIconSize] icon [rowIconGap] before the label (a tick in
 * `readyText`, the play-time row's arrows in onImage3). [actionsTop] above the buttons.
 */
@Immutable
data class NuvioMarqueeWelcomeTokens(
    val veil: Color,
    val cardWidth: Dp,
    val cardTop: Dp,
    val cardBottom: Dp,
    val cardPaddingHorizontal: Dp,
    val cardPaddingTop: Dp,
    val cardPaddingBottom: Dp,
    val cardGap: Dp,
    val rowsTop: Dp,
    val rowMinHeight: Dp,
    val rowFill: Color,
    val rowRadius: Dp,
    val rowPaddingHorizontal: Dp,
    val rowIconSize: Dp,
    val rowIconGap: Dp,
    val actionsTop: Dp
)

/**
 * Toast (`.l10toast`; u10 stage 12, u11 stage 25): pill [fill] with a hairline [edge], padded
 * [paddingHorizontal] x [paddingVertical], [bottom] above the screen bottom, shown [durationMs].
 * The success toast (Spec 52 `.toast.ok`): a [successIconSize] check in [successIcon]
 * [successIconGap] before the text, a [successEdge] hairline, padded [successPaddingStart] /
 * [successPaddingEnd] x [successPaddingVertical].
 */
@Immutable
data class NuvioMarqueeToastTokens(
    val fill: Color,
    val edge: Color,
    val paddingHorizontal: Dp,
    val paddingVertical: Dp,
    val bottom: Dp,
    val durationMs: Long,
    val successEdge: Color,
    val successIcon: Color,
    val successIconSize: Dp,
    val successIconGap: Dp,
    val successPaddingStart: Dp,
    val successPaddingEnd: Dp,
    val successPaddingVertical: Dp
)

/**
 * `.l8r` form row (u8 collections, u11 settings): at least [minHeight] (Settings panes pass the
 * taller [NuvioMarqueeSettingsTokens.rowMinHeight]), [fill] at rest, `glassFill` + an inset `ring`
 * while focus is in it; [radius], padded [paddingHorizontal] x [paddingVertical], parts
 * [innerGap]. The label column wraps at [labelMaxWidth] so the trailing control keeps its room;
 * the helper wraps under the label in onImage3. A dimmed row draws at [dimAlpha] and stays
 * focusable; a destructive label is [destructiveText]. Icons [iconSize]; a label's leading icon
 * (Spec 52 `.s52ki`) [leadingIconSize], [leadingIconGap] before the label. `.l8sw` switch:
 * [switchWidth] x [switchHeight] track, [switchKnob] knob [switchKnobInset] in; off =
 * [switchOffTrack] + [switchOffKnob], on = white + black.
 */
@Immutable
data class NuvioMarqueeFormRowTokens(
    val minHeight: Dp,
    val fill: Color,
    val radius: Dp,
    val paddingHorizontal: Dp,
    val paddingVertical: Dp,
    val innerGap: Dp,
    val labelMaxWidth: Dp,
    val dimAlpha: Float,
    val destructiveText: Color,
    val iconSize: Dp,
    val leadingIconSize: Dp,
    val leadingIconGap: Dp,
    val switchWidth: Dp,
    val switchHeight: Dp,
    val switchKnob: Dp,
    val switchKnobInset: Dp,
    val switchOffTrack: Color,
    val switchOffKnob: Color
)

/**
 * Settings (approved sandbox `u11-settings-marquee` v6), dp on the 960x540 canvas. Rail and pane
 * start [topInset] below the top bar's content inset. `.l11rail`: a [railWidth] column, items
 * [railItemHeight] tall (a full pill when focused), [railItemGap] apart, padded
 * [railItemPaddingHorizontal], a [railIconSize] icon [railIconGap] before the label; the selected
 * item a [railBarWidth] x [railBarHeight] bar at its start; the pane [railToPane] after the rail.
 * About's amber dot while an update waits (Spec 52 `.s52dot`): [railDotSize], [railDotGap] after the label.
 * Pane: header and chips pinned, rows [rowGap] apart and at least [rowMinHeight] tall, a [fadeHeight] fade to
 * the field at the bottom. `.l11sec` section label [sectionTopPadding] above, [sectionBottomPadding]
 * below. `.l11note` [notePadding]. `.l8chips` section chips: [chipHeight] pills padded
 * [chipPaddingHorizontal], [chipGap] apart, [chipFill] at rest, selected glass + a
 * [selectedRingWidth] white inset ring, focused white with black text; [chipsBottomPadding] under
 * the row. `.l8seg` segments: [segmentHeight] pills padded [segmentPaddingHorizontal], [segmentGap]
 * apart on [segmentFill], selected as a chip. `.l11st` stepper: chevrons, a [stepperTrackWidth] x
 * [stepperTrackHeight] track on [stepperTrackFill] filled white, the value at least
 * [stepperValueMinWidth] wide; parts [stepperGap] apart. A panel's stepper option (Spec 51
 * `.s51stp`): a line [optionStepperTop] under its option row, [optionStepperInset] in from its
 * edges, on [optionStepperFill] at [optionStepperRadius], padded [optionStepperPaddingHorizontal] x
 * [optionStepperPaddingVertical], parts [optionStepperGap] apart, [optionStepperIconSize] chevrons
 * around a value at least [optionStepperValueMinWidth] wide; at [optionStepperDimAlpha] while its
 * option is not picked and the row has no focus. `.l11rl` Advanced options group: a
 * [groupRule] hairline each side of a [groupPillHeight] pill padded [groupPillPaddingHorizontal]
 * on [chipFill] (focused white), [groupGap] between them, [groupIconSize] chevron.
 * `.l11sw` theme swatches (stage 7): [swatchSize] circles [swatchGap] apart; selected: a
 * [swatchRingWidth] [swatchSelectedRing] ring [swatchRingGap] outside the circle; focused: the ring
 * white and the swatch scaled [swatchFocusScale].
 * `.l11cs` colour chip (stage 21, Text colour / Background values): a [colorChipSize] square at
 * [colorChipRadius] with a [colorChipRingWidth] [colorChipRing] inset ring, [colorChipGap] before
 * the colour's name.
 * `.l11pv` subtitle preview (stage 21, pinned under the Subtitles chip): [previewHeight] tall at
 * [previewRadius], [previewBottom] above the rows; the cue at [previewTextScale] of the player's
 * size; the PREVIEW tag in [previewTagText], [previewTagInsetStart] x [previewTagInsetTop] in, on
 * [previewTagFill] at [previewTagRadius], padded [previewTagPaddingHorizontal] x [previewTagPaddingVertical].
 * Panels (`.l8p`, in a Dialog window over the collections side panel's veil, inset like it from
 * the end): a picker (stage 18) [pickerWidth] wide, [pickerInsetVertical] from the top and bottom,
 * a caption under its eyebrow [panelTextInset] in; the QR panel (`.l11qp`, stage 12)
 * [qrPanelWidth] wide, its QR [qrSize] square, parts [qrGap] apart, the action [qrActionHeight]
 * tall padded [qrActionPaddingHorizontal]. The TMDB key panel (Spec 52 `.s52msg`, `.btn.busy`): a
 * message line's icon [messageIconSize], [messageIconGap] before the text; the busy action at
 * [busyAlpha]; its phone block (`.s52qrrow`) a white QR tile [keyQrSize] square at [keyQrRadius],
 * the code filling [keyQrFill] of it, [keyQrGap] before the text.
 * `.l11pass` results panel (stage 24): [resultsFill] at [resultsRadius], padded
 * [resultsPaddingHorizontal] x [resultsPaddingVertical], lines [resultsLineGap] apart.
 * Account (stage 1): `.l11acc` block, a [accountAvatarSize] avatar [accountGap] before the text,
 * [accountBlockBottom] under it; the `.l11tbl` synced-data table (on the results panel) shows
 * [tableAvatarSize] avatars, cells padded [tableCellPaddingVertical].
 * QR sign-in route (stages 4, 5): the intro column [signInIntroTop] down, [signInIntroWidth] wide,
 * parts [signInIntroGap] apart, onboarding's Continue button [signInIntroActionTop] more below the hint
 * (sandbox spec-49-sign-in-buttons v2); the `.l11qc` card [signInCardWidth] wide from [signInCardTop] to
 * [signInCardBottom] above the bottom, [signInCardRadius] corners, [signInCardFill] (opaque, so nothing behind
 * shows through; shared by the welcome and What's new cards: Spec 80 D9) with a
 * [signInCardEdge] hairline, padded [signInCardPaddingHorizontal] x [signInCardPaddingTop] /
 * [signInCardPaddingBottom], parts [signInCardGap] apart; the QR tile [signInQrRadius] corners
 * ([qrSize] square) under [signInExpiredVeil] once dead; the status dot [signInStatusDot]; the
 * Server pill [signInPillTop] down.
 */
@Immutable
data class NuvioMarqueeSettingsTokens(
    val railWidth: Dp,
    val topInset: Dp,
    val railItemHeight: Dp,
    val railItemGap: Dp,
    val railItemPaddingHorizontal: Dp,
    val railIconSize: Dp,
    val railIconGap: Dp,
    val railBarWidth: Dp,
    val railBarHeight: Dp,
    val railDotSize: Dp,
    val railDotGap: Dp,
    val railToPane: Dp,
    val rowGap: Dp,
    val rowMinHeight: Dp,
    val fadeHeight: Dp,
    val headerToChips: Dp,
    val sectionTopPadding: Dp,
    val sectionBottomPadding: Dp,
    val notePadding: Dp,
    val chipHeight: Dp,
    val chipPaddingHorizontal: Dp,
    val chipGap: Dp,
    val chipFill: Color,
    val chipsBottomPadding: Dp,
    val selectedRingWidth: Dp,
    val segmentHeight: Dp,
    val segmentPaddingHorizontal: Dp,
    val segmentGap: Dp,
    val segmentFill: Color,
    val stepperTrackWidth: Dp,
    val stepperTrackHeight: Dp,
    val stepperTrackFill: Color,
    val stepperValueMinWidth: Dp,
    val stepperGap: Dp,
    val optionStepperTop: Dp,
    val optionStepperInset: Dp,
    val optionStepperFill: Color,
    val optionStepperRadius: Dp,
    val optionStepperPaddingHorizontal: Dp,
    val optionStepperPaddingVertical: Dp,
    val optionStepperGap: Dp,
    val optionStepperIconSize: Dp,
    val optionStepperValueMinWidth: Dp,
    val optionStepperDimAlpha: Float,
    val groupRule: Color,
    val groupPillHeight: Dp,
    val groupPillPaddingHorizontal: Dp,
    val groupGap: Dp,
    val groupIconSize: Dp,
    val swatchSize: Dp,
    val swatchGap: Dp,
    val swatchRingGap: Dp,
    val swatchRingWidth: Dp,
    val swatchSelectedRing: Color,
    val swatchFocusScale: Float,
    val colorChipSize: Dp,
    val colorChipRadius: Dp,
    val colorChipRingWidth: Dp,
    val colorChipRing: Color,
    val colorChipGap: Dp,
    val previewHeight: Dp,
    val previewRadius: Dp,
    val previewBottom: Dp,
    val previewTextScale: Float,
    val previewTagInsetStart: Dp,
    val previewTagInsetTop: Dp,
    val previewTagText: Color,
    val previewTagFill: Color,
    val previewTagRadius: Dp,
    val previewTagPaddingHorizontal: Dp,
    val previewTagPaddingVertical: Dp,
    val pickerWidth: Dp,
    val pickerInsetVertical: Dp,
    val panelTextInset: Dp,
    val qrPanelWidth: Dp,
    val qrSize: Dp,
    val qrGap: Dp,
    val qrActionHeight: Dp,
    val qrActionPaddingHorizontal: Dp,
    val messageIconSize: Dp,
    val messageIconGap: Dp,
    val busyAlpha: Float,
    val keyQrSize: Dp,
    val keyQrRadius: Dp,
    val keyQrFill: Float,
    val keyQrGap: Dp,
    val resultsFill: Color,
    val resultsRadius: Dp,
    val resultsPaddingHorizontal: Dp,
    val resultsPaddingVertical: Dp,
    val resultsLineGap: Dp,
    val accountAvatarSize: Dp,
    val accountGap: Dp,
    val accountBlockBottom: Dp,
    val tableAvatarSize: Dp,
    val tableCellPaddingVertical: Dp,
    val signInIntroTop: Dp,
    val signInIntroWidth: Dp,
    val signInIntroGap: Dp,
    val signInIntroActionTop: Dp,
    val signInCardWidth: Dp,
    val signInCardTop: Dp,
    val signInCardBottom: Dp,
    val signInCardRadius: Dp,
    val signInCardFill: Color,
    val signInCardEdge: Color,
    val signInCardPaddingHorizontal: Dp,
    val signInCardPaddingTop: Dp,
    val signInCardPaddingBottom: Dp,
    val signInCardGap: Dp,
    val signInQrRadius: Dp,
    val signInExpiredVeil: Color,
    val signInStatusDot: Dp,
    val signInPillTop: Dp
)

/** Profile picker and PIN screens (approved sandbox `u10-profiles-marquee` v3), dp on the 960x540 canvas. */
@Immutable
data class NuvioMarqueeProfilesTokens(
    /** PIN screen (stages 3, 4, 9): one centred column [pinTop] down: avatar [pinAvatarSize], then
     *  title, boxes, support line, keypad and footer, each [pinAvatarToTitle].. [pinKeypadToFooter] apart. */
    val pinTop: Dp,
    val pinAvatarSize: Dp,
    val pinAvatarToTitle: Dp,
    val pinTitleToBoxes: Dp,
    val pinBoxesToSupport: Dp,
    val pinSupportToKeypad: Dp,
    val pinKeypadToFooter: Dp,
    /** PIN boxes (`.l10pin`): [boxWidth]x[boxHeight], radius [boxRadius], [boxGap] apart. Empty: a
     *  [boxOutlineWidth] [boxOutline]; filled: [boxFilledOutline] on [boxFilledFill] with a [boxDot]
     *  white dot; the next box: a [boxActiveOutlineWidth] white outline on [boxActiveFill] with a
     *  [boxCursorWidth]x[boxCursorHeight] cursor; error: [boxErrorOutline] at the active width on [boxErrorFill]. */
    val boxWidth: Dp,
    val boxHeight: Dp,
    val boxRadius: Dp,
    val boxGap: Dp,
    val boxOutlineWidth: Dp,
    val boxOutline: Color,
    val boxFilledOutline: Color,
    val boxFilledFill: Color,
    val boxDot: Dp,
    val boxActiveOutlineWidth: Dp,
    val boxActiveFill: Color,
    val boxCursorWidth: Dp,
    val boxCursorHeight: Dp,
    val boxErrorOutline: Color,
    val boxErrorFill: Color,
    /** On-screen keypad (`.l10kp`, 3x4): [keyWidth]x[keyHeight] pills [keyGap] apart on [keyFill];
     *  focused white with black text at [keyFocusScale]; [keyDisabledAlpha] while locked out. */
    val keyWidth: Dp,
    val keyHeight: Dp,
    val keyGap: Dp,
    val keyFill: Color,
    val keyFocusScale: Float,
    val keyDisabledAlpha: Float,
    val keyIconSize: Dp,
    /** The PIN screen's support line in its error state (`.l10s.err`). */
    val pinErrorText: Color,
    /** D-pad combination screens (Spec 43 stages d-*, s-*): the PIN screen's column (pinTop, avatar,
     *  title, then slots at [pinTitleToBoxes], support at [pinBoxesToSupport]), then the ring
     *  [dpadSupportToRing] below the support line and the key hint [dpadRingToFooter] below the ring.
     *  Ring (`.ring`): [dpadRingSize] on [dpadRingFill] with a [boxOutlineWidth] [dpadRingEdge] edge,
     *  chevrons [dpadChevronSize] in [dpadChevronInset] from the edge, a [dpadRingCentreSize] centre
     *  key on [dpadRingCentreFill]; a press lights a [boxActiveOutlineWidth] white edge, a
     *  [dpadRingHaloWidth] [dpadRingHalo] halo and the accent glow: an eased rise over
     *  [dpadPulseAttackMs], an eased fall over [dpadPulseDecayMs] (Spec 43f F2).
     *  Slots (`.slots`): [dpadSlotWidth]x[dpadSlotHeight], radius [dpadSlotRadius], [dpadSlotGap]
     *  apart, empty edge [dpadSlotOutline], dot [dpadSlotDot]; filled / next / error states use the
     *  PIN box colours; success uses [successOutline] on [successFill], text [successText], faded
     *  in over [successFadeMs] on ring and slots (Spec 43f F3). */
    val dpadSupportToRing: Dp,
    val dpadRingToFooter: Dp,
    val dpadRingSize: Dp,
    val dpadRingFill: Color,
    val dpadRingEdge: Color,
    val dpadChevronSize: Dp,
    val dpadChevronInset: Dp,
    val dpadRingCentreSize: Dp,
    val dpadRingCentreFill: Color,
    val dpadRingHalo: Color,
    val dpadRingHaloWidth: Dp,
    val dpadPulseAttackMs: Int,
    val dpadPulseDecayMs: Int,
    val successFadeMs: Int,
    val dpadSlotWidth: Dp,
    val dpadSlotHeight: Dp,
    val dpadSlotRadius: Dp,
    val dpadSlotGap: Dp,
    val dpadSlotOutline: Color,
    val dpadSlotDot: Dp,
    /** The setup screen's D-pad glyph in its [pinAvatarSize] disc (stage s-create). */
    val dpadGlyphIconSize: Dp,
    val successOutline: Color,
    val successFill: Color,
    val successText: Color,
    /** Options side panel (stage 6): [optionsPanelWidth] wide; its head row (`.l10ph`) pads
     *  [optionsHeadPaddingHorizontal] / [optionsHeadPaddingTop] / [optionsHeadPaddingBottom], avatar
     *  [optionsAvatarSize], [optionsHeadGap] to the name. */
    val optionsPanelWidth: Dp,
    val optionsHeadPaddingHorizontal: Dp,
    val optionsHeadPaddingTop: Dp,
    val optionsHeadPaddingBottom: Dp,
    val optionsAvatarSize: Dp,
    val optionsHeadGap: Dp,
    /** Picker (Spec 43 stages picker, six, manage), tops on the 960x540 canvas: wordmark
     *  [wordmarkHeight] at the breadcrumb's spot; title at [titleTop] ([titleTopWithSubtitle] in
     *  Manage); Manage subtitle at [subtitleTop]; the row at [rowTop] ([rowTopWithSubtitle] under a
     *  subtitle); the key hint [hintBottom] above the bottom. */
    val wordmarkHeight: Dp,
    val titleTop: Dp,
    val titleTopWithSubtitle: Dp,
    val subtitleTop: Dp,
    val rowTop: Dp,
    val rowTopWithSubtitle: Dp,
    val hintBottom: Dp,
    /** Cards (`.cD`): avatar [avatarSize] [cardGap] apart, [avatarSizeCompact] / [cardGapCompact]
     *  at six items; the card is as wide as its avatar, focus ring [ringGap] outside it, focused at
     *  [cardFocusScale]. [avatarToName] to the name (a [nameLineHeight] box, so the focused 18 sp
     *  name never reflows), then on focus only [nameToDetails] to PRIMARY / the addon line,
     *  [cardLineGap] apart; PRIMARY in [primaryTag]. Add profile: [addFill] with a [addEdgeWidth]
     *  [addEdge] edge, + [addIconSize]. */
    val avatarSize: Dp,
    val avatarSizeCompact: Dp,
    val cardGap: Dp,
    val cardGapCompact: Dp,
    val cardFocusScale: Float,
    val ringGap: Dp,
    val avatarToName: Dp,
    val nameLineHeight: Dp,
    val nameToDetails: Dp,
    val cardLineGap: Dp,
    val primaryTag: Color,
    val addFill: Color,
    val addEdge: Color,
    val addEdgeWidth: Dp,
    val addIconSize: Dp,
    /** Lock badge (`.l10lk`): [lockBadgeSize] black disc in the avatar's bottom-end corner, a
     *  [lockBadgeEdgeWidth] [lockBadgeEdge] edge, lock icon [lockIconSize]. */
    val lockBadgeSize: Dp,
    val lockBadgeEdge: Color,
    val lockBadgeEdgeWidth: Dp,
    val lockIconSize: Dp,
    /** Avatar-art field (Spec 43, sandbox `u43-profiles-redesign` v4; picker, PIN and D-pad screens):
     *  the profile's avatar, pre-blurred, at [artImageAlpha] over the colour gradient; a radial veil
     *  from the avatar colour at [artTintAlpha] in the centre to black [artVeilMid] at 55% and
     *  [artVeilEdge] at the edge; black [artTopScrim] / [artBottomScrim] scrims at the top / bottom. */
    val artImageAlpha: Float,
    val artTintAlpha: Float,
    val artVeilMid: Float,
    val artVeilEdge: Float,
    val artTopScrim: Float,
    val artBottomScrim: Float,
    /** A chosen background (bundled, catalog, custom URL, My photo) draws under this one veil
     *  (Spec 44 D3, sandbox u44 stage 8 `.l8veil`). */
    val backgroundVeil: Color,
    /** Editor avatar picker (`.l10gp`): category pills [pickerChipGap] apart (also the gap from
     *  the pills to the grid and between the Avatar | Background tabs); a fixed
     *  [pickerColumns]-column grid of [pickerAvatarSize] avatars, [pickerGapHorizontal] x
     *  [pickerGapVertical] apart, padded [pickerGridPaddingVertical] so the focused ring isn't
     *  clipped. Selected: a [pickerSelectedRingWidth] [pickerSelectedRing] ring [pickerRingGap] out;
     *  focused: [pickerFocusScale], the white ring and the avatar-colour glow. */
    val pickerChipGap: Dp,
    val pickerColumns: Int,
    val pickerAvatarSize: Dp,
    val pickerGapHorizontal: Dp,
    val pickerGapVertical: Dp,
    val pickerGridPaddingVertical: Dp,
    val pickerSelectedRing: Color,
    val pickerSelectedRingWidth: Dp,
    val pickerRingGap: Dp,
    val pickerFocusScale: Float,
    /** Editor (stages 7, 8) on the 960x540 canvas: colour field under [editorVeil]; the preview
     *  column [editorPreviewWidth] wide at [editorPreviewTop] (avatar [editorAvatarSize], items
     *  [editorPreviewGap] apart, the Name field [editorFieldTop] below the name); a [editorRule]
     *  hairline [editorRuleStart] from the left, [editorRuleBottom] above the bottom; the picker
     *  column [editorPickerWidth] wide at [editorPickerStart] / [editorPickerTop], items
     *  [editorPickerGap] apart. Name field (`.l10in`, fills and edge from `search`): [fieldHeight],
     *  radius [fieldRadius], padded [fieldPaddingHorizontal]; label to field [fieldLabelGap]. */
    val editorVeil: Color,
    val editorPreviewWidth: Dp,
    val editorPreviewTop: Dp,
    val editorAvatarSize: Dp,
    val editorPreviewGap: Dp,
    val editorFieldTop: Dp,
    val editorRule: Color,
    val editorRuleStart: Dp,
    val editorRuleBottom: Dp,
    val editorPickerStart: Dp,
    val editorPickerTop: Dp,
    val editorPickerWidth: Dp,
    val editorPickerGap: Dp,
    val fieldHeight: Dp,
    val fieldRadius: Dp,
    val fieldPaddingHorizontal: Dp,
    val fieldLabelGap: Dp
)

/** Collections screens (approved sandbox `u8-collections-marquee`), dp on the 960x540 canvas. */
@Immutable
data class NuvioMarqueeCollectionsTokens(
    /** Stage 6 `.l8veil`: black over the first collection's backdrop. */
    val veil: Color,
    /** `.l8top`: header buttons [headerTop] from the top, glass pills as tall as the compact
     *  primary (New collection), padded [headerButtonPaddingHorizontal], [headerButtonGap] apart. */
    val headerTop: Dp,
    val headerButtonPaddingHorizontal: Dp,
    val headerButtonGap: Dp,
    /** `.l8col`: the list starts at [listTop], rows [rowGap] apart. */
    val listTop: Dp,
    val rowGap: Dp,
    /** `.l8s`: [rowFill] at rest, `glassFill` + an inset `ring` when focus is in the row or its
     *  actions; [rowRadius], padded [rowPaddingHorizontal] x [rowPaddingVertical], parts [rowInnerGap]. */
    val rowFill: Color,
    val rowRadius: Dp,
    val rowPaddingHorizontal: Dp,
    val rowPaddingVertical: Dp,
    val rowInnerGap: Dp,
    /** `.l8th`: [thumbWidth] x [thumbHeight], [thumbRadius], [thumbFill] behind a missing image. */
    val thumbWidth: Dp,
    val thumbHeight: Dp,
    val thumbRadius: Dp,
    val thumbFill: Color,
    /** `.l8act`: the focused row's action pills, [actionHeight] tall, padded
     *  [actionPaddingHorizontal], [actionGap] apart, inset [rowPaddingHorizontal]. */
    val actionHeight: Dp,
    val actionPaddingHorizontal: Dp,
    val actionGap: Dp,
    /** Stage 8 `.dim` + `.l8p`: the import panel, [importWidth] wide over the [panelVeil]; its
     *  children [panelGap] apart; the Source row is a `formRow` row. The
     *  `.l8fld` URL pill is [fieldHeight] tall, padded [fieldPaddingStart] (end, fill, edge and
     *  focus as the search field's); its Fetch button is an `.l8act` pill. */
    val panelVeil: Color,
    val importWidth: Dp,
    val panelGap: Dp,
    val fieldHeight: Dp,
    val fieldPaddingStart: Dp,
    /** Stage 10 editor: form column [editorFormWidth] wide from the safe edge at [listTop], the
     *  preview column after [editorColumnGap] to the other safe edge; `.l8sec` section labels
     *  [sectionTopPadding] above (none for the first) and [sectionBottomPadding] below. */
    val editorFormWidth: Dp,
    val editorColumnGap: Dp,
    val sectionTopPadding: Dp,
    val sectionBottomPadding: Dp,
    /** Preview card: [previewHeight] tall, [previewRadius], the collection backdrop under the
     *  home scrim; title at [previewInset]; mini tiles [previewTileGap] apart from
     *  [previewTilesTop], poster [previewTileWidth] x [previewTileHeight], landscape
     *  [previewLandscapeTileWidth] x [previewLandscapeTileHeight], corners [previewTileRadius]. */
    val previewHeight: Dp,
    val previewRadius: Dp,
    val previewInset: Dp,
    val previewTilesTop: Dp,
    val previewTileGap: Dp,
    val previewTileWidth: Dp,
    val previewTileHeight: Dp,
    val previewLandscapeTileWidth: Dp,
    val previewLandscapeTileHeight: Dp,
    val previewTileRadius: Dp,
    /** `.l8th.pst`: a folder row's poster thumb is [thumbPosterWidth] wide (landscape: [thumbWidth]). */
    val thumbPosterWidth: Dp,
    /** `.l8add`: Add folder, a compact glass pill padded [addButtonPaddingHorizontal]. */
    val addButtonPaddingHorizontal: Dp,
    /** Stage 11 folder preview: [folderPreviewHeight] tall (otherwise as the collection preview);
     *  the title logo [folderPreviewLogoHeight] x up to [folderPreviewLogoMaxWidth]; one tile
     *  [folderPreviewTileWidth] x [folderPreviewTileHeight] (landscape: the preview's landscape
     *  tile), [folderPreviewTileTop] down and [folderPreviewTileEnd] in from the end. */
    val folderPreviewHeight: Dp,
    val folderPreviewLogoHeight: Dp,
    val folderPreviewLogoMaxWidth: Dp,
    val folderPreviewTileWidth: Dp,
    val folderPreviewTileHeight: Dp,
    val folderPreviewTileTop: Dp,
    val folderPreviewTileEnd: Dp,
    /** `.l8s.miss` / `.l8s.old`: a [sourceOutlineWidth] outline in [sourceMissingOutline] (addon
     *  not installed) or [sourceRetiredOutline] (Trakt list) while the row is not focused. */
    val sourceOutlineWidth: Dp,
    val sourceMissingOutline: Color,
    val sourceRetiredOutline: Color,
    /** Stages 12-14 `.l8p` side panel: [sidePanelWidth] wide, [sidePanelInset] from the end and
     *  (when it fills the height) from the top and bottom, over the [panelVeil]; stage 15's TMDB
     *  panel is [tmdbPanelWidth] wide. */
    val sidePanelWidth: Dp,
    val tmdbPanelWidth: Dp,
    val sidePanelInset: Dp,
    /** Stage 12 `.l8emo-g`: square emoji cells, six to a line [emojiGap] apart, [emojiCellRadius],
     *  [emojiCellFill] at rest; the current emoji in glass with a [emojiSelectedRing]
     *  (`ringWidth`) inset ring; focused white, scaled [emojiFocusScale]. */
    val emojiGap: Dp,
    val emojiCellRadius: Dp,
    val emojiCellFill: Color,
    val emojiSelectedRing: Color,
    val emojiFocusScale: Float,
    /** Stage 12 Image URL page: the cover preview, poster [coverPreviewWidth] x
     *  [coverPreviewHeight], landscape [coverPreviewLandscapeWidth] x [coverPreviewLandscapeHeight]. */
    val coverPreviewWidth: Dp,
    val coverPreviewHeight: Dp,
    val coverPreviewLandscapeWidth: Dp,
    val coverPreviewLandscapeHeight: Dp
)

/** Player panels (approved sandbox `u6-player-panels-marquee`), dp on the 960x540 canvas. */
@Immutable
data class NuvioMarqueePlayerPanelTokens(
    /** Compact panel (stage 4, `.cpn`): bottom-end, [compactWidth] wide, inset [compactEndInset]
     *  from the end and [compactVerticalInset] from the bottom (and the top, when a list fills it). */
    val compactWidth: Dp,
    val compactEndInset: Dp,
    val compactVerticalInset: Dp,
    /** Stepper row (stage 5, `.adj`): label, [-] value [+], helper under. */
    val adjustPaddingHorizontal: Dp,
    val adjustPaddingTop: Dp,
    val adjustPaddingBottom: Dp,
    val adjustGap: Dp,
    val stepperSize: Dp,
    val stepperIconSize: Dp,
    val valueMinWidth: Dp,
    val helperTop: Dp,
    /** A stepper at its limit, or a whole row that does not apply (passthrough). */
    val disabledAlpha: Float,
    /** Gap above the row after the steppers (the Persist action). */
    val actionTop: Dp,
    /** A second control line under an adjust row's label line (the text-edge segments). */
    val adjustBelowTop: Dp,
    /** Colour dots (stage 7, `.dot`): [dotSize] ([dotSizeSmall] for the outline set), [dotGap] /
     *  [dotGapSmall] apart, a 1 dp [dotEdge] inside. Selected = a [dotRingGap] black gap then a
     *  [dotRingWidth] [dotSelectedRing] ring; focused = the same ring in white, scaled [dotFocusScale]. */
    val dotSize: Dp,
    val dotSizeSmall: Dp,
    val dotGap: Dp,
    val dotGapSmall: Dp,
    val dotEdge: Color,
    val dotRingGap: Dp,
    val dotRingWidth: Dp,
    val dotSelectedRing: Color,
    val dotFocusScale: Float,
    /** Pills (stage 7 `.tgl` / `.seg`): [pillHeight] high; the toggle padded [togglePaddingHorizontal]
     *  in `glassFill`; segments padded [segmentPaddingHorizontal], [segmentGap] apart, unselected in
     *  [segmentFill] with `onImage3` text, selected in `glassFill`. Focused = white, black text. */
    val pillHeight: Dp,
    val togglePaddingHorizontal: Dp,
    val segmentPaddingHorizontal: Dp,
    val segmentGap: Dp,
    val segmentFill: Color,
    /** Centred compact shells (stages 8-9, 16, `.cpc`): padded [centredPaddingHorizontal] /
     *  [centredPaddingVertical]; their text starts [contentInset] in. The delay strip is [stripWidth]
     *  wide, [stripTop] from the top; the Sync line [syncWidth] wide, [syncTop] down, over a flat
     *  [syncScrim]. */
    val centredPaddingHorizontal: Dp,
    val centredPaddingVertical: Dp,
    val contentInset: Dp,
    val stripWidth: Dp,
    val stripTop: Dp,
    val syncWidth: Dp,
    val syncTop: Dp,
    val syncScrim: Color,
    /** Delay slider (`.sld`): [sliderHeight] box ([sliderTop] / [sliderBottom] around it), an
     *  [sliderTrackHeight] [sliderTrack] track, a [zeroMarkWidth] x [zeroMarkHeight] [zeroMark] at
     *  0 ms; the knob is the OSD seek playhead (osd.seekThumb*). */
    val sliderHeight: Dp,
    val sliderTop: Dp,
    val sliderBottom: Dp,
    val sliderTrackHeight: Dp,
    val sliderTrack: Color,
    val zeroMarkWidth: Dp,
    val zeroMarkHeight: Dp,
    val zeroMark: Color,
    /** Small glass buttons (`.btn.sm`): [smallButtonHeight] tall, padded [smallButtonPadding],
     *  [smallButtonGap] apart. */
    val smallButtonHeight: Dp,
    val smallButtonPadding: Dp,
    val smallButtonGap: Dp,
    /** Sync line cue rows: the time column [cueTimeWidth]; [cueListMaxHeight] = six rows of 34 dp (bodyCompact's
     *  20 sp line + CueRow's 7 dp vertical padding each side): change those and this together. */
    val cueTimeWidth: Dp,
    val cueListMaxHeight: Dp,
    /** Pause overlay (stages 11-11c, `.pscrim` / `.pscrimB` / `.pblk`): start and bottom scrims as
     *  (fraction, black alpha); the block bottom-start [pauseBlockBottom] up, its lines
     *  [pauseBlockWidth] wide at most and [pauseBlockGap] apart, the plot [pausePlotWidth]. */
    val pauseScrimStartStops: List<Pair<Float, Float>>,
    val pauseScrimBottomStops: List<Pair<Float, Float>>,
    val pauseBlockWidth: Dp,
    val pauseBlockBottom: Dp,
    val pauseBlockGap: Dp,
    val pausePlotWidth: Dp,
    /** Cast row (`.ctl` / `.crow` / `.cch`): [castLabelTop] more above the label, [castChipGap]
     *  under it; the row [castRowWidth] wide, its end fading out from [castRowFadeStart]; chips [castChipHeight] tall,
     *  [castChipGap] apart, padded [castChipPaddingStart] / [castChipPaddingEnd], a [castAvatarSize]
     *  photo or initials circle (fill picked from [castAvatarColors] by name) [castChipGap] before
     *  the name, which ellipsizes past [castChipNameMaxWidth] (addon text). */
    val castLabelTop: Dp,
    val castRowWidth: Dp,
    val castRowFadeStart: Float,
    val castChipHeight: Dp,
    val castChipGap: Dp,
    val castChipPaddingStart: Dp,
    val castChipPaddingEnd: Dp,
    val castChipNameMaxWidth: Dp,
    val castAvatarSize: Dp,
    val castAvatarColors: List<Color>,
    /** Cast detail (`.cdet`): a [castPhotoWidth] x [castPhotoHeight] photo or initials tile (radius
     *  [castPhotoRadius]) [castDetailGap] before the text, whose lines sit [castDetailTextGap] apart
     *  and [castDetailTextBottom] above the photo's bottom; the BACK hint [castBackTop] more below. */
    val castPhotoWidth: Dp,
    val castPhotoHeight: Dp,
    val castPhotoRadius: Dp,
    val castDetailGap: Dp,
    val castDetailTextGap: Dp,
    val castDetailTextBottom: Dp,
    val castBackTop: Dp,
    /** Loading hand-off (stages 12-14, `.lbar` / `.stl` / `.spn`) under the Stream title block: a
     *  [handOffBarWidth] x [handOffBarHeight] bar ([handOffBarTop] more above it) on a
     *  [handOffBarTrack] track, its white fill glowing in the accent at [handOffGlowAlpha]; the
     *  status line [handOffStatusTop] more below, its parts [handOffStatusGap] apart, led by the
     *  percentage or, before any progress, a [handOffSpinnerSize] spinner on a [handOffSpinnerTrack] ring;
     *  its source line ellipsizes past [handOffSourceMaxWidth]. */
    val handOffBarWidth: Dp,
    val handOffBarHeight: Dp,
    val handOffBarTop: Dp,
    val handOffBarTrack: Color,
    val handOffGlowAlpha: Float,
    val handOffStatusTop: Dp,
    val handOffStatusGap: Dp,
    val handOffSpinnerSize: Dp,
    val handOffSpinnerTrack: Color,
    val handOffSourceMaxWidth: Dp,
    /** Episodes panel rows (stage 2, `.er`): [episodeRowRadius] rows on [episodeRowFill] (focused =
     *  `glassFill` + the white inset ring, as the source rows) padded [episodeRowPaddingVertical] /
     *  [episodeRowPaddingHorizontal], still and text [episodeRowGap] apart. The still is
     *  [episodeStillWidth] x [episodeStillHeight], radius [episodeStillRadius], on [episodeStillFill];
     *  its E-chip, [episodeTickSize] watched tick ([episodeTickIconSize] check) and progress bar
     *  ([episodeProgressBottom] from the bottom) sit [episodeStillInset] in. Text lines are
     *  [episodeTextGap] apart, the meta parts [episodeMetaGap] apart. */
    val episodeRowRadius: Dp,
    val episodeRowFill: Color,
    val episodeRowPaddingVertical: Dp,
    val episodeRowPaddingHorizontal: Dp,
    val episodeRowGap: Dp,
    val episodeStillWidth: Dp,
    val episodeStillHeight: Dp,
    val episodeStillRadius: Dp,
    val episodeStillFill: Color,
    val episodeStillInset: Dp,
    val episodeTickSize: Dp,
    val episodeTickIconSize: Dp,
    val episodeProgressBottom: Dp,
    val episodeTextGap: Dp,
    val episodeMetaGap: Dp,
    /** End-of-episode prompt (stage 16): a centred [endPromptWidth] shell (the centred padding and
     *  `contentInset`) over a [endPromptDim] dim; body in [endPromptBody] with [endPromptBodyBottom]
     *  under it; the next-episode row is [endPromptRowMargin] in with [endPromptRowBottom] below it. */
    val endPromptWidth: Dp,
    val endPromptDim: Color,
    val endPromptBody: Color,
    val endPromptBodyBottom: Dp,
    val endPromptRowMargin: Dp,
    val endPromptRowBottom: Dp
)

/** Stream screen (approved sandbox `u5-stream-screen-marquee`), dp on the 960x540 canvas. */
@Immutable
data class NuvioMarqueeStreamTokens(
    /** Title block: bottom-start at (tvSafeHorizontal, [titleBlockBottom] above the bottom),
     *  [titleBlockWidth] wide, its lines [titleBlockGap] apart. */
    val titleBlockWidth: Dp,
    val titleBlockBottom: Dp,
    val titleBlockGap: Dp,
    /** Sources panel: the compact-panel shell, [panelWidth] wide, [panelInset] from the screen's
     *  top, end and bottom, padded [panelPaddingTop] / [panelPaddingHorizontal] and nothing at the
     *  bottom (the list scrolls under the panel edge; its bottom content padding is
     *  [panelPaddingHorizontal] so the last row clears the rounded corner), children [panelGap] apart. The eyebrow is inset
     *  [eyebrowInset]; an optional context line sits [contextTop] under it (u6 stage 3); a 1 dp
     *  [rule] separates the circles from the list. */
    val panelWidth: Dp,
    val panelInset: Dp,
    val panelPaddingTop: Dp,
    val panelPaddingHorizontal: Dp,
    val panelGap: Dp,
    val eyebrowInset: Dp,
    val contextTop: Dp,
    val rule: Color,
    /** The row under the eyebrow is [chipHeight] high: since Spec 65 [refreshSize] circles
     *  [chipGap] apart (`circle*` below), then the pinned chips
     *  ([com.nuvio.tv.ui.components.marquee.MarqueePinnedChip], stage 2): [chipHeight] glass pills
     *  padded [chipPadding], a [pinMarkSize] origin icon / name / count [chipInnerGap] apart (the
     *  sheets' filter values use that gap too). A saved filter that is on fills `circleOnFill`; the
     *  addon view showing has a [chipSelectedRing] inset white ring. The chips scroll under the
     *  circles' end; while more follow, the row's last [pinFadeWidth] fade into [pinFade]. */
    val chipHeight: Dp,
    val chipPadding: Dp,
    val chipInnerGap: Dp,
    val chipGap: Dp,
    val refreshSize: Dp,
    val chipSelectedRing: Dp,
    val pinMarkSize: Dp,
    val pinFadeWidth: Dp,
    val pinFade: Color,
    /** Source rows: [rowRadius] cards in [rowFill] (focused: `glassFill` + an inset `ring`, no
     *  scale), padded [rowPaddingHorizontal] / [rowPaddingTop] / [rowPaddingBottom], [rowGap] apart.
     *  The main column keeps [rowLineGap] between badges (themselves [badgeGap] apart), name and
     *  file name; the side column sits [rowColumnGap] after it, starts [rowSideTop] lower, its
     *  addon name and languages line at most [rowSideMaxWidth] wide (not in the mockup, whose addon
     *  names are short: a long one must not squeeze the release text; the over-limit label is
     *  exempt) and keeps [sideGap] between its lines and between languages,
     *  dot and the [cachedIconSize] cached bolt in [cachedTint]. */
    val rowRadius: Dp,
    val rowFill: Color,
    val rowPaddingHorizontal: Dp,
    val rowPaddingTop: Dp,
    val rowPaddingBottom: Dp,
    val rowGap: Dp,
    val rowLineGap: Dp,
    val badgeGap: Dp,
    val rowColumnGap: Dp,
    val rowSideTop: Dp,
    val rowSideMaxWidth: Dp,
    val sideGap: Dp,
    val cachedIconSize: Dp,
    val cachedTint: Color,
    /** The decode line under a row's file name (Spec 65 P8, sandbox `spec-65-stream-filters` stage
     *  11 `.p8ln`): a [decodeLineIconSize] icon [decodeLineIconGap] before one line; `limitWarn`
     *  and a warning icon for a stream decoded in software, the rest of that row at
     *  [rowSoftwareAlpha]; `onImage2` and an info icon for one that plays reduced. */
    val decodeLineIconSize: Dp,
    val decodeLineIconGap: Dp,
    val rowSoftwareAlpha: Float,
    /** The player's "Playing" marker leads with a [markerIconSize] play icon, [markerIconGap] before its label. */
    val markerIconSize: Dp,
    val markerIconGap: Dp,
    /** Missing-upstreams foot (Spec 42 D7, sandbox `s42b-stream-upstreams` stage 4): pinned under a
     *  `rule` at the panel bottom, padded [missingFootTop] / [missingFootBottom], a [missingIconSize]
     *  warning icon [missingIconGap] before one line in [missingText] (its suffix in `onImage3`). */
    val missingFootTop: Dp,
    val missingFootBottom: Dp,
    val missingIconSize: Dp,
    val missingIconGap: Dp,
    val missingText: Color,
    /** Stream limit (Spec 51, sandbox `spec-51-network-stream-limit` v3; since Spec 65 the hidden
     *  circle and the hidden sheet's entries carry the limit chip's colours): a [limitWarn] icon on
     *  [limitChipFill] inside a 1 dp [limitChipEdge] (a `chipSelectedRing` [limitWarn] ring while the
     *  hidden view shows), entry text in [limitChipText], counts in [limitChipCount]. The
     *  hidden view's line sits [limitLineTop] under the
     *  circles: a [limitIconSize] [limitWarn] icon [limitLineIconGap] before the text. A hidden row's
     *  side column ends on its bitrate in [limitWarn] after a [limitRowIconSize] icon, [limitRowIconGap]
     *  apart. */
    val limitIconSize: Dp,
    val limitChipText: Color,
    val limitChipFill: Color,
    val limitChipEdge: Color,
    val limitChipCount: Color,
    val limitWarn: Color,
    val limitLineTop: Dp,
    val limitLineIconGap: Dp,
    val limitRowIconSize: Dp,
    val limitRowIconGap: Dp,
    /** The row's circles (Spec 65 P1, sandbox `spec-65-stream-filters` v5 stages 1, 3, 5, 10): refresh,
     *  sources, filters and, while streams are hidden, the amber hidden circle, each `refreshSize`.
     *  The filters circle fills [circleOnFill] while values are on. A [circleBadgeSize] high count
     *  badge sits [circleBadgeOffset] outside the circle's top end corner (ambient accent on the
     *  filters circle, `limitWarn` on the hidden one, [circleBadgeError] for the failed mark on the
     *  sources circle); while addons are asked a spinner ring runs [circleRingOutset] outside the
     *  sources circle. The eyebrow names the hidden view in `limitWarn`. */
    val circleOnFill: Color,
    val circleBadgeSize: Dp,
    val circleBadgeOffset: Dp,
    val circleBadgePadding: Dp,
    val circleBadgeError: Color,
    val circleRingOutset: Dp,
    /** The sheets (stages 4, 6, 7) cover the panel: [sheetFill], [sheetRadius], padded
     *  [sheetPaddingHorizontal] / [sheetPaddingTop] / [sheetPaddingBottom], children [sheetGap] apart;
     *  the Stream screen dims the rest in [sheetDim]. Sources and hidden rows: [sheetRowHeight] high
     *  [sheetRowRadius] cards in `rowFill` padded [sheetRowPadding], [sheetRowIconSize] icons, parts
     *  [sheetRowInnerGap] apart; the shown view has a `chipSelectedRing` white ring; focused = white
     *  fill, black text. Filter values: [facetChipHeight] pills padded [facetChipPadding],
     *  [facetChipGap] apart under a heading [facetGroupGap] above; on = [circleOnFill] behind a
     *  [facetCheckSize] check; a value that would list nothing draws at [facetZeroAlpha]. A saved
     *  filter is such a pill without a count: on = the ambient accent at [savedOnAlpha]; pinned = a
     *  `pinMarkSize` pin in the accent after its name (a pinned addon's row: `sheetRowIconSize`).
     *  The foot's buttons are [sheetButtonHeight] glass pills padded [sheetButtonPadding], the phone
     *  button a glass circle of that height at the row's end; the sources sheet's foot is the pin
     *  hint instead. */
    val sheetFill: Color,
    val sheetDim: Color,
    val sheetRadius: Dp,
    val sheetPaddingHorizontal: Dp,
    val sheetPaddingTop: Dp,
    val sheetPaddingBottom: Dp,
    val sheetGap: Dp,
    val sheetRowHeight: Dp,
    val sheetRowRadius: Dp,
    val sheetRowPadding: Dp,
    val sheetRowIconSize: Dp,
    val sheetRowInnerGap: Dp,
    val facetChipHeight: Dp,
    val facetChipPadding: Dp,
    val facetChipGap: Dp,
    val facetGroupGap: Dp,
    val facetCheckSize: Dp,
    val facetZeroAlpha: Float,
    val savedOnAlpha: Float,
    val sheetButtonHeight: Dp,
    val sheetButtonPadding: Dp,
    /** The saved-filters phone dialog (stage 8, `.qrd`): a [phoneCardWidth] card in [phoneCardFill]
     *  with a [phoneCardEdge] hairline and `sheetRadius` corners, centred over [phoneVeil]; its QR
     *  and text are [phoneCardPadding] in and as far apart. */
    val phoneVeil: Color,
    val phoneCardWidth: Dp,
    val phoneCardFill: Color,
    val phoneCardEdge: Color,
    val phoneCardPadding: Dp,
    /** The row menu (hold OK on an episode's row, stages 12, 14, `.pk`): a [rowMenuWidth] panel in
     *  [rowMenuFill] with a [rowMenuEdge] hairline and [rowMenuRadius] corners, padded
     *  [rowMenuPadding], [rowMenuOffset] under its row (above it when it would end less than
     *  [rowMenuScreenMargin] from the screen bottom) and [rowMenuInset] in from the row's start,
     *  over [rowMenuVeil]. Items [rowMenuItemGap] apart: [rowMenuItemRadius] corners, padded
     *  [rowMenuItemPaddingHorizontal] / [rowMenuItemPaddingVertical], a [rowMenuIconSize] icon in
     *  the ambient accent [rowMenuIconGap] before the text; focused = white fill, black content. */
    val rowMenuVeil: Color,
    val rowMenuWidth: Dp,
    val rowMenuFill: Color,
    val rowMenuEdge: Color,
    val rowMenuRadius: Dp,
    val rowMenuPadding: Dp,
    val rowMenuOffset: Dp,
    val rowMenuInset: Dp,
    val rowMenuScreenMargin: Dp,
    val rowMenuItemGap: Dp,
    val rowMenuItemRadius: Dp,
    val rowMenuItemPaddingHorizontal: Dp,
    val rowMenuItemPaddingVertical: Dp,
    val rowMenuIconSize: Dp,
    val rowMenuIconGap: Dp,
    /** Loading: static skeleton rows in [skeletonRowFill] (`rowRadius`, padded
     *  [skeletonRowPaddingVertical] / `rowPaddingHorizontal`), `skeletonFill` bars [skeletonLineGap]
     *  apart. Empty / error: a block padded [messagePaddingVertical] / [messagePaddingHorizontal],
     *  lines [messageGap] apart, body in [messageBody], Retry [messageActionTop] further down. */
    val skeletonRowFill: Color,
    val skeletonRowPaddingVertical: Dp,
    val skeletonLineGap: Dp,
    val messagePaddingVertical: Dp,
    val messagePaddingHorizontal: Dp,
    val messageGap: Dp,
    val messageBody: Color,
    val messageActionTop: Dp,
    /** Dialogs (stage 9; settings reuse the P2P consent): a compact panel centred over the
     *  `search.pickerDim` veil, padded `panelPaddingTop` / `panelPaddingHorizontal` (bottom too),
     *  [consentWidth] (P2P) wide; a confirm (Spec 28 stage 7,
     *  `MarqueeConfirmPanel`) is [confirmWidth] wide over the [confirmDim] veil. Body text in [dialogBody] and
     *  actions inset [dialogTextInset] (matches PanelEyebrow's 10 dp start inset, PanelComponents.kt); actions [dialogActionGap] apart,
     *  [dialogActionTop] under the body, [dialogActionBottom] above the panel padding. */
    val consentWidth: Dp,
    val confirmWidth: Dp,
    val confirmDim: Color,
    val dialogBody: Color,
    val dialogTextInset: Dp,
    val dialogActionGap: Dp,
    val dialogActionTop: Dp,
    val dialogActionBottom: Dp,
    /** Addon certificate panel (Spec 68 X7, sandbox `s068-x7-tls-confirm` v1 `.cp` / `.x7fp`):
     *  [consentWidth] wide over [certDim]; the host line [certHostBottom] above the body; the
     *  fingerprint block [certFingerprintTop] under the body, inset `dialogTextInset`, padded
     *  [certFingerprintPaddingVertical] / [certFingerprintPaddingHorizontal], [certFingerprintRadius],
     *  [certFingerprintFill]; caption in [certCaption], fingerprint in [certFingerprint], a replaced
     *  one in [certOldFingerprint]; old and new columns [certColumnGap] apart. */
    val certDim: Color,
    val certHostBottom: Dp,
    val certFingerprintTop: Dp,
    val certFingerprintPaddingVertical: Dp,
    val certFingerprintPaddingHorizontal: Dp,
    val certFingerprintRadius: Dp,
    val certFingerprintFill: Color,
    val certCaption: Color,
    val certFingerprint: Color,
    val certOldFingerprint: Color,
    val certColumnGap: Dp
)

/** Library (approved sandbox `u7-library-marquee` v3), dp on the 960x540 canvas. */
@Immutable
data class NuvioMarqueeLibraryTokens(
    /** Tab row (stage 1, `.l7bar`): [tabRowTop] under the top bar's `contentTop`, one
     *  `search.filterPillHeight` line (the Sort & filter pill shares it); tabs [tabGap] apart. */
    val tabRowTop: Dp,
    val tabGap: Dp,
    /** History cards (stage 7, `.hcard b`): the caption (title, then when) this far under the card. */
    val cardCaptionGap: Dp
)

/** Search + Discover (approved sandbox `u4-search-discover-marquee` v2), dp on the 960x540 canvas. */
@Immutable
data class NuvioMarqueeSearchTokens(
    /** Discover filter pill: a [filterPillHeight] glass pill, padded [filterPillPaddingStart] /
     *  [filterPillPaddingEnd] (the chevron side), label, value and [filterPillIconSize] chevron
     *  [filterPillInnerGap] apart; pills [filterPillGap] apart. */
    val filterPillHeight: Dp,
    val filterPillPaddingStart: Dp,
    val filterPillPaddingEnd: Dp,
    val filterPillInnerGap: Dp,
    val filterPillIconSize: Dp,
    val filterPillGap: Dp,
    /** Filter picker: the compact panel, [pickerWidth] wide, [pickerOffset] under its pill, over a
     *  [pickerDim] veil; it scrolls inside, ending at least [pickerBottomMargin] above the screen bottom. */
    val pickerWidth: Dp,
    val pickerOffset: Dp,
    val pickerDim: Color,
    val pickerBottomMargin: Dp,
    /** Grids (Discover, See All, folder Tabs, Library): cards packed into lines at most
     *  [gridLineWidth] wide (the 864 dp safe width), [gridGap] apart both ways: 7 posters, 4
     *  [landscapeGridCardWidth]x[landscapeGridCardHeight] cards (card-shapes-marquee st4), 5
     *  [squareGridCardSize] squares, or mixed shapes at the poster height (Spec 41 D2/D3).
     *  7 x 112 + 6 x 14 overflows the line, so Home's `cardGap` cannot apply. */
    val gridGap: Dp,
    val gridLineWidth: Dp,
    val landscapeGridCardWidth: Dp,
    val landscapeGridCardHeight: Dp,
    val squareGridCardSize: Dp,
    /** Search field row: controls [fieldRowGap] apart; the field is a [fieldHeight] pill in
     *  [fieldFill] with a [fieldEdge] hairline (focused: [fieldFocusedFill] + `ring`), padded
     *  [fieldPaddingStart] / [fieldPaddingEnd], search icon [fieldIconGap] before the text, an
     *  inline [clearSize] clear circle at the end. [iconSize]: the field, clear and chip icons. */
    val fieldRowGap: Dp,
    val fieldHeight: Dp,
    val fieldFill: Color,
    val fieldFocusedFill: Color,
    val fieldEdge: Color,
    val fieldPaddingStart: Dp,
    val fieldPaddingEnd: Dp,
    val fieldIconGap: Dp,
    val clearSize: Dp,
    val iconSize: Dp,
    /** Recent searches (Spec 38 stages 9-10, `.l38rp`): a [recentsPanelWidth] panel
     *  [recentsPanelOffset] under the field's start, [recentsPanelFill] at [recentsPanelRadius],
     *  padded [recentsPanelPadding], a [recentsPanelShadow] elevation; rows [recentsRowHeight] tall
     *  at [recentsRowRadius], padded [recentsRowPadding], the `iconSize` history icon
     *  [recentsRowGap] before the query. */
    val recentsPanelWidth: Dp,
    val recentsPanelOffset: Dp,
    val recentsPanelFill: Color,
    val recentsPanelRadius: Dp,
    val recentsPanelPadding: Dp,
    val recentsPanelShadow: Dp,
    val recentsRowHeight: Dp,
    val recentsRowRadius: Dp,
    val recentsRowPadding: Dp,
    val recentsRowGap: Dp,
    /** Discover inside Search (stage 3, Spec 38 stage 8): the DISCOVER label [discoverLabelTop]
     *  under the field row, the filter pills [discoverLabelGap] under it. */
    val discoverLabelTop: Dp,
    val discoverLabelGap: Dp,
    /** Compact hero (Spec 38 Q4 B, `.l38hc`), past a band's first row: the hero block
     *  [compactHeroTop] from the screen top, [compactHeroWidth] wide (plot [compactHeroDescriptionWidth]),
     *  children [compactHeroGap] apart; the focused row starts at [compactBandTop]. The backdrop takes
     *  one vertical scrim, [compactScrimStops] (`.scrimC`), instead of the left/bottom/top scrims. */
    val compactHeroTop: Dp,
    val compactHeroWidth: Dp,
    val compactHeroDescriptionWidth: Dp,
    val compactHeroGap: Dp,
    val compactBandTop: Dp,
    val compactScrimStops: List<Pair<Float, Float>>
)

/**
 * Player OSD (Marquee spec §Screens "Player OSD" + §Components OSD icon button, play/pause, small
 * glass pill; approved sandbox `u3-player-osd-marquee` v5), dp on the 960x540 canvas. The bottom
 * block is bottom-anchored: transport row y 462..526, times y 434, seek track y 420, tray y 368.
 */
@Immutable
data class NuvioMarqueeOsdTokens(
    /** `media.osdScrimTop`: [scrimTopStart] fading to transparent over [scrimTopHeight]. */
    val scrimTopStart: Color,
    val scrimTopHeight: Dp,
    /** `media.osdScrimBottom`: (fraction of the screen height, black alpha) stops. */
    val scrimBottomStops: List<Pair<Float, Float>>,
    /** Top band: title and clock line boxes start at [topInset]; badges at [badgesTop], [badgeGap]
     *  apart; a long title ellipsizes [titleEndGap] before the badges (or the clock). */
    val topInset: Dp,
    val badgesTop: Dp,
    val badgeGap: Dp,
    val titleEndGap: Dp,
    /** Transport row: [iconButtonSize] circles with [iconSize] icons, [rowGap] apart; play/pause
     *  in a [playPauseSize] slot: unfocused a bare [playPauseIconSize] icon, focused a white circle
     *  with a [playPauseFocusedIconSize] icon (Spec 45 Q5). The row's bottom sits [bottomInset]
     *  above the screen bottom. */
    val iconButtonSize: Dp,
    val iconSize: Dp,
    val playPauseSize: Dp,
    val playPauseIconSize: Dp,
    val playPauseFocusedIconSize: Dp,
    val rowGap: Dp,
    val bottomInset: Dp,
    /** Accent dot under Subtitles while a subtitle is on: [dotSize], [dotBottom] above the circle's edge. */
    val dotSize: Dp,
    val dotBottom: Dp,
    /** More while the tray is open (focus sits in the tray). */
    val moreOpenFill: Color,
    /** Small glass pill (Next episode, still-watching): [pillHeight], [pillPadding] each side,
     *  [pillIconSize] icon [pillIconGap] from the label; pills in a row (still-watching) [pillRowGap] apart. */
    val pillHeight: Dp,
    val pillPadding: Dp,
    val pillIconSize: Dp,
    val pillIconGap: Dp,
    val pillRowGap: Dp,
    /** More tray (F3 stages 3-4): icon-only `pillHeight` circles, [trayIconSize] icons, [trayIconGap]
     *  apart; the focused pill expands to icon + label + value, padded [trayPillPaddingStart] /
     *  `pillPadding`. A collapsed pill whose value is not the default carries the accent dot. */
    val trayIconSize: Dp,
    val trayIconGap: Dp,
    val trayPillPaddingStart: Dp,
    /** Seek bar: [seekTouchHeight] focus box around a [seekHeight] track ([seekFocusedHeight]
     *  focused); track [seekTrack], buffered [seekBuffered], played white with an ambient-accent
     *  glow at [seekGlowAlpha]. Gaps: tray to bar box, bar box to times, times to the row. */
    val seekTouchHeight: Dp,
    val seekHeight: Dp,
    val seekFocusedHeight: Dp,
    val seekTrack: Color,
    val seekBuffered: Color,
    val seekGlowAlpha: Float,
    val trayToBar: Dp,
    val barToTimes: Dp,
    val timesToRow: Dp,
    /** Focused bar: a [seekThumbSize] white playhead in a [seekThumbRing] black ring with an
     *  ambient-accent glow at [seekThumbGlowAlpha]; while a preview is pending, [seekGhost] spans
     *  from the playing position to the preview. */
    val seekThumbSize: Dp,
    val seekThumbRing: Dp,
    val seekThumbGlowAlpha: Float,
    val seekGhost: Color,
    /** Preview bubble over the playhead: a [previewFrameHeight] thumbnail frame (radius
     *  [previewFrameRadius], [previewFrameRing] hairline) [previewGap] above the time pill ([previewTimeFill], [previewTimePadding]
     *  each side). Its bottom sits [previewBottomControls] above the screen bottom with the OSD (2 dp
     *  over the focused bar), [previewBottomHidden] over the mini seek (6 dp over its label). */
    val previewFrameHeight: Dp,
    val previewFrameRadius: Dp,
    val previewFrameRing: Color,
    val previewGap: Dp,
    val previewTimeFill: Color,
    val previewTimePadding: Dp,
    val previewBottomControls: Dp,
    val previewBottomHidden: Dp,
    /** Chapters (F3 stages 1-2): a [chapterGap] gap cut into the bar at each chapter start (OSD and
     *  mini seek). With the OSD the bubble names the previewed chapter in a pill over the time pill
     *  (`previewTimeFill`, radius [chapterPillRadius], padded [chapterPillPaddingH] / [chapterPillPaddingV],
     *  at most [chapterPillMaxWidth]); a titled chapter's number sits [chapterNumberGap] before it. */
    val chapterGap: Dp,
    val chapterPillRadius: Dp,
    val chapterPillPaddingH: Dp,
    val chapterPillPaddingV: Dp,
    val chapterPillMaxWidth: Dp,
    val chapterNumberGap: Dp,
    /** Hidden-controls mini seek: a [miniScrimHeight] bottom scrim ((fraction of its height, black
     *  alpha) [miniScrimStops]), the [seekHeight] bar's bottom edge [miniBarBottom] above the screen
     *  bottom, "current / duration" [miniLabelGap] above the bar at its end. */
    val miniScrimHeight: Dp,
    val miniScrimStops: List<Pair<Float, Float>>,
    val miniBarBottom: Dp,
    val miniLabelGap: Dp,
    /** Skip pill bottom inset with controls (top y 372 for the 36 dp pill) and hidden (y 450);
     *  post-play card bottom inset with controls (top y 300 for the 96 dp card). */
    val skipBottomControls: Dp,
    val skipBottomHidden: Dp,
    val postPlayBottomControls: Dp,
    /** Post-play card (next episode / still watching): [postPlayCardWidth] x [postPlayCardHeight],
     *  `cardRadius`, [postPlayCardFill] inside a [postPlayCardHairline] 1 dp edge; focused = `ring` +
     *  `glowCard` in the ambient accent. The still fills [postPlayStillWidth] x the card height at
     *  the start; text sits [postPlayTextPaddingH] / [postPlayTextPaddingV] in, lines
     *  [postPlayLineGap] apart; still-watching's small glass pills are [postPlayPillHeight] tall. */
    val postPlayCardWidth: Dp,
    val postPlayCardHeight: Dp,
    val postPlayCardFill: Color,
    val postPlayCardHairline: Color,
    val postPlayStillWidth: Dp,
    val postPlayTextPaddingH: Dp,
    val postPlayTextPaddingV: Dp,
    val postPlayLineGap: Dp,
    val postPlayPillHeight: Dp,
    /** Focused next-episode card: grows by [postPlayOverviewHeight] for the 2-line overview,
     *  set [postPlayOverviewTop] below the still row (data-additions stage 14). */
    val postPlayOverviewHeight: Dp,
    val postPlayOverviewTop: Dp,
    /** While the OSD shows, the stats panel ends this far above the screen bottom (above the tray). */
    val statsBottomInset: Dp,
    /** Stats panel (data st10): top start, [statsWidth] wide, [statsInset] from the start, top and
     *  bottom; two columns [statsColumnGap] apart; values [statsGood] / [statsWarn] / [statsBad] by health. */
    val statsWidth: Dp,
    val statsInset: Dp,
    val statsColumnGap: Dp,
    val statsGood: Color,
    val statsWarn: Color,
    val statsBad: Color,
    /** Degraded toast (data st8): centred [toastTop] below the screen top; [toastFill] pill, radius
     *  [toastRadius], [toastRingWidth] [toastRing]; padding [toastPaddingStart] / [toastPaddingEnd] /
     *  [toastPaddingVertical]; warning icon [toastIconSize], [toastIconGap] from the text. */
    val toastTop: Dp,
    val toastFill: Color,
    val toastRing: Color,
    val toastRingWidth: Dp,
    val toastRadius: Dp,
    val toastPaddingStart: Dp,
    val toastPaddingEnd: Dp,
    val toastPaddingVertical: Dp,
    val toastIconSize: Dp,
    val toastIconGap: Dp
)

/** Glass icon button (Marquee spec §Components): a [size] circle in `glassFill`; focus = white. */
@Immutable
data class NuvioMarqueeGlassButtonTokens(
    val size: Dp,
    val iconSize: Dp,
    /** The text variant (MarqueeGlassTextButton): `size` tall, padded this much each side; an
     *  optional leading icon is [textIconSize], [textIconGap] before the label. */
    val textPaddingHorizontal: Dp,
    val textIconSize: Dp,
    val textIconGap: Dp
)

/** Tech badge (Marquee spec §Components): outlined text; the release-group variant is filled; the
 *  degraded variant (data st8) is [warnText] on [warnFill] inside a [warnOutline] outline. */
@Immutable
data class NuvioMarqueeTechBadgeTokens(
    val outline: Color,
    val outlineWidth: Dp,
    val groupFill: Color,
    val horizontalPadding: Dp,
    val warnText: Color,
    val warnOutline: Color,
    val warnFill: Color
)

/** Detail first frame (Marquee spec §Screens "Detail as decided at the U2 slice"), dp on 960x540. */
@Immutable
data class NuvioMarqueeDetailTokens(
    val infoTop: Dp,
    val infoWidth: Dp,
    val infoGap: Dp,
    val logoMaxHeight: Dp,
    val logoMaxWidth: Dp,
    val descriptionWidth: Dp,
    val descriptionMaxLines: Int,
    /** Focused (truncated) description: a `glassFill` plate this far outside the text box. */
    val descriptionFocusInsetHorizontal: Dp,
    val descriptionFocusInsetVertical: Dp,
    val descriptionFocusRadius: Dp,
    /** Actions row: extra top margin on top of [infoGap], and the gap between buttons. */
    val actionsExtraTop: Dp,
    val actionGap: Dp,
    /** Source line: reserved height (actions never jump), badge gap, icon-to-label and
     *  label-to-badges gaps, ready dot + its soft glow, searching spinner. */
    val sourceLineHeight: Dp,
    val sourceBadgeGap: Dp,
    val sourceIconGap: Dp,
    val sourceLabelGap: Dp,
    val readyDotSize: Dp,
    val readyDotGlow: Dp,
    /** About sheet (data-additions stage 4): veil; two columns ([aboutLeftWidth] + the rest,
     *  [aboutColumnGap] apart) from [aboutTop]; left blocks [aboutBlockGap] apart; right fact rows
     *  ([aboutLabelWidth] label, [aboutLabelGap], [aboutRowPadding] above and below, an
     *  [aboutRule] hairline on top) under section heads ([aboutSectionTop] / [aboutSectionBottom]);
     *  chips [aboutChipHeight] tall, [aboutChipPadding] inside, [aboutChipGap] apart; the
     *  collection poster [aboutPosterWidth] x [aboutPosterHeight], radius [aboutPosterRadius]. */
    val aboutVeil: Color,
    val aboutTop: Dp,
    val aboutLeftWidth: Dp,
    val aboutColumnGap: Dp,
    val aboutBlockGap: Dp,
    val aboutLabelWidth: Dp,
    val aboutLabelGap: Dp,
    val aboutRowPadding: Dp,
    val aboutRule: Color,
    val aboutSectionTop: Dp,
    val aboutSectionBottom: Dp,
    val aboutChipHeight: Dp,
    val aboutChipPadding: Dp,
    val aboutChipGap: Dp,
    val aboutPosterWidth: Dp,
    val aboutPosterHeight: Dp,
    val aboutPosterRadius: Dp,
    /** Bottom-left key hint ("BACK Close", "BACK Stop trailer"). */
    val hintBottom: Dp,
    val hintGap: Dp,
    val hintKeyHeight: Dp,
    val hintKeyPadding: Dp,
    /** Trailer preview: title block bottom inset, logo height, corner scrim (angle in CSS
     *  degrees from "to top", (fraction, black alpha) stops from the bottom-left corner). */
    val trailerTitleBottom: Dp,
    val trailerLogoHeight: Dp,
    val trailerScrimAngle: Float,
    val trailerScrimStops: List<Pair<Float, Float>>,
    /** Episodes band (season chips over episode cards). First frame: chips at [bandFirstFrameTop].
     *  Focused (scrolled state): the cards' bottom edge [bandBottomInset] above the screen bottom
     *  (chips y 336, cards y 380 on 540; an inset, as Home's band); [bandAfterGap] to the next band. */
    val bandFirstFrameTop: Dp,
    val bandBottomInset: Dp,
    val bandAfterGap: Dp,
    /** Season chips: [chipHeight] pills, [chipPadding] each side, [chipGap] apart, [chipsToCards] above the cards. */
    val chipHeight: Dp,
    val chipPadding: Dp,
    val chipGap: Dp,
    val chipsToCards: Dp,
    /** Landscape card, detail size (spec §Components): episodes, related, collection and trailer
     *  cards; gap = `home.cardGap`. */
    val cardWidth: Dp,
    val cardHeight: Dp,
    /** UNAVAILABLE episode: card content dimmed to [unavailableAlpha], chip text [unavailableText]. */
    val unavailableAlpha: Float,
    val unavailableText: Color,
    /** Scrolled state: the focused item's block ([blockWidth], [blockGap], 2-line description in
     *  [blockDescriptionWidth]) sits `home.heroAboveRow` above the band; context line at [contextTop]. */
    val blockWidth: Dp,
    val blockGap: Dp,
    val blockDescriptionWidth: Dp,
    val contextTop: Dp,
    /** Section band (tab strip over one section's content): tabs in a [sectionTabsHeight] line
     *  box (the `tab` style), [sectionTabGap] apart, [sectionTabsToContent] above the content,
     *  which always takes [sectionContentHeight] (tabs never move between sections). Anchored like
     *  the episodes band: the content box ends `bandBottomInset` above the screen bottom. */
    val sectionTabsHeight: Dp,
    val sectionTabGap: Dp,
    val sectionTabsToContent: Dp,
    val sectionContentHeight: Dp,
    /** Cast: [castSize] circles; each item [castItemWidth] wide, [castGap] apart; name and role
     *  [castTextGap] under the circle, centred, up to [castTextMaxWidth] (into the gaps). */
    val castSize: Dp,
    val castItemWidth: Dp,
    val castGap: Dp,
    val castTextGap: Dp,
    val castTextMaxWidth: Dp,
    /** Ratings grid: [ratingCellWidth]x[ratingCellHeight] cells, radius [ratingCellRadius],
     *  [ratingGap] apart, season labels in a [ratingLabelWidth] column; no-rating cells [ratingEmptyFill]. */
    val ratingCellWidth: Dp,
    val ratingCellHeight: Dp,
    val ratingCellRadius: Dp,
    val ratingGap: Dp,
    val ratingLabelWidth: Dp,
    val ratingEmptyFill: Color,
    /** Studio/network logos on [logoTileFill] tiles, [logoTileWidth]x[logoTileHeight], logo inset [logoTilePadding]. */
    val logoTileWidth: Dp,
    val logoTileHeight: Dp,
    val logoTileFill: Color,
    val logoTilePadding: Dp
)

/** Person page first frame (approved sandbox `u9-cast-marquee` v3 stages 1, 6, 10), dp on 960x540.
 *  The info column takes `detail.infoTop` and `detail.infoGap`. */
@Immutable
data class NuvioMarqueePersonTokens(
    /** Photo field: the pre-blurred photo under a [fieldVeil] black veil plus Home's scrims. */
    val fieldVeil: Color,
    val infoWidth: Dp,
    /** Biography: [bioWidth] wide, [bioMaxLines] lines (focusable when cut off). */
    val bioWidth: Dp,
    val bioMaxLines: Int,
    /** Portrait at the end edge, `detail.infoTop` down: [portraitWidth]x[portraitHeight], radius
     *  [portraitRadius], a [portraitOutlineWidth] [portraitOutline] hairline, [portraitShadow] elevation.
     *  No photo: initials ([initialsSize], [initialsText]) on a 135° [initialsFillStart] to
     *  [initialsFillEnd] tile. */
    val portraitWidth: Dp,
    val portraitHeight: Dp,
    val portraitRadius: Dp,
    val portraitOutline: Color,
    val portraitOutlineWidth: Dp,
    val portraitShadow: Dp,
    val initialsSize: TextUnit,
    val initialsText: Color,
    val initialsFillStart: Color,
    val initialsFillEnd: Color,
    /** Department band peeking under the first frame: tabs line at [bandTop], cards [tabsToCards] below. */
    val bandTop: Dp,
    val tabsToCards: Dp,
    /** Read more sheet (stage 2; the studio page's too, via ReadMoreSheet): one [sheetWidth] column
     *  centred [sheetTop] down, blocks [sheetGap] apart, the facts [sheetFactsTop] further, their
     *  labels [sheetLabelWidth] wide. */
    val sheetTop: Dp,
    val sheetWidth: Dp,
    val sheetGap: Dp,
    val sheetFactsTop: Dp,
    val sheetLabelWidth: Dp
)

/** Studio / network page header frame (approved sandbox `u9-cast-marquee` v3 stage 7), dp on 960x540. */
@Immutable
data class NuvioMarqueeEntityTokens(
    /** The first title's backdrop under a [veil] black veil plus Home's scrims. */
    val veil: Color,
    /** Info column at [infoTop], [infoWidth] wide (blocks `detail.infoGap` apart); description [descriptionWidth]. */
    val infoTop: Dp,
    val infoWidth: Dp,
    val descriptionWidth: Dp,
    /** Logo at the end edge, [logoTop] down: a [logoWidth]x[logoHeight] `detail.logoTileFill` tile,
     *  radius [logoRadius], [logoShadow] elevation (none for a white-tinted logo). */
    val logoTop: Dp,
    val logoWidth: Dp,
    val logoHeight: Dp,
    val logoRadius: Dp,
    val logoShadow: Dp,
    /** The first row's title peeks at [peekTop] (cards `home.rowTitleGap` under it). */
    val peekTop: Dp
)

/** Primary button (Marquee spec §Components): white pill, black content, ring outside a gap. */
@Immutable
data class NuvioMarqueePrimaryButtonTokens(
    val height: Dp,
    val horizontalPadding: Dp,
    val iconSize: Dp,
    val iconGap: Dp,
    /** `focus.buttonScale` (centre pivot). */
    val focusScale: Float,
    /** Ring drawn this far outside the pill (`marquee.ring` over a black gap); the detail logo
     *  tiles and ratings cells use the same gap. */
    val ringGap: Dp,
    /** `focus.glowButton`: shadow elevation standing in for y12 blur30, colour at [glowAlpha]. */
    val glowElevation: Dp,
    val glowAlpha: Float,
    /** The compact variant (u8 stage 6 header, `.l8top .btn.pri`): [compactHeight] tall, padded
     *  [compactPaddingHorizontal], no focus scale or glow (ring only). */
    val compactHeight: Dp,
    val compactPaddingHorizontal: Dp
)

/** Home geometry (Marquee spec §Screens "Home", §Components cards), dp on the 960x540 canvas. */
@Immutable
data class NuvioMarqueeHomeTokens(
    val landscapeCardWidth: Dp,
    val landscapeCardHeight: Dp,
    val portraitCardWidth: Dp,
    val portraitCardHeight: Dp,
    /** Cards keep their own shape at [portraitCardHeight] in a row that is not all landscape
     *  (Spec 41 D2/D3, u41-addon-card-shapes v2): square 168x168, landscape 299x168. */
    val squareCardWidth: Dp,
    val wideCardWidth: Dp,
    val cardGap: Dp,
    /** Row title line box to the cards. */
    val rowTitleGap: Dp,
    /** Space between one row's cards and the next row's title. */
    val rowGap: Dp,
    /** Focused row's card bottom edge, from the screen bottom (y 494 on 540: landscape title
     *  lands ~334, portrait ~294). An inset, so a UI-scaled screen keeps the band on screen. */
    val focusedCardBottomInset: Dp,
    val heroWidth: Dp,
    val heroGap: Dp,
    val heroDescriptionWidth: Dp,
    /** Hero block bottom sits this far above the focused row title. */
    val heroAboveRow: Dp,
    /** Bottom-anchored hero grows upward, never above this y. */
    val heroMinTop: Dp,
    val heroLogoMaxHeight: Dp,
    val heroLogoMaxWidth: Dp,
    val heroProgressWidth: Dp,
    /** Breadcrumb y (MarqueeBreadcrumb on pushed screens, in place of the top bar). */
    val breadcrumbTop: Dp
)

/** Empty / error block (Home states; Detail error): top-left at (tvSafeHorizontal, [top]). */
@Immutable
data class NuvioMarqueeMessageTokens(
    val top: Dp,
    val width: Dp,
    val bodyWidth: Dp,
    val gap: Dp,
    /** Extra space above the action button, on top of [gap]. */
    val actionTop: Dp,
    /** Between the action button and the optional secondary one (`.actions` gap). */
    val actionGap: Dp,
    /** Backdrop variant (the player's playback error): the image under [backdropVeil], the block
     *  [bottomAnchor] above the bottom. */
    val backdropVeil: Color,
    val bottomAnchor: Dp
)

/** Top bar geometry (Marquee spec §Components "Top bar"), dp on the 960x540 canvas. */
@Immutable
data class NuvioTopBarTokens(
    val top: Dp,
    val height: Dp,
    val itemGap: Dp,
    val trailingGap: Dp,
    val avatarSize: Dp,
    /** Selected-tab underline: [underlineHeight] tall, [underlineOffset] below the line box. */
    val underlineOffset: Dp,
    val underlineHeight: Dp,
    /** Focus pill padding, drawn outside the text box so tabs never shift (with the underline:
     *  every MarqueeTab, the detail section strip too). */
    val pillPaddingHorizontal: Dp,
    val pillPaddingVertical: Dp,
    /** Where root-screen content starts under the bar (Q-U1: interim screens at y 72). */
    val contentTop: Dp,
    /** A tab's small count, this far after its label (Library's type tabs, u7 stage 1). */
    val tabCountGap: Dp,
    /** A blocked tab's lock (u11 stage 25): [tabLockSize] square, [tabLockGap] after the label. */
    val tabLockSize: Dp,
    val tabLockGap: Dp,
    /** The Update pill before the clock (Spec 52 T2 `.s52pill`): [updatePillHeight] tall, padded
     *  [updatePillPaddingStart] / [updatePillPaddingEnd], a [updatePillIconSize] icon [updatePillIconGap]
     *  before the label; at rest [updatePillText] on [updatePillFill] inside a 1 dp [updatePillEdge],
     *  focused white with black content. Spec 80 D5 (`.s80pulse`): the first time a release shows,
     *  [updatePulseRings] rings of [updatePulseColor] grow from the pill's edge to [updatePulseSpread]
     *  over [updatePulseRingMs] (ease-out, alpha [updatePulseAlpha] to 0, the edge brightening to
     *  [updatePulseEdgeAlpha]), [updatePulseGapMs] apart, then rest. */
    val updatePillHeight: Dp,
    val updatePillPaddingStart: Dp,
    val updatePillPaddingEnd: Dp,
    val updatePillIconSize: Dp,
    val updatePillIconGap: Dp,
    val updatePillFill: Color,
    val updatePillEdge: Color,
    val updatePillText: Color,
    val updatePulseColor: Color,
    val updatePulseSpread: Dp,
    val updatePulseAlpha: Float,
    val updatePulseEdgeAlpha: Float,
    val updatePulseRingMs: Int,
    val updatePulseGapMs: Int,
    val updatePulseRings: Int
)

object NuvioMarquee {
    val tokens = NuvioMarqueeTokens(
        ring = NuvioPrimitives.white,
        ringWidth = 2.dp,
        onImage2 = NuvioPrimitives.white.copy(alpha = 0.72f),
        onImage3 = NuvioPrimitives.white.copy(alpha = 0.50f),
        onImageBody = NuvioPrimitives.white.copy(alpha = 0.85f),
        scrimTopStart = NuvioPrimitives.black.copy(alpha = 0.60f),
        scrimTopHeight = 96.dp,
        scrimLeftStops = listOf(0f to 0.92f, 0.24f to 0.78f, 0.50f to 0.30f, 0.66f to 0f),
        scrimBottomStops = listOf(0.40f to 0f, 0.60f to 0.60f, 0.78f to 0.95f, 1f to 1f),
        scrimLeftDetailStops = listOf(0f to 0.94f, 0.30f to 0.82f, 0.58f to 0.35f, 0.74f to 0f),
        scrimBottomDetailStops = listOf(0.52f to 0f, 0.74f to 0.70f, 0.96f to 1f),
        glassFill = NuvioPrimitives.white.copy(alpha = 0.14f),
        readyText = Color(0xFF7FD28B),
        cardScale = 1.06f,
        glowCardElevation = 18.dp,
        glowCardAlpha = 0.55f,
        cardRadius = 12.dp,
        progressHeight = 3.dp,
        metaGap = 10.dp,
        skeletonFill = NuvioPrimitives.white.copy(alpha = 0.06f),
        spinnerSize = 12.dp,
        spinnerStroke = 2.dp,
        topBar = NuvioTopBarTokens(
            top = 24.dp,
            height = 32.dp,
            itemGap = 28.dp,
            trailingGap = 14.dp,
            avatarSize = 30.dp,
            underlineOffset = 6.dp,
            underlineHeight = 2.dp,
            pillPaddingHorizontal = 14.dp,
            pillPaddingVertical = 6.dp,
            contentTop = 72.dp,
            tabCountGap = 5.dp,
            tabLockSize = 13.dp,
            tabLockGap = 6.dp,
            updatePillHeight = 30.dp,
            updatePillPaddingStart = 10.dp,
            updatePillPaddingEnd = 13.dp,
            updatePillIconSize = 16.dp,
            updatePillIconGap = 6.dp,
            updatePillFill = NuvioPrimitives.warning.copy(alpha = 0.16f),
            updatePillEdge = NuvioPrimitives.warning.copy(alpha = 0.5f),
            updatePillText = NuvioPrimitives.warningOnTint,
            updatePulseColor = NuvioPrimitives.warning,
            updatePulseSpread = 9.dp,
            updatePulseAlpha = 0.55f,
            updatePulseEdgeAlpha = 0.9f,
            updatePulseRingMs = 1200,
            updatePulseGapMs = 360,
            updatePulseRings = 3
        ),
        home = NuvioMarqueeHomeTokens(
            landscapeCardWidth = 228.dp,
            landscapeCardHeight = 128.dp,
            portraitCardWidth = 112.dp,
            portraitCardHeight = 168.dp,
            squareCardWidth = 168.dp,
            wideCardWidth = 299.dp,
            cardGap = 14.dp,
            rowTitleGap = 12.dp,
            rowGap = 22.dp,
            focusedCardBottomInset = 46.dp,
            heroWidth = 460.dp,
            heroGap = 10.dp,
            heroDescriptionWidth = 420.dp,
            heroAboveRow = 24.dp,
            heroMinTop = 80.dp,
            heroLogoMaxHeight = 100.dp,
            heroLogoMaxWidth = 300.dp,
            heroProgressWidth = 160.dp,
            breadcrumbTop = 30.dp
        ),
        detail = NuvioMarqueeDetailTokens(
            infoTop = 64.dp,
            infoWidth = 500.dp,
            infoGap = 12.dp,
            logoMaxHeight = 100.dp,
            logoMaxWidth = 360.dp,
            descriptionWidth = 460.dp,
            descriptionMaxLines = 3,
            descriptionFocusInsetHorizontal = 12.dp,
            descriptionFocusInsetVertical = 8.dp,
            descriptionFocusRadius = 8.dp,
            actionsExtraTop = 6.dp,
            actionGap = 12.dp,
            sourceLineHeight = 24.dp,
            sourceBadgeGap = 6.dp,
            sourceIconGap = 8.dp,
            sourceLabelGap = 12.dp,
            readyDotSize = 7.dp,
            readyDotGlow = 6.dp,
            aboutVeil = NuvioPrimitives.black.copy(alpha = 0.88f),
            aboutTop = 44.dp,
            aboutLeftWidth = 420.dp,
            aboutColumnGap = 44.dp,
            aboutBlockGap = 14.dp,
            aboutLabelWidth = 108.dp,
            aboutLabelGap = 12.dp,
            aboutRowPadding = 5.dp,
            aboutRule = NuvioPrimitives.white.copy(alpha = 0.07f),
            aboutSectionTop = 14.dp,
            aboutSectionBottom = 6.dp,
            aboutChipHeight = 26.dp,
            aboutChipPadding = 11.dp,
            aboutChipGap = 6.dp,
            aboutPosterWidth = 36.dp,
            aboutPosterHeight = 54.dp,
            aboutPosterRadius = 6.dp,
            hintBottom = 36.dp,
            hintGap = 8.dp,
            hintKeyHeight = 24.dp,
            hintKeyPadding = 9.dp,
            trailerTitleBottom = 84.dp,
            trailerLogoHeight = 60.dp,
            trailerScrimAngle = 20f,
            trailerScrimStops = listOf(0f to 0.75f, 0.32f to 0.35f, 0.55f to 0f),
            bandFirstFrameTop = 436.dp,
            bandBottomInset = 43.dp,
            bandAfterGap = 19.dp,
            chipHeight = 32.dp,
            chipPadding = 14.dp,
            chipGap = 10.dp,
            chipsToCards = 12.dp,
            cardWidth = 208.dp,
            cardHeight = 117.dp,
            unavailableAlpha = 0.45f,
            unavailableText = NuvioPrimitives.errorOnImage,
            blockWidth = 480.dp,
            blockGap = 8.dp,
            blockDescriptionWidth = 440.dp,
            contextTop = 34.dp,
            sectionTabsHeight = 20.dp,
            sectionTabGap = 18.dp,
            sectionTabsToContent = 18.dp,
            sectionContentHeight = 122.dp,
            castSize = 80.dp,
            castItemWidth = 88.dp,
            castGap = 26.dp,
            castTextGap = 8.dp,
            castTextMaxWidth = 112.dp,
            ratingCellWidth = 42.dp,
            ratingCellHeight = 30.dp,
            ratingCellRadius = 6.dp,
            ratingGap = 6.dp,
            ratingLabelWidth = 48.dp,
            ratingEmptyFill = NuvioPrimitives.white.copy(alpha = 0.08f),
            logoTileWidth = 168.dp,
            logoTileHeight = 76.dp,
            logoTileFill = NuvioPrimitives.white.copy(alpha = 0.92f),
            logoTilePadding = 14.dp
        ),
        person = NuvioMarqueePersonTokens(
            fieldVeil = NuvioPrimitives.black.copy(alpha = 0.45f),
            infoWidth = 560.dp,
            bioWidth = 500.dp,
            bioMaxLines = 4,
            portraitWidth = 200.dp,
            portraitHeight = 300.dp,
            portraitRadius = 16.dp,
            portraitOutline = NuvioPrimitives.white.copy(alpha = 0.16f),
            portraitOutlineWidth = 1.dp,
            portraitShadow = 24.dp,
            initialsSize = 64.sp,
            initialsText = NuvioPrimitives.white.copy(alpha = 0.80f),
            initialsFillStart = Color(0xFF3A4A5A),
            initialsFillEnd = Color(0xFF1C242C),
            bandTop = 436.dp,
            tabsToCards = 38.dp,
            sheetTop = 56.dp,
            sheetWidth = 640.dp,
            sheetGap = 12.dp,
            sheetFactsTop = 6.dp,
            sheetLabelWidth = 120.dp
        ),
        entity = NuvioMarqueeEntityTokens(
            veil = NuvioPrimitives.black.copy(alpha = 0.62f),
            infoTop = 76.dp,
            infoWidth = 540.dp,
            descriptionWidth = 500.dp,
            logoTop = 96.dp,
            logoWidth = 260.dp,
            logoHeight = 124.dp,
            logoRadius = 14.dp,
            logoShadow = 24.dp,
            peekTop = 436.dp
        ),
        message = NuvioMarqueeMessageTokens(
            top = 176.dp,
            width = 520.dp,
            bodyWidth = 440.dp,
            gap = 12.dp,
            actionTop = 10.dp,
            actionGap = 12.dp,
            backdropVeil = NuvioPrimitives.black.copy(alpha = 0.82f),
            bottomAnchor = 72.dp
        ),
        primaryButton = NuvioMarqueePrimaryButtonTokens(
            height = 48.dp,
            horizontalPadding = 24.dp,
            iconSize = 18.dp,
            iconGap = 8.dp,
            focusScale = 1.05f,
            ringGap = 2.dp,
            glowElevation = 16.dp,
            glowAlpha = 0.60f,
            compactHeight = 36.dp,
            compactPaddingHorizontal = 20.dp
        ),
        glassButton = NuvioMarqueeGlassButtonTokens(
            size = 44.dp,
            iconSize = 18.dp,
            textPaddingHorizontal = 20.dp,
            textIconSize = 16.dp,
            textIconGap = 8.dp
        ),
        techBadge = NuvioMarqueeTechBadgeTokens(
            outline = NuvioPrimitives.white.copy(alpha = 0.38f),
            outlineWidth = 1.dp,
            groupFill = NuvioPrimitives.white.copy(alpha = 0.08f),
            horizontalPadding = 5.dp,
            warnText = NuvioPrimitives.warning,
            warnOutline = NuvioPrimitives.warning.copy(alpha = 0.85f),
            warnFill = NuvioPrimitives.warning.copy(alpha = 0.10f)
        ),
        osd = NuvioMarqueeOsdTokens(
            scrimTopStart = NuvioPrimitives.black.copy(alpha = 0.62f),
            scrimTopHeight = 120.dp,
            scrimBottomStops = listOf(0.50f to 0f, 0.70f to 0.55f, 1f to 0.90f),
            topInset = 30.dp,
            badgesTop = 41.dp,
            badgeGap = 6.dp,
            titleEndGap = 24.dp,
            iconButtonSize = 44.dp,
            iconSize = 24.dp,
            playPauseSize = 64.dp,
            playPauseIconSize = 36.dp,
            playPauseFocusedIconSize = 30.dp,
            rowGap = 18.dp,
            bottomInset = 14.dp,
            dotSize = 4.dp,
            dotBottom = 3.dp,
            moreOpenFill = NuvioPrimitives.white.copy(alpha = 0.22f),
            pillHeight = 36.dp,
            pillPadding = 14.dp,
            pillIconSize = 18.dp,
            pillIconGap = 6.dp,
            pillRowGap = 10.dp,
            trayIconSize = 20.dp,
            trayIconGap = 8.dp,
            trayPillPaddingStart = 11.dp,
            seekTouchHeight = 20.dp,
            seekHeight = 4.dp,
            seekFocusedHeight = 8.dp,
            seekTrack = NuvioPrimitives.white.copy(alpha = 0.22f),
            seekBuffered = NuvioPrimitives.white.copy(alpha = 0.38f),
            seekGlowAlpha = 0.70f,
            trayToBar = 8.dp,
            barToTimes = 2.dp,
            timesToRow = 8.dp,
            seekThumbSize = 16.dp,
            seekThumbRing = 2.dp,
            seekThumbGlowAlpha = 0.75f,
            seekGhost = NuvioPrimitives.white.copy(alpha = 0.55f),
            previewFrameHeight = 108.dp,
            previewFrameRadius = 8.dp,
            previewFrameRing = NuvioPrimitives.white.copy(alpha = 0.24f),
            previewGap = 6.dp,
            previewTimeFill = NuvioPrimitives.black.copy(alpha = 0.60f),
            previewTimePadding = 8.dp,
            previewBottomControls = 124.dp,
            previewBottomHidden = 76.dp,
            chapterGap = 2.dp,
            chapterPillRadius = 10.dp,
            chapterPillPaddingH = 10.dp,
            chapterPillPaddingV = 2.dp,
            chapterPillMaxWidth = 260.dp,
            chapterNumberGap = 6.dp,
            miniScrimHeight = 160.dp,
            miniScrimStops = listOf(0f to 0f, 0.50f to 0.45f, 1f to 0.78f),
            miniBarBottom = 42.dp,
            miniLabelGap = 6.dp,
            skipBottomControls = 132.dp,
            skipBottomHidden = 54.dp,
            postPlayBottomControls = 144.dp,
            postPlayCardWidth = 360.dp,
            postPlayCardHeight = 96.dp,
            postPlayCardFill = Color(0xEB121212),
            postPlayCardHairline = NuvioPrimitives.white.copy(alpha = 0.12f),
            postPlayStillWidth = 170.dp,
            postPlayTextPaddingH = 14.dp,
            postPlayTextPaddingV = 12.dp,
            postPlayLineGap = 4.dp,
            postPlayPillHeight = 28.dp,
            postPlayOverviewHeight = 56.dp,
            postPlayOverviewTop = 10.dp,
            statsBottomInset = 188.dp,
            statsWidth = 560.dp,
            statsInset = 28.dp,
            statsColumnGap = 22.dp,
            statsGood = Color(0xFF7FD28B),
            statsWarn = NuvioPrimitives.warning,
            statsBad = NuvioPrimitives.error,
            toastTop = 76.dp,
            toastFill = NuvioPrimitives.black.copy(alpha = 0.78f),
            toastRing = NuvioPrimitives.warning.copy(alpha = 0.55f),
            toastRingWidth = 1.dp,
            toastRadius = 20.dp,
            toastPaddingStart = 12.dp,
            toastPaddingEnd = 16.dp,
            toastPaddingVertical = 9.dp,
            toastIconSize = 18.dp,
            toastIconGap = 10.dp
        ),
        search = NuvioMarqueeSearchTokens(
            filterPillHeight = 36.dp,
            filterPillPaddingStart = 16.dp,
            filterPillPaddingEnd = 10.dp,
            filterPillInnerGap = 6.dp,
            filterPillIconSize = 18.dp,
            filterPillGap = 10.dp,
            pickerWidth = 300.dp,
            pickerOffset = 8.dp,
            pickerDim = NuvioPrimitives.black.copy(alpha = 0.35f),
            pickerBottomMargin = 28.dp,
            gridGap = 12.dp,
            landscapeGridCardWidth = 207.dp,
            landscapeGridCardHeight = 116.dp,
            squareGridCardSize = 163.dp,
            gridLineWidth = 864.dp,
            fieldRowGap = 12.dp,
            fieldHeight = 44.dp,
            fieldFill = NuvioPrimitives.white.copy(alpha = 0.08f),
            fieldFocusedFill = NuvioPrimitives.white.copy(alpha = 0.14f),
            fieldEdge = NuvioPrimitives.white.copy(alpha = 0.16f),
            fieldPaddingStart = 16.dp,
            fieldPaddingEnd = 6.dp,
            fieldIconGap = 10.dp,
            clearSize = 32.dp,
            iconSize = 18.dp,
            recentsPanelWidth = 420.dp,
            recentsPanelOffset = 8.dp,
            recentsPanelFill = Color(0xFF0E0E0E).copy(alpha = 0.97f),
            recentsPanelRadius = 20.dp,
            recentsPanelPadding = 12.dp,
            recentsPanelShadow = 18.dp,
            recentsRowHeight = 36.dp,
            recentsRowRadius = 10.dp,
            recentsRowPadding = 12.dp,
            recentsRowGap = 12.dp,
            discoverLabelTop = 24.dp,
            discoverLabelGap = 8.dp,
            compactHeroTop = 80.dp,
            compactHeroWidth = 560.dp,
            compactHeroDescriptionWidth = 520.dp,
            compactHeroGap = 6.dp,
            compactBandTop = 214.dp,
            compactScrimStops = listOf(0f to 0.25f, 0.34f to 0.70f, 0.48f to 0.92f, 0.70f to 1f)
        ),
        library = NuvioMarqueeLibraryTokens(
            tabRowTop = 4.dp,
            tabGap = 22.dp,
            cardCaptionGap = 8.dp
        ),
        stream = NuvioMarqueeStreamTokens(
            titleBlockWidth = 380.dp,
            titleBlockBottom = 56.dp,
            titleBlockGap = 10.dp,
            panelWidth = 452.dp,
            panelInset = 24.dp,
            panelPaddingTop = 18.dp,
            panelPaddingHorizontal = 16.dp,
            panelGap = 12.dp,
            eyebrowInset = 4.dp,
            contextTop = 8.dp,
            rule = NuvioPrimitives.white.copy(alpha = 0.12f),
            chipHeight = 32.dp,
            chipPadding = 14.dp,
            chipInnerGap = 7.dp,
            chipGap = 8.dp,
            refreshSize = 32.dp,
            chipSelectedRing = 1.5.dp,
            pinMarkSize = 12.dp,
            pinFadeWidth = 40.dp,
            pinFade = NuvioPrimitives.black.copy(alpha = 0.85f),
            rowRadius = 14.dp,
            rowFill = NuvioPrimitives.white.copy(alpha = 0.06f),
            rowPaddingHorizontal = 16.dp,
            rowPaddingTop = 11.dp,
            rowPaddingBottom = 12.dp,
            rowGap = 10.dp,
            rowLineGap = 5.dp,
            badgeGap = 5.dp,
            rowColumnGap = 16.dp,
            rowSideTop = 1.dp,
            rowSideMaxWidth = 128.dp,
            sideGap = 3.dp,
            cachedIconSize = 12.dp,
            cachedTint = NuvioPrimitives.premium,
            decodeLineIconSize = 13.dp,
            decodeLineIconGap = 5.dp,
            rowSoftwareAlpha = 0.6f,
            markerIconSize = 13.dp,
            markerIconGap = 4.dp,
            missingFootTop = 10.dp,
            missingFootBottom = 14.dp,
            missingIconSize = 14.dp,
            missingIconGap = 7.dp,
            missingText = NuvioPrimitives.warning,
            limitIconSize = 14.dp,
            limitChipText = NuvioPrimitives.warningOnTint,
            limitChipFill = NuvioPrimitives.warning.copy(alpha = 0.10f),
            limitChipEdge = NuvioPrimitives.warning.copy(alpha = 0.5f),
            limitChipCount = NuvioPrimitives.warningOnTint.copy(alpha = 0.7f),
            limitWarn = NuvioPrimitives.warning,
            limitLineTop = 10.dp,
            limitLineIconGap = 8.dp,
            limitRowIconSize = 12.dp,
            limitRowIconGap = 4.dp,
            circleOnFill = NuvioPrimitives.white.copy(alpha = 0.26f),
            circleBadgeSize = 15.dp,
            circleBadgeOffset = 3.dp,
            circleBadgePadding = 3.dp,
            circleBadgeError = Color(0xFFFF8A80),
            circleRingOutset = 3.dp,
            sheetFill = Color(0xFF0E0E0E).copy(alpha = 0.97f),
            sheetDim = NuvioPrimitives.black.copy(alpha = 0.45f),
            sheetRadius = 20.dp,
            sheetPaddingHorizontal = 20.dp,
            sheetPaddingTop = 18.dp,
            sheetPaddingBottom = 16.dp,
            sheetGap = 8.dp,
            sheetRowHeight = 46.dp,
            sheetRowRadius = 12.dp,
            sheetRowPadding = 14.dp,
            sheetRowIconSize = 16.dp,
            sheetRowInnerGap = 10.dp,
            facetChipHeight = 28.dp,
            facetChipPadding = 12.dp,
            facetChipGap = 6.dp,
            facetGroupGap = 5.dp,
            facetCheckSize = 14.dp,
            facetZeroAlpha = 0.38f,
            savedOnAlpha = 0.30f,
            sheetButtonHeight = 36.dp,
            sheetButtonPadding = 16.dp,
            phoneVeil = NuvioPrimitives.black.copy(alpha = 0.72f),
            phoneCardWidth = 560.dp,
            phoneCardFill = Color(0xFF121212).copy(alpha = 0.98f),
            phoneCardEdge = NuvioPrimitives.white.copy(alpha = 0.08f),
            phoneCardPadding = 28.dp,
            rowMenuVeil = NuvioPrimitives.black.copy(alpha = 0.35f),
            rowMenuWidth = 300.dp,
            rowMenuFill = Color(0xFF141414).copy(alpha = 0.98f),
            rowMenuEdge = NuvioPrimitives.white.copy(alpha = 0.10f),
            rowMenuRadius = 14.dp,
            rowMenuPadding = 8.dp,
            rowMenuOffset = 5.dp,
            rowMenuInset = 60.dp,
            rowMenuScreenMargin = 28.dp,
            rowMenuItemGap = 2.dp,
            rowMenuItemRadius = 9.dp,
            rowMenuItemPaddingHorizontal = 10.dp,
            rowMenuItemPaddingVertical = 8.dp,
            rowMenuIconSize = 14.dp,
            rowMenuIconGap = 10.dp,
            skeletonRowFill = NuvioPrimitives.white.copy(alpha = 0.04f),
            skeletonRowPaddingVertical = 12.dp,
            skeletonLineGap = 8.dp,
            messagePaddingVertical = 28.dp,
            messagePaddingHorizontal = 8.dp,
            messageGap = 10.dp,
            messageBody = NuvioPrimitives.white.copy(alpha = 0.75f),
            messageActionTop = 10.dp,
            consentWidth = 560.dp,
            confirmWidth = 440.dp,
            confirmDim = NuvioPrimitives.black.copy(alpha = 0.55f),
            dialogBody = NuvioPrimitives.white.copy(alpha = 0.8f),
            dialogTextInset = 10.dp,
            dialogActionGap = 12.dp,
            dialogActionTop = 16.dp,
            dialogActionBottom = 2.dp,
            certDim = NuvioPrimitives.black.copy(alpha = 0.78f),
            certHostBottom = 8.dp,
            certFingerprintTop = 12.dp,
            certFingerprintPaddingVertical = 9.dp,
            certFingerprintPaddingHorizontal = 12.dp,
            certFingerprintRadius = 10.dp,
            certFingerprintFill = NuvioPrimitives.white.copy(alpha = 0.06f),
            certCaption = NuvioPrimitives.white.copy(alpha = 0.5f),
            certFingerprint = NuvioPrimitives.white.copy(alpha = 0.85f),
            certOldFingerprint = NuvioPrimitives.white.copy(alpha = 0.45f),
            certColumnGap = 22.dp
        ),
        playerPanel = NuvioMarqueePlayerPanelTokens(
            compactWidth = 320.dp,
            compactEndInset = 44.dp,
            compactVerticalInset = 28.dp,
            adjustPaddingHorizontal = 10.dp,
            adjustPaddingTop = 4.dp,
            adjustPaddingBottom = 8.dp,
            adjustGap = 8.dp,
            stepperSize = 28.dp,
            stepperIconSize = 16.dp,
            valueMinWidth = 64.dp,
            helperTop = 3.dp,
            disabledAlpha = 0.35f,
            actionTop = 4.dp,
            adjustBelowTop = 8.dp,
            dotSize = 20.dp,
            dotSizeSmall = 16.dp,
            dotGap = 9.dp,
            dotGapSmall = 8.dp,
            dotEdge = Color.White.copy(alpha = 0.25f),
            dotRingGap = 2.dp,
            dotRingWidth = 2.dp,
            dotSelectedRing = Color.White.copy(alpha = 0.7f),
            dotFocusScale = 1.1f,
            pillHeight = 26.dp,
            togglePaddingHorizontal = 12.dp,
            segmentPaddingHorizontal = 8.dp,
            segmentGap = 5.dp,
            segmentFill = Color.White.copy(alpha = 0.06f),
            centredPaddingHorizontal = 18.dp,
            centredPaddingVertical = 16.dp,
            contentInset = 10.dp,
            stripWidth = 576.dp,
            stripTop = 44.dp,
            syncWidth = 560.dp,
            syncTop = 72.dp,
            syncScrim = Color.Black.copy(alpha = 0.45f),
            sliderHeight = 16.dp,
            sliderTop = 6.dp,
            sliderBottom = 14.dp,
            sliderTrackHeight = 8.dp,
            sliderTrack = Color.White.copy(alpha = 0.22f),
            zeroMarkWidth = 2.dp,
            zeroMarkHeight = 18.dp,
            zeroMark = Color.White.copy(alpha = 0.5f),
            smallButtonHeight = 36.dp,
            smallButtonPadding = 16.dp,
            smallButtonGap = 10.dp,
            cueTimeWidth = 64.dp,
            cueListMaxHeight = 204.dp,
            pauseScrimStartStops = listOf(0f to 0.88f, 0.40f to 0.60f, 0.70f to 0.15f, 1f to 0f),
            pauseScrimBottomStops = listOf(0f to 0.35f, 0.30f to 0f, 0.60f to 0.20f, 1f to 0.75f),
            pauseBlockWidth = 500.dp,
            pauseBlockBottom = 56.dp,
            pauseBlockGap = 10.dp,
            pausePlotWidth = 480.dp,
            castLabelTop = 8.dp,
            castRowWidth = 880.dp,
            castRowFadeStart = 0.88f,
            castChipHeight = 36.dp,
            castChipGap = 8.dp,
            castChipPaddingStart = 4.dp,
            castChipPaddingEnd = 14.dp,
            castChipNameMaxWidth = 240.dp,
            castAvatarSize = 28.dp,
            castAvatarColors = listOf(
                Color(0xFFC2185B),
                Color(0xFF5C6BC0),
                Color(0xFF00897B),
                Color(0xFFF4511E),
                Color(0xFF8E24AA),
                Color(0xFF3949AB)
            ),
            castPhotoWidth = 120.dp,
            castPhotoHeight = 180.dp,
            castPhotoRadius = 12.dp,
            castDetailGap = 28.dp,
            castDetailTextGap = 8.dp,
            castDetailTextBottom = 4.dp,
            castBackTop = 18.dp,
            handOffBarWidth = 360.dp,
            handOffBarHeight = 2.dp,
            handOffBarTop = 4.dp,
            handOffBarTrack = Color.White.copy(alpha = 0.22f),
            handOffGlowAlpha = 0.70f,
            handOffStatusTop = 6.dp,
            handOffStatusGap = 10.dp,
            handOffSpinnerSize = 14.dp,
            handOffSpinnerTrack = Color.White.copy(alpha = 0.25f),
            handOffSourceMaxWidth = 160.dp,
            episodeRowRadius = 14.dp,
            episodeRowFill = Color.White.copy(alpha = 0.06f),
            episodeRowPaddingVertical = 10.dp,
            episodeRowPaddingHorizontal = 12.dp,
            episodeRowGap = 14.dp,
            episodeStillWidth = 128.dp,
            episodeStillHeight = 72.dp,
            episodeStillRadius = 8.dp,
            episodeStillFill = Color(0xFF1A1A1A),
            episodeStillInset = 6.dp,
            episodeTickSize = 18.dp,
            episodeTickIconSize = 13.dp,
            episodeProgressBottom = 5.dp,
            episodeTextGap = 3.dp,
            episodeMetaGap = 8.dp,
            endPromptWidth = 460.dp,
            endPromptDim = Color.Black.copy(alpha = 0.55f),
            endPromptBody = Color.White.copy(alpha = 0.80f),
            endPromptBodyBottom = 12.dp,
            endPromptRowMargin = 4.dp,
            endPromptRowBottom = 14.dp
        ),
        collections = NuvioMarqueeCollectionsTokens(
            veil = NuvioPrimitives.black.copy(alpha = 0.86f),
            headerTop = 22.dp,
            headerButtonPaddingHorizontal = 18.dp,
            headerButtonGap = 10.dp,
            listTop = 76.dp,
            rowGap = 6.dp,
            rowFill = NuvioPrimitives.white.copy(alpha = 0.06f),
            rowRadius = 12.dp,
            rowPaddingHorizontal = 14.dp,
            rowPaddingVertical = 6.dp,
            rowInnerGap = 12.dp,
            thumbWidth = 64.dp,
            thumbHeight = 36.dp,
            thumbRadius = 6.dp,
            thumbFill = NuvioPrimitives.neutral850,
            actionHeight = 30.dp,
            actionPaddingHorizontal = 12.dp,
            actionGap = 6.dp,
            panelVeil = NuvioPrimitives.black.copy(alpha = 0.7f),
            importWidth = 500.dp,
            panelGap = 6.dp,
            fieldHeight = 40.dp,
            fieldPaddingStart = 14.dp,
            editorFormWidth = 452.dp,
            editorColumnGap = 32.dp,
            sectionTopPadding = 8.dp,
            sectionBottomPadding = 2.dp,
            previewHeight = 118.dp,
            previewRadius = 14.dp,
            previewInset = 16.dp,
            previewTilesTop = 42.dp,
            previewTileGap = 8.dp,
            previewTileWidth = 44.dp,
            previewTileHeight = 66.dp,
            previewLandscapeTileWidth = 88.dp,
            previewLandscapeTileHeight = 50.dp,
            previewTileRadius = 12.dp,
            thumbPosterWidth = 24.dp,
            addButtonPaddingHorizontal = 14.dp,
            folderPreviewHeight = 96.dp,
            folderPreviewLogoHeight = 28.dp,
            folderPreviewLogoMaxWidth = 240.dp,
            folderPreviewTileWidth = 54.dp,
            folderPreviewTileHeight = 80.dp,
            folderPreviewTileTop = 8.dp,
            folderPreviewTileEnd = 40.dp,
            sourceOutlineWidth = 1.dp,
            sourceMissingOutline = NuvioPrimitives.errorOnImage.copy(alpha = 0.55f),
            sourceRetiredOutline = NuvioPrimitives.warning.copy(alpha = 0.55f),
            sidePanelWidth = 400.dp,
            tmdbPanelWidth = 520.dp,
            sidePanelInset = 28.dp,
            emojiGap = 8.dp,
            emojiCellRadius = 12.dp,
            emojiCellFill = NuvioPrimitives.white.copy(alpha = 0.07f),
            emojiSelectedRing = NuvioPrimitives.white.copy(alpha = 0.6f),
            emojiFocusScale = 1.08f,
            coverPreviewWidth = 112.dp,
            coverPreviewHeight = 168.dp,
            coverPreviewLandscapeWidth = 224.dp,
            coverPreviewLandscapeHeight = 126.dp
        ),
        profiles = NuvioMarqueeProfilesTokens(
            pinTop = 44.dp,
            pinAvatarSize = 72.dp,
            pinAvatarToTitle = 14.dp,
            pinTitleToBoxes = 18.dp,
            pinBoxesToSupport = 12.dp,
            pinSupportToKeypad = 16.dp,
            pinKeypadToFooter = 16.dp,
            boxWidth = 48.dp,
            boxHeight = 56.dp,
            boxRadius = 12.dp,
            boxGap = 12.dp,
            boxOutlineWidth = 1.dp,
            boxOutline = NuvioPrimitives.white.copy(alpha = 0.5f),
            boxFilledOutline = NuvioPrimitives.white.copy(alpha = 0.92f),
            boxFilledFill = NuvioPrimitives.white.copy(alpha = 0.07f),
            boxDot = 12.dp,
            boxActiveOutlineWidth = 2.dp,
            boxActiveFill = NuvioPrimitives.white.copy(alpha = 0.04f),
            boxCursorWidth = 2.dp,
            boxCursorHeight = 24.dp,
            boxErrorOutline = Color(0xFFE35D5D),
            boxErrorFill = Color(0xFF311818).copy(alpha = 0.76f),
            keyWidth = 64.dp,
            keyHeight = 44.dp,
            keyGap = 8.dp,
            keyFill = NuvioPrimitives.white.copy(alpha = 0.08f),
            keyFocusScale = 1.05f,
            keyDisabledAlpha = 0.35f,
            keyIconSize = 20.dp,
            pinErrorText = Color(0xFFFF8E8E),
            dpadSupportToRing = 22.dp,
            dpadRingToFooter = 24.dp,
            dpadRingSize = 176.dp,
            dpadRingFill = NuvioPrimitives.white.copy(alpha = 0.06f),
            dpadRingEdge = NuvioPrimitives.white.copy(alpha = 0.16f),
            dpadChevronSize = 30.dp,
            dpadChevronInset = 10.dp,
            dpadRingCentreSize = 72.dp,
            dpadRingCentreFill = NuvioPrimitives.white.copy(alpha = 0.10f),
            dpadRingHalo = NuvioPrimitives.white.copy(alpha = 0.06f),
            dpadRingHaloWidth = 8.dp,
            dpadPulseAttackMs = 70,
            dpadPulseDecayMs = 300,
            successFadeMs = 180,
            dpadSlotWidth = 32.dp,
            dpadSlotHeight = 40.dp,
            dpadSlotRadius = 10.dp,
            dpadSlotGap = 10.dp,
            dpadSlotOutline = NuvioPrimitives.white.copy(alpha = 0.4f),
            dpadSlotDot = 10.dp,
            dpadGlyphIconSize = 34.dp,
            successOutline = Color(0xFF6FD08A),
            successFill = Color(0xFF18311E).copy(alpha = 0.7f),
            successText = Color(0xFF8EE6A2),
            optionsPanelWidth = 360.dp,
            optionsHeadPaddingHorizontal = 6.dp,
            optionsHeadPaddingTop = 2.dp,
            optionsHeadPaddingBottom = 10.dp,
            optionsAvatarSize = 44.dp,
            optionsHeadGap = 12.dp,
            wordmarkHeight = 20.dp,
            titleTop = 100.dp,
            titleTopWithSubtitle = 88.dp,
            subtitleTop = 148.dp,
            rowTop = 184.dp,
            rowTopWithSubtitle = 204.dp,
            hintBottom = 26.dp,
            avatarSize = 128.dp,
            avatarSizeCompact = 116.dp,
            cardGap = 30.dp,
            cardGapCompact = 20.dp,
            cardFocusScale = 1.08f,
            ringGap = 3.dp,
            avatarToName = 12.dp,
            nameLineHeight = 22.dp,
            nameToDetails = 12.dp,
            cardLineGap = 6.dp,
            primaryTag = Color(0xFFFFB300),
            addFill = NuvioPrimitives.white.copy(alpha = 0.06f),
            addEdge = NuvioPrimitives.white.copy(alpha = 0.22f),
            addEdgeWidth = 1.5.dp,
            addIconSize = 36.dp,
            lockBadgeSize = 30.dp,
            lockBadgeEdge = NuvioPrimitives.white.copy(alpha = 0.85f),
            lockBadgeEdgeWidth = 2.dp,
            lockIconSize = 15.dp,
            artImageAlpha = 0.6f,
            artTintAlpha = 0.22f,
            artVeilMid = 0.55f,
            artVeilEdge = 0.92f,
            artTopScrim = 0.4f,
            artBottomScrim = 0.7f,
            backgroundVeil = NuvioPrimitives.black.copy(alpha = 0.45f),
            pickerChipGap = 6.dp,
            pickerColumns = 7,
            pickerAvatarSize = 56.dp,
            pickerGapHorizontal = 18.dp,
            pickerGapVertical = 14.dp,
            pickerGridPaddingVertical = 8.dp,
            pickerSelectedRing = NuvioPrimitives.white.copy(alpha = 0.55f),
            pickerSelectedRingWidth = 2.dp,
            pickerRingGap = 2.dp,
            pickerFocusScale = 1.1f,
            editorVeil = NuvioPrimitives.black.copy(alpha = 0.45f),
            editorPreviewWidth = 300.dp,
            editorPreviewTop = 84.dp,
            editorAvatarSize = 120.dp,
            editorPreviewGap = 10.dp,
            editorFieldTop = 14.dp,
            editorRule = NuvioPrimitives.white.copy(alpha = 0.12f),
            editorRuleStart = 380.dp,
            editorRuleBottom = 56.dp,
            editorPickerStart = 412.dp,
            editorPickerTop = 76.dp,
            editorPickerWidth = 500.dp,
            editorPickerGap = 12.dp,
            fieldHeight = 44.dp,
            fieldRadius = 12.dp,
            fieldPaddingHorizontal = 14.dp,
            fieldLabelGap = 6.dp
        ),
        formRow = NuvioMarqueeFormRowTokens(
            minHeight = 44.dp,
            fill = NuvioPrimitives.white.copy(alpha = 0.06f),
            radius = 12.dp,
            paddingHorizontal = 14.dp,
            paddingVertical = 6.dp,
            innerGap = 12.dp,
            labelMaxWidth = 380.dp,
            dimAlpha = 0.45f,
            destructiveText = NuvioPrimitives.errorOnImage,
            iconSize = 18.dp,
            leadingIconSize = 15.dp,
            leadingIconGap = 6.dp,
            switchWidth = 34.dp,
            switchHeight = 20.dp,
            switchKnob = 14.dp,
            switchKnobInset = 3.dp,
            switchOffTrack = NuvioPrimitives.white.copy(alpha = 0.22f),
            switchOffKnob = NuvioPrimitives.white.copy(alpha = 0.75f)
        ),
        settings = NuvioMarqueeSettingsTokens(
            railWidth = 196.dp,
            topInset = 12.dp,
            railItemHeight = 38.dp,
            railItemGap = 2.dp,
            railItemPaddingHorizontal = 14.dp,
            railIconSize = 18.dp,
            railIconGap = 12.dp,
            railBarWidth = 3.dp,
            railBarHeight = 16.dp,
            railDotSize = 7.dp,
            // The mockup's 12 px gap less the dot's 4 px pull-back.
            railDotGap = 8.dp,
            railToPane = 40.dp,
            rowGap = 6.dp,
            rowMinHeight = 48.dp,
            fadeHeight = 64.dp,
            headerToChips = 6.dp,
            sectionTopPadding = 10.dp,
            sectionBottomPadding = 2.dp,
            notePadding = 4.dp,
            chipHeight = 28.dp,
            chipPaddingHorizontal = 12.dp,
            chipGap = 6.dp,
            chipFill = NuvioPrimitives.white.copy(alpha = 0.08f),
            chipsBottomPadding = 6.dp,
            selectedRingWidth = 1.5.dp,
            segmentHeight = 28.dp,
            segmentPaddingHorizontal = 12.dp,
            segmentGap = 4.dp,
            segmentFill = NuvioPrimitives.white.copy(alpha = 0.06f),
            stepperTrackWidth = 92.dp,
            stepperTrackHeight = 3.dp,
            stepperTrackFill = NuvioPrimitives.white.copy(alpha = 0.2f),
            stepperValueMinWidth = 36.dp,
            stepperGap = 10.dp,
            optionStepperTop = 2.dp,
            optionStepperInset = 6.dp,
            optionStepperFill = NuvioPrimitives.white.copy(alpha = 0.06f),
            optionStepperRadius = 10.dp,
            optionStepperPaddingHorizontal = 10.dp,
            optionStepperPaddingVertical = 8.dp,
            optionStepperGap = 12.dp,
            optionStepperIconSize = 16.dp,
            optionStepperValueMinWidth = 64.dp,
            optionStepperDimAlpha = 0.45f,
            groupRule = NuvioPrimitives.white.copy(alpha = 0.14f),
            groupPillHeight = 30.dp,
            groupPillPaddingHorizontal = 14.dp,
            groupGap = 12.dp,
            groupIconSize = 16.dp,
            swatchSize = 32.dp,
            swatchGap = 14.dp,
            swatchRingGap = 2.dp,
            swatchRingWidth = 2.dp,
            swatchSelectedRing = NuvioPrimitives.white.copy(alpha = 0.55f),
            swatchFocusScale = 1.14f,
            colorChipSize = 14.dp,
            colorChipRadius = 4.dp,
            colorChipRingWidth = 1.dp,
            colorChipRing = NuvioPrimitives.white.copy(alpha = 0.4f),
            colorChipGap = 6.dp,
            previewHeight = 96.dp,
            previewRadius = 12.dp,
            previewBottom = 4.dp,
            // The mockup draws the 100% cue at 17 px against the player's 24 sp.
            previewTextScale = 17f / 24f,
            previewTagInsetStart = 10.dp,
            previewTagInsetTop = 8.dp,
            previewTagText = NuvioPrimitives.white.copy(alpha = 0.75f),
            previewTagFill = NuvioPrimitives.black.copy(alpha = 0.6f),
            previewTagRadius = 4.dp,
            previewTagPaddingHorizontal = 6.dp,
            previewTagPaddingVertical = 2.dp,
            pickerWidth = 360.dp,
            // Spec 58 L5: 48, was 96 (348 dp tall cut the stream-limit picker's foot note).
            pickerInsetVertical = 48.dp,
            panelTextInset = 10.dp,
            qrPanelWidth = 340.dp,
            qrSize = 170.dp,
            qrGap = 10.dp,
            qrActionHeight = 36.dp,
            qrActionPaddingHorizontal = 18.dp,
            messageIconSize = 16.dp,
            messageIconGap = 8.dp,
            busyAlpha = 0.55f,
            keyQrSize = 112.dp,
            keyQrRadius = 10.dp,
            keyQrFill = 0.84f,
            keyQrGap = 14.dp,
            resultsFill = NuvioPrimitives.white.copy(alpha = 0.04f),
            resultsRadius = 12.dp,
            resultsPaddingHorizontal = 14.dp,
            resultsPaddingVertical = 8.dp,
            resultsLineGap = 2.dp,
            accountAvatarSize = 44.dp,
            accountGap = 14.dp,
            accountBlockBottom = 8.dp,
            tableAvatarSize = 22.dp,
            tableCellPaddingVertical = 4.dp,
            signInIntroTop = 150.dp,
            signInIntroWidth = 430.dp,
            signInIntroGap = 16.dp,
            signInIntroActionTop = 30.dp,
            signInCardWidth = 360.dp,
            signInCardTop = 72.dp,
            signInCardBottom = 44.dp,
            signInCardRadius = 20.dp,
            signInCardFill = NuvioPrimitives.neutral900,
            signInCardEdge = NuvioPrimitives.white.copy(alpha = 0.08f),
            signInCardPaddingHorizontal = 24.dp,
            signInCardPaddingTop = 20.dp,
            signInCardPaddingBottom = 16.dp,
            signInCardGap = 7.dp,
            signInQrRadius = 10.dp,
            signInExpiredVeil = NuvioPrimitives.black.copy(alpha = 0.84f),
            signInStatusDot = 7.dp,
            signInPillTop = 22.dp
        ),
        toast = NuvioMarqueeToastTokens(
            fill = NuvioPrimitives.black.copy(alpha = 0.78f),
            edge = NuvioPrimitives.white.copy(alpha = 0.14f),
            paddingHorizontal = 22.dp,
            paddingVertical = 12.dp,
            bottom = 34.dp,
            durationMs = 2_600L,
            successEdge = NuvioPrimitives.successOnImage.copy(alpha = 0.55f),
            successIcon = NuvioPrimitives.successOnImage,
            successIconSize = 18.dp,
            successIconGap = 10.dp,
            successPaddingStart = 12.dp,
            successPaddingEnd = 16.dp,
            successPaddingVertical = 9.dp
        ),
        welcome = NuvioMarqueeWelcomeTokens(
            veil = NuvioPrimitives.black.copy(alpha = 0.88f),
            cardWidth = 560.dp,
            cardTop = 48.dp,
            cardBottom = 24.dp,
            cardPaddingHorizontal = 22.dp,
            cardPaddingTop = 18.dp,
            cardPaddingBottom = 16.dp,
            cardGap = 6.dp,
            rowsTop = 6.dp,
            rowMinHeight = 32.dp,
            rowFill = NuvioPrimitives.white.copy(alpha = 0.04f),
            rowRadius = 10.dp,
            rowPaddingHorizontal = 12.dp,
            rowIconSize = 16.dp,
            rowIconGap = 10.dp,
            actionsTop = 6.dp
        ),
        liveTv = NuvioMarqueeLiveTvTokens(
            accent = Color(0xFFE9B04F),
            pillsTop = 4.dp,
            pillHeight = 32.dp,
            pillPaddingHorizontal = 14.dp,
            pillGap = 10.dp,
            pillCountGap = 6.dp,
            pillOutline = NuvioPrimitives.white.copy(alpha = 0.22f),
            pillIconSize = 16.dp,
            pillIconGap = 6.dp,
            listStart = 38.dp,
            listTop = 124.dp,
            listWidth = 430.dp,
            rowHeight = 62.dp,
            rowGap = 4.dp,
            rowRadius = 12.dp,
            rowPaddingStart = 10.dp,
            rowPaddingEnd = 14.dp,
            rowInnerGap = 12.dp,
            numberWidth = 22.dp,
            logoSize = 40.dp,
            logoRadius = 9.dp,
            rowProgressWidth = 40.dp,
            focusRowsAbove = 3,
            selectedFill = NuvioPrimitives.white.copy(alpha = 0.14f),
            mutedAlpha = 0.5f,
            lostAlpha = 0.42f,
            lostFocusedAlpha = 0.62f,
            favorite = Color(0xFFFFC94D),
            favoriteSize = 13.dp,
            warnTagFill = NuvioPrimitives.warning.copy(alpha = 0.16f),
            warnTagFocusedText = Color(0xFF8A4B00),
            warnTagFocusedFill = NuvioPrimitives.warning.copy(alpha = 0.35f),
            fadeHeight = 56.dp,
            previewStart = 492.dp,
            previewTop = 128.dp,
            previewWidth = 420.dp,
            previewHeight = 236.dp,
            previewRadius = 12.dp,
            previewFill = Color(0xFF111111),
            previewInset = 12.dp,
            previewLogoSize = 28.dp,
            tuningVeil = NuvioPrimitives.black.copy(alpha = 0.55f),
            tuningSpinnerSize = 26.dp,
            errorFill = Color(0xFF121212),
            errorEdge = NuvioPrimitives.white.copy(alpha = 0.08f),
            errorIconSize = 28.dp,
            warn = Color(0xFFFFB74D),
            buttonHeight = 36.dp,
            buttonPaddingHorizontal = 18.dp,
            infoTop = 380.dp,
            infoGap = 8.dp,
            infoProgressWidth = 160.dp,
            liveRed = Color(0xFFE53935),
            badgeHeight = 18.dp,
            badgePaddingHorizontal = 7.dp,
            badgeRadius = 4.dp,
            liveDotSize = 5.dp,
            emptyTop = 150.dp,
            emptyWidth = 560.dp, // Spec 73 D2: three buttons
            emptyGap = 12.dp,
            fullInset = 48.dp,
            fullTop = 32.dp,
            clockTop = 36.dp,
            fullBottom = 36.dp,
            fullProgressWidth = 320.dp,
            osdScrimTop = 150.dp,
            osdScrimBottom = 260.dp,
            zapScrimTop = 110.dp,
            zapScrimBottom = 220.dp,
            bannerWidth = 470.dp,
            bannerRadius = 16.dp,
            bannerPaddingHorizontal = 16.dp,
            bannerPaddingVertical = 14.dp,
            bannerGap = 8.dp,
            bannerLogoSize = 36.dp,
            bannerProgressWidth = 200.dp,
            chromeFill = NuvioPrimitives.black.copy(alpha = 0.8f),
            chromeEdge = NuvioPrimitives.white.copy(alpha = 0.08f),
            overlayStart = 32.dp,
            overlayTop = 72.dp,
            overlayBottom = 24.dp,
            overlayWidth = 400.dp,
            overlayRadius = 20.dp,
            overlayListTop = 90.dp,
            overlayListInset = 8.dp,
            overlayShade = NuvioPrimitives.black.copy(alpha = 0.5f),
            chipHeight = 28.dp,
            chipPaddingHorizontal = 12.dp,
            chipGap = 6.dp,
            chipFill = NuvioPrimitives.white.copy(alpha = 0.08f),
            chipRing = 1.5.dp,
            optionsPanelWidth = 340.dp,
            guidePanelWidth = 360.dp,
            panelInsetVertical = 60.dp,
            sourcesTypeWidth = 66.dp,
            sourcesIconSize = 18.dp,
            sourcesButtonPaddingHorizontal = 16.dp,
            sourcesButtonGap = 10.dp,
            addPanelWidth = 520.dp,
            addVeil = NuvioPrimitives.black.copy(alpha = 0.78f),
            suggestFill = Color(0xFF7FD28B).copy(alpha = 0.08f),
            suggestEdge = Color(0xFF7FD28B).copy(alpha = 0.35f),
            guidesPickWidth = 360.dp,
            guidesPickFill = Color(0xFF1A1A1A),
            guidesPickVeil = NuvioPrimitives.black.copy(alpha = 0.6f),
            scanPickWidth = 420.dp,
            scanCardWidth = 460.dp,
            scanBarHeight = 4.dp,
            scanBarTrack = NuvioPrimitives.white.copy(alpha = 0.22f),
            pickerPaddingEnd = 8.dp,
            pickerInnerGap = 6.dp,
            pickerValueMaxWidth = 150.dp,
            scheduleHeadWidth = 176.dp,
            scheduleHeadHeight = 99.dp,
            scheduleHeadRadius = 10.dp,
            scheduleHeadGap = 12.dp,
            scheduleGap = 10.dp,
            scheduleRowMinHeight = 40.dp,
            scheduleRowRadius = 10.dp,
            scheduleRowPaddingHorizontal = 12.dp,
            scheduleRowInnerGap = 14.dp,
            scheduleTimeWidth = 44.dp,
            scheduleProgressWidth = 120.dp,
            scheduleNowFill = NuvioPrimitives.white.copy(alpha = 0.07f),
            scheduleListGap = 2.dp,
            guideBandTop = 124.dp,
            guideBandHeight = 118.dp,
            guideBandPictureWidth = 210.dp,
            guideBandRadius = 10.dp,
            guideBandGap = 18.dp,
            guideBandProgressWidth = 200.dp,
            guideTop = 256.dp,
            guideRulerHeight = 24.dp,
            guideChipHeight = 22.dp,
            guideChipPaddingHorizontal = 10.dp,
            guideRowHeight = 44.dp,
            guideCompactRowHeight = 32.dp,
            guideGap = 4.dp,
            guideChannelWidth = 196.dp,
            guideNumberWidth = 34.dp,
            guideLogoSize = 28.dp,
            guideCompactLogoSize = 22.dp,
            guideCellRadius = 8.dp,
            guideCutRadius = 2.dp,
            guideCellPaddingHorizontal = 10.dp,
            guideCellFill = NuvioPrimitives.white.copy(alpha = 0.07f),
            guideAiringFill = NuvioPrimitives.white.copy(alpha = 0.13f),
            guideNoneEdge = NuvioPrimitives.white.copy(alpha = 0.08f),
            guideEndStripe = NuvioPrimitives.white.copy(alpha = 0.035f),
            guideMarkEdge = NuvioPrimitives.white.copy(alpha = 0.18f),
            guideMidnightEdge = NuvioPrimitives.white.copy(alpha = 0.5f),
            guideNowLineWidth = 2.dp
        ),
        tip = NuvioMarqueeTipTokens(
            veil = NuvioPrimitives.black.copy(alpha = 0.94f),
            cardTop = 64.dp,
            cardBottom = 40.dp,
            cardGap = 8.dp,
            qrTile = 196.dp,
            qrRadius = 12.dp,
            qrInset = 14.dp,
            introTop = 118.dp,
            introWidth = 440.dp,
            introGap = 14.dp,
            titleSize = 36.sp,
            titleLineHeight = 42.sp,
            chipHeight = 34.dp,
            chipPaddingHorizontal = 16.dp,
            chipGap = 8.dp,
            chipFill = NuvioPrimitives.white.copy(alpha = 0.08f),
            chipMidFill = NuvioPrimitives.white.copy(alpha = 0.16f),
            chipEdge = NuvioPrimitives.white.copy(alpha = 0.1f),
            ladderGap = 8.dp,
            termsWidth = 290.dp,
            heart = NuvioPrimitives.errorOnImage,
            heartOnFocus = Color(0xFFD32F2F),
            rowHeart = 20.dp,
            rowHeartEnd = 2.dp,
            thanksHeart = 64.dp,
            heartGradient = listOf(Color(0xFFFFB4A9), Color(0xFFFF6E63), NuvioPrimitives.red500),
            thanksTitleSize = 52.sp,
            thanksTitleLineHeight = 60.sp,
            thanksLineWidth = 640.dp,
            signature = Color(0xFFFFB4A9),
            thanksHeartGap = 20.dp,
            thanksLineTop = 10.dp,
            thanksSignatureTop = 14.dp,
            thanksDoneTop = 28.dp,
            sparkColors = listOf(
                NuvioPrimitives.errorOnImage, NuvioPrimitives.warning, NuvioPrimitives.white,
                Color(0xFFFF6E63), Color(0xFFFFD7CF)
            )
        )
    )
}
