package net.sarazan.articulate.core.serialize

import net.sarazan.articulate.core.model.Entry
import net.sarazan.articulate.core.model.LocaleTag
import net.sarazan.articulate.core.model.Localization
import net.sarazan.articulate.core.model.StringCatalog
import net.sarazan.articulate.core.model.StringKey
import net.sarazan.articulate.core.model.StringUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Model-level rules the golden test doesn't isolate on their own: comment
 * omission, empty-object shape, and JSON string escaping. These are the rules
 * most likely to regress silently, since they can be right for one input by
 * coincidence and wrong for the next.
 */
class EscapingAndEdgeCasesTest {

    @Test
    fun `entry without a comment omits the comment key entirely`() {
        val catalog = StringCatalog(
            sourceLanguage = "en",
            entries = mapOf(
                StringKey("plain_string") to Entry(
                    localizations = mapOf(LocaleTag("en") to Localization.Simple(StringUnit("x"))),
                ),
            ),
        )
        assertFalse(XcstringsWriter.write(catalog).contains("comment"))
    }

    @Test
    fun `empty catalog renders an empty strings object per the blank-line convention`() {
        val catalog = StringCatalog(sourceLanguage = "en", entries = emptyMap())
        assertTrue(XcstringsWriter.write(catalog).contains("\"strings\" : {\n\n  }"))
    }

    @Test
    fun `quotes and backslashes are escaped`() {
        assertEquals("\"a\\\"b\\\\c\"", renderSingleValue("a\"b\\c"))
    }

    @Test
    fun `control characters with named JSON shorthands are escaped, not written literally`() {
        // \n \t \r \b \f each have a 2-character shorthand in the JSON spec and must
        // use it rather than falling through to the generic \uXXXX path. Kotlin has no
        // \f escape (unlike Java), so form feed is written as \u000C here.
        val input = "a\nb\tc\rd\be\u000Cf"
        assertEquals("\"a\\nb\\tc\\rd\\be\\ff\"", renderSingleValue(input))
    }

    @Test
    fun `control characters without a named shorthand fall back to lowercase 4-digit backslash-u escapes`() {
        // U+0001 (SOH) has no JSON shorthand, so it must hit the generic branch.
        // Also pins CanonicalFormat.LOWERCASE_HEX_ESCAPES: lowercase "a" not "A".
        val input = "x\u0001y"
        assertEquals("\"x\\u0001y\"", renderSingleValue(input))
    }

    @Test
    fun `non-ASCII text is written literally, not backslash-u escaped`() {
        val text = "café 日本語"
        assertEquals("\"$text\"", renderSingleValue(text))
    }

    /**
     * Serializes a one-field catalog and extracts just the rendered value
     * literal, so escaping can be asserted in isolation from surrounding
     * structure. Uses [StringCatalog.sourceLanguage] as the vehicle since it
     * needs no [Entry] wrapper.
     */
    private fun renderSingleValue(raw: String): String {
        val catalog = StringCatalog(sourceLanguage = raw, entries = emptyMap())
        val line = XcstringsWriter.write(catalog).lineSequence().first { it.contains("sourceLanguage") }
        return line.substringAfter(CanonicalFormat.MEMBER_SEPARATOR).removeSuffix(",")
    }
}
