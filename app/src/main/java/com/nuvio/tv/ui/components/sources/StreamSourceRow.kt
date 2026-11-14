package com.nuvio.tv.ui.components.sources

import com.nuvio.tv.domain.model.ReleaseResolution
import com.nuvio.tv.core.stream.resolutionLabel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DecodeCapability
import com.nuvio.tv.core.player.DecodeVerdict
import com.nuvio.tv.core.stream.ReleaseFacts
import com.nuvio.tv.core.stream.ReleaseFactsParser
import com.nuvio.tv.domain.model.AioStreamData
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.formatStreamSizeLabel
import com.nuvio.tv.ui.components.rememberRawSvgPainter
import com.nuvio.tv.ui.components.marquee.MarqueeSourceBadges
import com.nuvio.tv.ui.components.marquee.rememberMarqueeLongPress
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioTheme

private const val DETAIL_MAX_LINES = 2
private const val ADDON_TEXT_MAX_LINES = 4

// Spec 75 S6 mark line (sandbox `spec-75-failure-reasons` `.p75ln`, `.sr.p75dim`).
private const val FAILED_ROW_ALPHA = 0.55f
private val FAILURE_MARK_TEXT_SIZE = 11.5.sp
private val FAILURE_MARK_LINE_HEIGHT = 15.sp
private val FAILURE_MARK_ICON_SIZE = 13.dp
private val FAILURE_MARK_GAP = 5.dp

/**
 * One source row (approved sandbox `u5-stream-screen-marquee` stages 1-2 +
 * `data-additions-marquee` stage 6). Badges mode: the shared source badges, the stream name, then
 * [streamRowDetailText]; the side column holds the [marker] ("Last played", "Preferred", or the
 * player's "Playing" with a play icon, u6 stage 1) in the ambient [accent], the addon (for AIOStreams the
 * upstream that found the stream, Spec 42 D6), the size ([showSize], the "Size badges" setting), and
 * the languages + the stream kind ("EN · PM") with a bolt when the debrid file is cached. [useAddonFormatting]: the name and the addon's own
 * description (line breaks kept, up to 4 lines); the side column keeps the marker and the
 * addon only (QU5-11).
 * [overLimitMbps] / [overResolutionCap] (a stream the limits hide, listed in a hidden view, Spec 51
 * P6, Spec 58 D3): the side column ends on why, in amber behind a warning icon ("109 Mbps", "Over
 * 1080p", or both), in place of the languages line. [decode] (Spec 65 P8, D15): a stream this
 * device cannot play as released gets a sentence under the file name, in either formatting; one it
 * would decode in software has the rest of its row faded. Focus =
 * glass fill + inset ring, no scale. [failureMark] (Spec 75 S6, sandbox `spec-75-failure-reasons` stages 9-10): a
 * source that failed in the last day gets a red line under its file name (the reason and its age)
 * and the rest of the row faded. [onLongPress] (hold OK, or MENU; Spec 65 P6's row menu): the
 * release that follows does not click. Callers put `focusRequester`/`onFocusChanged`/key handling
 * in [modifier].
 */
