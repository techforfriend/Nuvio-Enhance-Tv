package com.nuvio.tv.domain.model

import java.util.Locale

/**
 * A profile's maximum age rating (Spec 64 D4): the US-board ladder, lowest first, so a cap
 * allows its own tier and every tier below it. [storageKey] is the persisted form; never
 * rename one (a renamed key reads back as junk, which fails closed to [ALL_AGES]).
 */
enum class ContentRatingCap(val storageKey: String, val ratings: Set<String>) {
    ALL_AGES("all_ages", setOf("TV-Y", "TV-G", "G")),
    OLDER_KIDS("older_kids", setOf("TV-Y7", "TV-Y7-FV", "PG", "TV-PG")),
    TEENS("teens", setOf("PG-13", "TV-14")),
    MATURE("mature", setOf("R", "TV-MA")),
    ADULTS_ONLY("adults_only", setOf("NC-17"));

    companion object {
        /** The cap a read failure or an unknown stored value fails closed to (Spec 64 D1). */
        val STRICTEST: ContentRatingCap = ALL_AGES

        /**
         * The tier of a US-board [rating] (trimmed, case-insensitive), or null when it is
         * unknown: absent, `NR`/unrated, or any non-US code (Spec 64 D1, D4).
         */
        fun tierOf(rating: String?): ContentRatingCap? {
            val code = usRatingCode(rating) ?: return null
            return entries.first { code in it.ratings }
        }

        /** [rating] as its US-board code (trimmed, uppercased), or null when [tierOf] would not know it. */
        fun usRatingCode(rating: String?): String? {
            val code = rating?.trim()?.uppercase(Locale.ROOT) ?: return null
            return code.takeIf { entries.any { tier -> code in tier.ratings } }
        }

        /** The cap stored as [key]; null for an absent or unknown key (the store decides what junk means). */
        fun fromStorageKey(key: String?): ContentRatingCap? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * Whether a title rated [rating] may be shown under [cap]. No cap allows everything; under a
 * cap an unknown rating is hidden (fail closed, Spec 64 D1). [rating] must be a US-board value
 * (the addon's `app_extras.certification`): a bare code cannot say which board issued it, and
 * several non-US boards reuse US spellings ("PG", "G").
 */
fun isRatingAllowed(rating: String?, cap: ContentRatingCap?): Boolean {
    if (cap == null) return true
    val tier = ContentRatingCap.tierOf(rating) ?: return false
    return tier <= cap
}
