package com.nuvio.tv.core.player

import java.awt.Font
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 98 (issue #2): the libass fallback fonts must carry the family name ass-kt passes as
 * libass's default family ("sans-serif", AssKt.c nativeAssRenderInit) and cover Latin with every
 * Vietnamese letter, Greek and Cyrillic, or libass draws boxes when fontconfig finds no covering system font.
 */
class LibassFallbackFontTest {

    private val rawDir = File("src/main/res/raw")

    private val vietnamese =
        "aàáảãạăằắẳẵặâầấẩẫậeèéẻẽẹêềếểễệiìíỉĩịoòóỏõọôồốổỗộơờớởỡợuùúủũụưừứửữựyỳýỷỹỵđ"

    // Spec 98 D7: monotonic Greek (Open Sans has no polytonic) and Cyrillic (Russian, Ukrainian, Serbian).
    private val greekCyrillic = "αβγδεζηθικλμνξοπρσςτυφχψωάέήίόύώϊϋ" +
        "абвгдежзийклмнопрстуфхцчшщъыьэюяёєіїґђјљњћџ"

    @Test
    fun `each fallback font is family sans-serif and covers Vietnamese Greek and Cyrillic in both cases`() {
        val letters = (vietnamese + greekCyrillic).let { it + it.uppercase() }
        for ((name, _) in LIBASS_FALLBACK_FONTS) {
            val font = Font.createFont(Font.TRUETYPE_FONT, File(rawDir, name))
            assertEquals(name, "sans-serif", font.getFamily(java.util.Locale.ROOT))
            val missing = letters.filterNot { font.canDisplay(it) }
            assertTrue("$name lacks: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `the bold file is weight 700 and the regular file weight 400`() {
        val weights = LIBASS_FALLBACK_FONTS.associate { (name, _) -> name to usWeightClass(File(rawDir, name)) }
        assertEquals(mapOf("libass_fallback_regular.ttf" to 400, "libass_fallback_bold.ttf" to 700), weights)
    }

    /** OS/2 usWeightClass (uint16 at table offset 4), which libass matches \b / Bold against. */
    private fun usWeightClass(file: File): Int {
        val b = file.readBytes()
        fun u16(o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong()
        for (i in 0 until u16(4)) {
            val rec = 12 + i * 16
            if (String(b, rec, 4, Charsets.US_ASCII) == "OS/2") return u16(u32(rec + 8).toInt() + 4)
        }
        error("${file.name} has no OS/2 table")
    }

    @Test
    fun `both fonts are handed over with their bytes`() {
        val added = mutableListOf<Pair<String, Byte>>()

        addLibassFallbackFonts(
            readFont = { resId -> byteArrayOf(resId.toByte()) },
            addFont = { name, data -> added += name to data.single() }
        )

        assertEquals(LIBASS_FALLBACK_FONTS.map { (name, resId) -> name to resId.toByte() }, added)
    }

    @Test
    fun `an unreadable font is skipped and the other still added`() {
        val added = mutableListOf<String>()
        val badId = LIBASS_FALLBACK_FONTS.first().second

        addLibassFallbackFonts(
            readFont = { resId -> if (resId == badId) throw java.io.IOException("gone") else byteArrayOf(1) },
            addFont = { name, _ -> added += name }
        )

        assertEquals(listOf(LIBASS_FALLBACK_FONTS.last().first), added)
    }
}
