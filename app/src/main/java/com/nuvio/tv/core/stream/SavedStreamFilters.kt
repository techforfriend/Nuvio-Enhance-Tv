package com.nuvio.tv.core.stream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Saved filters and the Sources row's pins (Spec 65 P2, D12-D14): a saved filter is a named set of
 * filter values; a pin puts a saved filter or an addon's view in the row as a chip. Per profile,
 * kept by SavedStreamFiltersDataStore. Pure values: every change returns a new instance.
 */

/** A set of filter values under a [name]; [id] is what a pin and the phone page refer to. */
@Serializable
data class SavedStreamFilter(
    val id: String,
    val name: String,
    val values: Map<FacetGroup, Set<String>>
) {
    val filter: FacetFilter get() = FacetFilter(values)
}

/** One row of the phone page's list as it comes back: the saved filter [id] under the [name] given there. */
@Serializable
data class SavedFilterEdit(val id: String, val name: String = "")

/**
 * A saved filter's name as the app keeps it, whoever typed it (the phone page's names are untrusted):
 * control characters and runs of whitespace as one space, trimmed, [SavedStreamFilters.MAX_NAME_LENGTH]
 * characters at most; null when nothing is left.
 */
fun savedFilterName(raw: String): String? {
    val spaced = raw.map { if (it.isWhitespace() || it.isISOControl()) ' ' else it }.joinToString("")
    val words = spaced.split(' ').filter { it.isNotEmpty() }
    val cut = words.joinToString(" ").take(SavedStreamFilters.MAX_NAME_LENGTH)
    // A cut between the two halves of one character (an emoji) drops the half left behind.
    val whole = if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut
    return whole.trimEnd().takeIf { it.isNotEmpty() }
}

/** A chip pinned to the Sources row. */
@Serializable
sealed interface StreamPin {
    /** The saved filter [id]. */
    @Serializable
    @SerialName("filter")
    data class Filter(val id: String) : StreamPin

    /** The view of the addon called [name]. */
    @Serializable
    @SerialName("addon")
    data class Addon(val name: String) : StreamPin
}

/** A profile's saved filters in the sheet's order and its pins in the row's order. */
@Serializable
data class SavedStreamFilters(
    val filters: List<SavedStreamFilter> = emptyList(),
    val pins: List<StreamPin> = emptyList()
) {
    /** The saved filter holding exactly [filter]'s values; null when none does or nothing is on. */
    fun matching(filter: FacetFilter): SavedStreamFilter? {
        val on = filter.valuesOn()
        return if (on.isEmpty()) null else filters.firstOrNull { it.values == on }
    }

    /** Whether [filter] can be saved: values are on, no saved filter holds them, and there is room. */
    fun canSave(filter: FacetFilter): Boolean =
        !filter.isEmpty && matching(filter) == null && filters.size < MAX_FILTERS

    /** These with [filter] saved last as [name] under [id]; unchanged when it [canSave] not. */
    fun withSaved(id: String, name: String, filter: FacetFilter): SavedStreamFilters =
        if (canSave(filter)) copy(filters = filters + SavedStreamFilter(id, name, filter.valuesOn())) else this

    fun isPinned(pin: StreamPin): Boolean = pin in pins

    /**
     * These with [pin] removed, or added last. Unchanged when it would be one pin over [MAX_PINS],
     * or names a saved filter that is not there.
     */
    fun withPinToggled(pin: StreamPin): SavedStreamFilters = when {
        pin in pins -> copy(pins = pins - pin)
        pins.size >= MAX_PINS -> this
        pin is StreamPin.Filter && filters.none { it.id == pin.id } -> this
        else -> copy(pins = pins + pin)
    }

    /**
     * These as the phone page left them (D14). The filters [order] names come first, in its order
     * and under its names (a name [savedFilterName] refuses keeps the stored one); the [deleted]
     * ones are gone, their pins with them; a filter neither names (saved on the TV after the page
     * loaded) follows in its stored place. An id that is not saved here is ignored, so the page
     * can neither add a filter nor change one's values.
     */
    fun edited(order: List<SavedFilterEdit>, deleted: Set<String>): SavedStreamFilters {
        val stored = filters.associateBy { it.id }
        val listed = order.distinctBy { it.id }.mapNotNull { edit ->
            val filter = stored[edit.id]?.takeUnless { it.id in deleted } ?: return@mapNotNull null
            savedFilterName(edit.name)?.let { filter.copy(name = it) } ?: filter
        }
        val listedIds = listed.mapTo(HashSet()) { it.id }
        val next = listed + filters.filter { it.id !in listedIds && it.id !in deleted }
        if (next == filters) return this
        return copy(filters = next, pins = pins.filterNot { it is StreamPin.Filter && it.id in deleted })
    }

    /**
     * These as the app accepts them from disk: a filter needs an id no earlier one has, a name and
     * a value; a pin is listed once and its saved filter exists; both lists within their caps.
     */
    fun normalized(): SavedStreamFilters {
        val kept = filters
            .map { it.copy(values = it.values.filterValues { keys -> keys.isNotEmpty() }) }
            .filter { it.id.isNotBlank() && it.name.isNotBlank() && it.values.isNotEmpty() }
            .distinctBy { it.id }
            .take(MAX_FILTERS)
        val ids = kept.mapTo(HashSet()) { it.id }
        val keptPins = pins.distinct()
            .filter { pin ->
                when (pin) {
                    is StreamPin.Filter -> pin.id in ids
                    is StreamPin.Addon -> pin.name.isNotBlank()
                }
            }
            .take(MAX_PINS)
        return if (kept == filters && keptPins == pins) this else SavedStreamFilters(kept, keptPins)
    }

    companion object {
        /** Saved filters a profile keeps: the sheet lists them all, so the list stays short. */
        const val MAX_FILTERS = 20

        /** Pins a profile keeps: more would never be reached in a row one panel wide. */
        const val MAX_PINS = 20

        /** A saved filter's name: room for a default of several values ("4K · Dolby Vision · Atmos · Remux"). */
        const val MAX_NAME_LENGTH = 60

        val NONE = SavedStreamFilters()
    }
}

/** The groups with a value on: what a saved filter stores and is compared by. */
private fun FacetFilter.valuesOn(): Map<FacetGroup, Set<String>> = on.filterValues { it.isNotEmpty() }
