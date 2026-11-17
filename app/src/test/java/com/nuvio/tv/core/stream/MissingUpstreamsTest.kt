package com.nuvio.tv.core.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 42 P6 / D7: the missing-upstreams phrasing for the panel foot (2 names) and the failed-fetch message (3). */
class MissingUpstreamsTest {

    private val templates = MissingUpstreamsTemplates(
        one = "%1\$s didn't answer",
        list = "%1\$s and %2\$s didn't answer",
        more = "%1\$s and %2\$d more didn't answer"
    )

    private fun text(vararg names: String, max: Int = 2) = missingUpstreamsText(names.toList(), max, templates)

    @Test
    fun `no names gives nothing`() {
        assertNull(text())
    }

    @Test
    fun `up to the limit every name is listed`() {
        assertEquals("Knaben didn't answer", text("Knaben"))
        assertEquals("ComeTorz and Knaben didn't answer", text("ComeTorz", "Knaben"))
        assertEquals("Torrentio, MediaFusion and Meteor didn't answer", text("Torrentio", "MediaFusion", "Meteor", max = 3))
    }

    @Test
    fun `past the limit the rest is counted`() {
        assertEquals("ComeTorz, Knaben and 1 more didn't answer", text("ComeTorz", "Knaben", "STorz"))
        val ten = listOf("Torrentio", "MediaFusion", "Meteor") + (1..7).map { "Upstream $it" }
        assertEquals(
            "Torrentio, MediaFusion, Meteor and 7 more didn't answer",
            missingUpstreamsText(ten, maxNames = 3, templates = templates)
        )
    }

    @Test
    fun `repeated names count once`() {
        assertEquals("Knaben didn't answer", text("Knaben", "Knaben"))
    }

    // Spec 97 X6: the foot's count while source names are hidden.
    @Test
    fun `the count is of distinct upstreams`() {
        assertEquals(0, missingUpstreamsCount(emptyList()))
        assertEquals(2, missingUpstreamsCount(listOf("A", "A", "B")))
    }
}
