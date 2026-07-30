package net.sarazan.articulate.core.serialize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Locks the exact canonical output for one representative catalog.
 *
 * This is a regression lock, not independent verification against real Xcode --
 * that is [RoundTripTest]'s job once the observed-Xcode fixture (see
 * `fixtures/xcode/README.md`) lands. Until then "golden" means "what
 * [CanonicalFormat] currently, provisionally, produces" -- what it locks down is
 * sort order (alphabetical at every level, including `de` before `en` despite
 * being inserted in the opposite order in [TestCatalogs.sample]), comma
 * placement, comment omission when absent, and the plural wrapper shape --
 * not the still-provisional formatting constants themselves.
 *
 * Expected output is built from (depth, line) pairs rather than a hand-indented
 * text block, so indentation is `"  ".repeat(depth)` by construction and cannot
 * be mistranscribed by miscounting spaces in a literal.
 */
class GoldenSerializationTest {

    @Test
    fun `sample catalog serializes to expected canonical text`() {
        val expected = expectedLines.joinToString("\n") + "\n"
        assertEquals(expected, XcstringsWriter.write(TestCatalogs.sample()))
    }

    private fun l(depth: Int, content: String) = "  ".repeat(depth) + content

    private val expectedLines = listOf(
        l(0, "{"),
        l(1, "\"sourceLanguage\" : \"en\","),
        l(1, "\"strings\" : {"),
        l(2, "\"app_home_title\" : {"),
        l(3, "\"comment\" : \"Shown at the top of the home screen\","),
        l(3, "\"extractionState\" : \"manual\","),
        l(3, "\"localizations\" : {"),
        l(4, "\"de\" : {"),
        l(5, "\"stringUnit\" : {"),
        l(6, "\"state\" : \"translated\","),
        l(6, "\"value\" : \"Startseite\""),
        l(5, "}"),
        l(4, "},"),
        l(4, "\"en\" : {"),
        l(5, "\"stringUnit\" : {"),
        l(6, "\"state\" : \"translated\","),
        l(6, "\"value\" : \"Home\""),
        l(5, "}"),
        l(4, "}"),
        l(3, "}"),
        l(2, "},"),
        l(2, "\"cart_item_count\" : {"),
        l(3, "\"extractionState\" : \"manual\","),
        l(3, "\"localizations\" : {"),
        l(4, "\"en\" : {"),
        l(5, "\"variations\" : {"),
        l(6, "\"plural\" : {"),
        l(7, "\"one\" : {"),
        l(8, "\"stringUnit\" : {"),
        l(9, "\"state\" : \"translated\","),
        l(9, "\"value\" : \"%1\$lld item\""),
        l(8, "}"),
        l(7, "},"),
        l(7, "\"other\" : {"),
        l(8, "\"stringUnit\" : {"),
        l(9, "\"state\" : \"translated\","),
        l(9, "\"value\" : \"%1\$lld items\""),
        l(8, "}"),
        l(7, "}"),
        l(6, "}"),
        l(5, "}"),
        l(4, "}"),
        l(3, "}"),
        l(2, "}"),
        l(1, "},"),
        l(1, "\"version\" : \"1.0\""),
        l(0, "}"),
    )
}
