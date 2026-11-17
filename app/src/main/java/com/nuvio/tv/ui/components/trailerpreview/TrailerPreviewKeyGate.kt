package com.nuvio.tv.ui.components.trailerpreview

/**
 * Pairs each KeyDown the preview acted on with its repeats and KeyUp (Spec 96 PD6), so a consumed press never
 * leaves an orphan KeyUp below (MainActivity swallows its own consumed ups for the same reason). Codes, not
 * Android types, so it runs in unit tests.
 */
class TrailerPreviewKeyGate {
    enum class Held {
        /** KeyDown consumed: consume the repeats and the KeyUp. */
        CONSUMED,
        /** Stage 2 OK: consume the repeats; the KeyUp opens Details. */
        OPEN_ON_UP
    }

    private val held = HashMap<Int, Held>()

    /**
     * Before deciding a KeyDown, true = consume it (a repeat of a held press). A fresh press (repeat 0) forgets
     * any stale entry for its code (an up lost to a pause). False = ask [TrailerPreviewMachine.onKeyDown].
     */
    fun onKeyDown(code: Int, repeatCount: Int): Boolean {
        if (repeatCount == 0) {
            held.remove(code)
            return false
        }
        return code in held
    }

    fun hold(code: Int, how: Held) {
        held[code] = how
    }

    /** The KeyUp's entry, removed; null = not the preview's key. */
    fun onKeyUp(code: Int): Held? = held.remove(code)
}