@Composable
internal fun StreamSourceRow(
    stream: Stream,
    showSize: Boolean,
    useAddonFormatting: Boolean,
    marker: SourceRowMarker?,
    accent: () -> Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overLimitMbps: Int? = null,
    overResolutionCap: ReleaseResolution? = null,
    decode: DecodeCapability = DecodeCapability.UNKNOWN,
    onLongPress: (() -> Unit)? = null,
    failureMark: SourceFailureMark? = null
) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val styles = NuvioTheme.textStyles
    val unknownName = stringResource(R.string.stream_unknown)
    val gbTemplate = stringResource(R.string.unit_size_gb)
    val mbTemplate = stringResource(R.string.unit_size_mb)
    // Classify once per stream: cheap enough for the visible rows. Null with addon formatting,
    // which shows nothing derived from the facts.
    val facts = remember(stream, useAddonFormatting) {
        if (useAddonFormatting) null else ReleaseFactsParser.factsFor(stream)
    }
    // Its own parse only with addon formatting, and only on a device with something to say.
    val decodeFacts = remember(stream, facts, decode) {
        when {
            decode.saysNothing -> null
            facts != null -> facts
            else -> ReleaseFactsParser.factsFor(stream)
        }
    }
    val verdict = decodeFacts?.let(decode::verdict)
    // The mockup fades everything but the sentence itself.
    val softwareFade = if (verdict == DecodeVerdict.SOFTWARE) Modifier.alpha(tokens.rowSoftwareAlpha) else Modifier
    // Spec 75: a marked row fades all but its mark line (`.sr.p75dim`).
    val fade = if (failureMark != null) softwareFade.alpha(FAILED_ROW_ALPHA) else softwareFade
    val name = stream.getDisplayNameOrNull() ?: unknownName
    val detailText = streamRowDetailText(stream, name, useAddonFormatting)
    val sizeLabel = if (showSize) {
        facts?.size?.takeIf { it > 0 }?.let { formatStreamSizeLabel(it, gbTemplate, mbTemplate) }
    } else {
        null
    }
    val kind = if (useAddonFormatting) null else streamKindLabel(stream.streamData)
    val languageLine = facts?.let { streamRowLanguageLine(streamRowLanguages(it.languages), kind) }
    val cached = !useAddonFormatting && stream.streamData?.cached == true
    var focused by remember { mutableStateOf(false) }
    val longPress = rememberMarqueeLongPress()
    val shape = RoundedCornerShape(tokens.rowRadius)
    // tv-material centres the stroke on the outline; half the width inwards keeps the whole
    // ring inside the row (the mockup's inset ring), so the list never clips it.
    val ring = Border(BorderStroke(marquee.ringWidth, marquee.ring), inset = -(marquee.ringWidth / 2), shape = shape)

    Card(
        onClick = { longPress.click(onClick) },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(longPress.modifier(onLongPress)),
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = tokens.rowFill,
            focusedContainerColor = marquee.glassFill,
            pressedContainerColor = marquee.glassFill
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = ring, pressedBorder = ring),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = tokens.rowPaddingHorizontal,
                    top = tokens.rowPaddingTop,
                    end = tokens.rowPaddingHorizontal,
                    bottom = tokens.rowPaddingBottom
                ),
            horizontalArrangement = Arrangement.spacedBy(tokens.rowColumnGap),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(tokens.rowLineGap)
            ) {
                if (facts != null) {
                    MarqueeSourceBadges(
                        facts = facts,
                        fusionBadges = stream.badges,
                        gap = tokens.badgeGap,
                        modifier = fade
                    )
                }
                Text(
                    // One line, as the mockup's nowrap name: "Torrentio\n4k DV" reads "Torrentio 4k DV".
                    text = name.oneLine(),
                    style = styles.rowTitle,
                    color = NuvioPrimitives.white,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = fade
                )
                if (detailText != null) {
                    Text(
                        text = detailText,
                        style = if (useAddonFormatting) styles.addonText else styles.caption,
                        color = if (focused) marquee.onImage2 else marquee.onImage3,
                        maxLines = if (useAddonFormatting) ADDON_TEXT_MAX_LINES else DETAIL_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = fade
                    )
                }
                if (failureMark != null) FailureMarkLine(failureMark)
                if (verdict != null) DecodeLine(verdict, decodeFacts)
            }
            // The width cap sits on the two open-ended lines (addon name, languages), not the column:
            // the over-limit label is bounded copy ("Over 1080p · 109 Mbps") wider than the cap
            // (Spec 58 L6, 12/S67 N-9: a column cap wrapped its "Mbps" out of view).
            Column(
                modifier = Modifier.padding(top = tokens.rowSideTop).then(fade),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(tokens.sideGap)
            ) {
                if (marker != null) SourceRowMarkerLabel(marker, accent)
                Text(
                    text = stream.streamData?.upstream ?: stream.addonName,
                    style = styles.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = marquee.onImage2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = tokens.rowSideMaxWidth)
                )
                if (sizeLabel != null) {
                    Text(text = sizeLabel, style = styles.caption, color = marquee.onImage3, maxLines = 1)
                }
                if (overLimitMbps != null || overResolutionCap != null) {
                    OverLimitLabel(overLimitMbps, overResolutionCap)
                } else if (languageLine != null || cached) {
                    Row(
                        modifier = Modifier.widthIn(max = tokens.rowSideMaxWidth),
                        horizontalArrangement = Arrangement.spacedBy(tokens.sideGap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (languageLine != null) {
                            Text(
                                text = languageLine,
                                style = styles.sideLabel,
                                color = marquee.onImage2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        // "EN · ⚡", but "EN · PM ⚡": the kind already separates the bolt (mockup stage 2).
                        if (languageLine != null && cached && kind == null) {
                            Text(text = SIDE_SEPARATOR, style = styles.sideLabel, color = marquee.onImage2)
                        }
                        if (cached) {
                            Icon(
                                imageVector = Icons.Filled.FlashOn,
                                contentDescription = stringResource(R.string.stream_cached),
                                tint = tokens.cachedTint,
                                modifier = Modifier.size(tokens.cachedIconSize)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * What this device does to the stream (Spec 65 P8, sandbox `spec-65-stream-filters` stage 11): amber
 * behind a warning icon for a software decode ("Software AV1 decode at 4K: ..."), grey behind an
 * info icon for Dolby Vision on a TV without it.
 */
@Composable
private fun DecodeLine(verdict: DecodeVerdict, facts: ReleaseFacts) {
    val marquee = NuvioTheme.marquee
    val tokens = marquee.stream
    val software = verdict == DecodeVerdict.SOFTWARE
    val color = if (software) tokens.limitWarn else marquee.onImage2
    val text = when (verdict) {
        DecodeVerdict.SOFTWARE ->
            stringResource(R.string.stream_decode_software, facts.encode.label, resolutionLabel(facts.resolution))
        DecodeVerdict.REDUCED -> stringResource(R.string.stream_decode_reduced)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(tokens.decodeLineIconGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (software) Icons.Filled.Warning else Icons.Filled.Info,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(tokens.decodeLineIconSize)
        )
        Text(
            text = text,
            style = NuvioTheme.textStyles.panelHelper.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Spec 75 S6 (`.p75ln`): a history icon, the reason in red, then "· 2 min ago" muted. */
@Composable
private fun FailureMarkLine(mark: SourceFailureMark) {
    val style = NuvioTheme.textStyles.caption.copy(
        fontSize = FAILURE_MARK_TEXT_SIZE,
        lineHeight = FAILURE_MARK_LINE_HEIGHT,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(FAILURE_MARK_GAP),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.History,
            contentDescription = null,
            tint = NuvioPrimitives.errorOnImage,
            modifier = Modifier.size(FAILURE_MARK_ICON_SIZE)
        )
        Text(
            text = mark.reason,
            style = style,
            color = NuvioPrimitives.errorOnImage,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(
            text = stringResource(R.string.failure_mark_age, mark.age),
            style = style,
            color = NuvioTheme.marquee.onImage3,
            maxLines = 1
        )
    }
}

/**
 * Why a stream is hidden (sandbox `spec-51-network-stream-limit` `.s51mb`, Spec 58 stage b-mixed), in
 * amber behind a warning icon: "109 Mbps", "Over 1080p", or "Over 1080p · 109 Mbps".
 */
@Composable
private fun OverLimitLabel(mbps: Int?, cap: ReleaseResolution?) {
    val tokens = NuvioTheme.marquee.stream
    val bitrate = mbps?.let { stringResource(R.string.this_tv_mbps, it) }
    val resolution = cap?.let { stringResource(R.string.stream_limit_over_resolution, resolutionLabel(it)) }
    val text = if (bitrate != null && resolution != null) {
        stringResource(R.string.stream_limit_row_both, resolution, bitrate)
    } else {
        resolution ?: bitrate.orEmpty()
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(tokens.limitRowIconGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = tokens.limitWarn,
            modifier = Modifier.size(tokens.limitRowIconSize)
        )
        Text(
            text = text,
            style = NuvioTheme.textStyles.sideLabel.copy(letterSpacing = 0.sp),
            color = tokens.limitWarn,
            maxLines = 1
        )
    }
}

/** The accent label above a row's addon name. */
internal enum class SourceRowMarker { LAST_PLAYED, PLAYING, PREFERRED }

/**
 * The marker of the row at [index] (Spec 65 D16): the panel's [marker] on its [markedIndex] row,
 * and "Preferred" on the first row while the view leads with the show's preferred source
 * ([preferredCount] > 0). On one row Preferred wins over Last played; the player's Playing wins
 * over Preferred (it says what is on screen now).
 */
internal fun sourceRowMarker(
    index: Int,
    marker: SourceRowMarker?,
    markedIndex: Int,
    preferredCount: Int
): SourceRowMarker? {
    val marked = marker.takeIf { index == markedIndex }
    return when {
        marked == SourceRowMarker.PLAYING -> marked
        index == 0 && preferredCount > 0 -> SourceRowMarker.PREFERRED
        else -> marked
    }
}

@Composable
private fun SourceRowMarkerLabel(marker: SourceRowMarker, accent: () -> Color) {
    val tokens = NuvioTheme.marquee.stream
    val color = accent()
    val label = when (marker) {
        SourceRowMarker.LAST_PLAYED -> stringResource(R.string.stream_last_played)
        SourceRowMarker.PLAYING -> stringResource(R.string.player_now_playing)
        SourceRowMarker.PREFERRED -> stringResource(R.string.stream_preferred)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(tokens.markerIconGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (marker == SourceRowMarker.PLAYING) {
            val iconSize = tokens.markerIconSize
            Icon(
                painter = rememberRawSvgPainter(R.raw.ic_player_play, iconSize),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(iconSize)
            )
        }
        Text(text = label.uppercase(), style = NuvioTheme.textStyles.lastPlayed, color = color, maxLines = 1)
    }
}

private const val SIDE_SEPARATOR = "·"

/**
 * The text under the name. Badges mode (QU5-6): the file name (AIOStreams `streamData`, then the
 * Stremio hint, then the resolver's), which names the release the badges summarise, else the
 * addon's description, on one flowing line as the mockup's `.ds`. [useAddonFormatting]: the
 * addon's description as written, inner line breaks kept (outer blank lines trimmed). A
 * description that only repeats [name], or nothing at all, gives null.
 */
internal fun streamRowDetailText(stream: Stream, name: String, useAddonFormatting: Boolean): String? {
    val description = stream.getDisplayDescription()?.trim()?.takeIf { it.isNotEmpty() && it != name.trim() }
    if (useAddonFormatting) return description
    val resolve = stream.clientResolve
    val fileName = listOf(
        stream.streamData?.filename,
        stream.behaviorHints?.filename,
        resolve?.filename,
        resolve?.stream?.raw?.filename
    ).firstOrNull { !it.isNullOrBlank() }
    return (fileName ?: description)?.oneLine()
}

/** Line breaks and the blank space around them read as one space (the mockup's nowrap / flow text). */
private fun String.oneLine(): String = lines().map(String::trim).filter(String::isNotEmpty).joinToString(" ")

/** The side column's languages (P7): "EN · NL", "MULTI"; null when the release names none. */
internal fun streamRowLanguages(languages: List<String>): String? =
    languages.takeIf { it.isNotEmpty() }?.joinToString(" $SIDE_SEPARATOR ")

/** The languages line with the stream kind last (Spec 42 D6): "EN · PM", "MULTI · WEB", "PM"; null when neither. */
internal fun streamRowLanguageLine(languages: String?, kind: String?): String? =
    listOfNotNull(languages, kind).takeIf { it.isNotEmpty() }?.joinToString(" $SIDE_SEPARATOR ")

/**
 * The kind of an AIOStreams stream (Spec 42 D6): the debrid service's short code (AIOStreams
 * v2.36.0 constants.ts shortName; "DEBRID" for a service not listed), WEB for http, P2P for
 * torrents, null for anything else or another addon's stream.
 */
internal fun streamKindLabel(data: AioStreamData?): String? = when (data?.type) {
    "debrid" -> DEBRID_SHORT_NAMES[data.serviceId] ?: "DEBRID"
    "http" -> "WEB"
    "p2p" -> "P2P"
    else -> null
}

private val DEBRID_SHORT_NAMES = mapOf(
    "premiumize" to "PM",
    "realdebrid" to "RD",
    "alldebrid" to "AD",
    "torbox" to "TB",
    "debridlink" to "DL"
)
