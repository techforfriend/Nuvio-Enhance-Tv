package com.nuvio.tv.domain.model

/**
 * Layout › Home "Autoplay trailer" (Spec 96 D7): one choice, so only one trailer ever plays.
 * [HERO] plays behind the Marquee hero (the pre-Spec 96 switch on); [CARD] plays in the focused card,
 * then a large panel (Spec 96 D1).
 */
enum class TrailerAutoplayMode {
    OFF, HERO, CARD;

    companion object {
        /** Stored as two booleans (no migration): the old switch and Spec 96's card flag. */
        fun of(enabled: Boolean, card: Boolean): TrailerAutoplayMode = when {
            !enabled -> OFF
            card -> CARD
            else -> HERO
        }
    }
}

/** Layout › Home "Preview delay" (Spec 96 D12): the In the card wait, focus -> card and card -> large panel. */
object TrailerPreviewDelay {
    const val MIN_S = 1
    const val MAX_S = 10
    const val DEFAULT_S = 3
}
