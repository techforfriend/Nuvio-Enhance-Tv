package com.nuvio.tv.core.stream

/** How many distinct upstreams failed: the panel foot's count while source names are hidden (Spec 97 X6). */
internal fun missingUpstreamsCount(names: List<String>): Int = names.distinct().size

/** The three phrasings of [missingUpstreamsText]: R.string.stream_upstreams_missing_one / _list / _more. */
internal data class MissingUpstreamsTemplates(val one: String, val list: String, val more: String)

/**
 * Names the upstreams a gateway reported as failed (Spec 42 P6): "A didn't answer", "A and B
 * didn't answer", "A, B and C didn't answer", and past [maxNames] "A, B and 3 more didn't answer".
 * The panel foot names 2, the failed-fetch message 3 (D7). Null when there are no names.
 */
internal fun missingUpstreamsText(
    names: List<String>,
    maxNames: Int,
    templates: MissingUpstreamsTemplates
): String? {
    require(maxNames >= 1) { "maxNames must be at least 1" }
    val distinct = names.distinct()
    return when {
        distinct.isEmpty() -> null
        distinct.size == 1 -> templates.one.format(distinct.single())
        distinct.size <= maxNames -> templates.list.format(distinct.dropLast(1).joinToString(", "), distinct.last())
        else -> templates.more.format(distinct.take(maxNames).joinToString(", "), distinct.size - maxNames)
    }
}
