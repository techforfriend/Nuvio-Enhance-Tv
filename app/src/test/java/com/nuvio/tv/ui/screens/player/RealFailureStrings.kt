package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R

/**
 * The real res/values templates, formatted with the arguments given, so a wrong argument type or a
 * missing argument fails in a plain unit test. Plurals take "one" for 1, else "other" (English).
 */
object RealFailureStrings : FailureStrings {

    private val xml: String by lazy { java.io.File("src/main/res/values/strings.xml").readText() }

    private fun unescape(text: String) = text.replace("\\'", "'").replace("\\n", "\n")

    private val templates: Map<Int, String> by lazy {
        val byName = Regex("""<string name="(\w+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        R.string::class.java.fields.mapNotNull { field -> byName[field.name]?.let { field.getInt(null) to it } }.toMap()
    }

    private val plurals: Map<Int, Map<String, String>> by lazy {
        val item = Regex("""<item quantity="(\w+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
        val byName = Regex("""<plurals name="(\w+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { m ->
                m.groupValues[1] to item.findAll(m.groupValues[2]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            }
        R.plurals::class.java.fields.mapNotNull { field -> byName[field.name]?.let { field.getInt(null) to it } }.toMap()
    }

    override fun get(id: Int, vararg args: Any): String {
        val template = templates.getValue(id)
        return if (args.isEmpty()) template else String.format(template, *args)
    }

    override fun plural(id: Int, count: Int, vararg args: Any): String {
        val forms = plurals.getValue(id)
        val template = forms.getValue(if (count == 1) "one" else "other")
        return String.format(template, *args)
    }
}
