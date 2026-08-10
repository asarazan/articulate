package net.sarazan.articulate.core.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Direct unit tests of the S2-S4 escape/quote/whitespace pipeline, isolated
 * from XML parsing. Each case here is transcribed from a verified experiment
 * in `docs/CONVERSIONS.md` -- the corpus (`core/src/test/corpus`) exercises
 * the same rules end to end through real `strings.xml` files; this suite
 * pins the pipeline's own logic independent of that.
 */
class AndroidTextPipelineTest {

    private val pos = XmlPosition("test.xml", 1, 1)

    private fun process(raw: String): String = AndroidTextPipeline.process(raw, pos, "k")

    private fun processSpanned(raw: String, boundaries: List<Int>): String =
        AndroidTextPipeline.process(raw, pos, "k", hasRealSpan = true, spanBoundaries = boundaries)

    @Test
    fun `E5 - escape processing happens after edge trimming`() {
        assertEquals("qz", process("\\q\\z\\ "))
    }

    @Test
    fun `E1 - hash at question escapes`() {
        assertEquals("#@?", process("\\#\\@\\?"))
    }

    @Test
    fun `E1 - unknown escape drops the backslash`() {
        assertEquals("qz", process("\\q\\z"))
    }

    @Test
    fun `E3 - trailing lone backslash is dropped`() {
        assertEquals("abc", process("abc\\"))
    }

    @Test
    fun `escape carriage return is rejected`() {
        assertThrows(ConversionException::class.java) { process("carriage\\rreturn") }
    }

    @Test
    fun `E2 - bad unicode escape is rejected`() {
        assertThrows(ConversionException::class.java) { process("\\u12") }
    }

    @Test
    fun `E2 - good unicode escape resolves the code point`() {
        assertEquals("\u00E9", process("\\u00E9"))
    }

    @Test
    fun `A1 - bare apostrophe outside quoting is an error`() {
        assertThrows(ConversionException::class.java) { process("don't") }
    }

    @Test
    fun `quoted apostrophe is fine`() {
        assertEquals("don't", process("\"don't\""))
    }

    @Test
    fun `Q1 - mid-string quoted region preserves whitespace`() {
        // Even number of quotes: legal, no odd-count error.
        assertEquals("a  b  c", process("a\"  b  \"c"))
    }

    @Test
    fun `error-odd-quote-count - single stray quote is rejected by Articulate`() {
        // AAPT2 itself accepts this (a single stray quote silently enables
        // quoting for the rest of the string -- see docs/CONVERSIONS.md Q1);
        // Articulate treats it as an authoring mistake and rejects it instead.
        assertThrows(ConversionException::class.java) { process("say \"hello world") }
    }

    @Test
    fun `W1 - whitespace collapses to a single space`() {
        assertEquals("hello world", process("   hello   world   "))
    }

    @Test
    fun `W3 - non-ASCII whitespace is not collapsed`() {
        assertEquals("x\u0020\u2008\u2003y", process("x\u0020\u2008\u2003y"))
    }

    @Test
    fun `K4 - unescaped leading at is rejected`() {
        assertThrows(ConversionException::class.java) { process("@string/other") }
    }

    @Test
    fun `K4 - escaped leading at is a literal string`() {
        assertEquals("@string/other", process("\\@string/other"))
    }

    @Test
    fun `K4 - unescaped leading question is rejected`() {
        assertThrows(ConversionException::class.java) { process("?attr/foo") }
    }

    @Test
    fun `K4 - lookalikes that are not at or question are fine`() {
        assertEquals("#FF0000", process("#FF0000"))
        assertEquals("42", process("42"))
        assertEquals("true", process("true"))
    }

    @Test
    fun `E4 - escapes still process inside quotes`() {
        assertEquals("a\nb  c", process("\"a\\nb  c\""))
    }

    /**
     * M2 (`docs/CONVERSIONS.md`, PLAN.md D4/D7, implemented 2026-08-10):
     * `This <b> is </b> spaced` flattens to the raw text "This  is  spaced"
     * with span boundaries at offsets 5 and 9 (bracketing the tag's own
     * " is " text). AAPT2's `ResetTextState()` fires at both, so the spaces
     * immediately on either side of each boundary are never merged with
     * each other -- producing a double space on both sides of the stripped
     * tag, not the single space W1 would otherwise collapse to.
     */
    @Test
    fun `M2 - span boundaries reset whitespace collapse, producing a double space`() {
        assertEquals("This  is  spaced", processSpanned("This  is  spaced", listOf(5, 9)))
    }

    /**
     * Q2: `"x  <b>y</b>  z"` flattens to raw text `"x  y  z"` (length 9)
     * with span boundaries at offsets 4 and 5. The opening quote (index 0)
     * preserves the two spaces before the boundary; the boundary then resets
     * quoting, so the two spaces after collapse to one -- "a quoted region
     * cannot span an inline tag".
     */
    @Test
    fun `Q2 - quoting state resets at a span boundary`() {
        assertEquals("x  y z", processSpanned("\"x  y  z\"", listOf(4, 5)))
    }

    /**
     * W2: edge trimming (S2) is skipped for the *whole* string once any real
     * span was seen, not merely suppressed around the span itself --
     * `  <b>x</b>  y  ` keeps both a leading and a trailing single space
     * (collapsed by W1, but not removed by S2).
     */
    @Test
    fun `W2 - edge trim is suppressed for the whole string when a real span is present`() {
        assertEquals(" x y ", processSpanned("  x  y  ", listOf(2, 3)))
    }

    /**
     * Negative control for W2: the same raw text with [hasRealSpan] left at
     * its default `false` (no span, ordinary [process]) must still trim,
     * proving the suppression above is genuinely conditional on the span
     * flag rather than something that stopped trimming unconditionally.
     */
    @Test
    fun `W2 control - the same text trims normally with no span present`() {
        assertEquals("x y", process("  x  y  "))
    }

    /**
     * PR #23 audit (2026-08-10): the boundary-drain loop in [AndroidTextPipeline]
     * compared `spanBoundaries[boundaryIndex] == i`, but [i] advances by more
     * than one inside the escape branch (2 for `\n`, 6 for `\uXXXX`). A
     * boundary offset landing inside an escape was skipped over and never
     * equalled [i] again, wedging [boundaryIndex] there forever -- every
     * later boundary reset in the string was silently disabled.
     *
     * This reproduces `a\<b>n</b>x <i> y </i> z` (flattened by [ContentFlattener]
     * to raw text `a\nx  y  z` with a literal backslash+n, NOT an escape, at
     * indices 1-2 -- the escape only exists once [AndroidTextPipeline] walks
     * the flattened stream). Byte-for-byte, raw text and boundaries are:
     *
     * index:  0    1    2    3    4    5    6    7    8    9
     * char:   a    \    n    x    ' '  ' '  y    ' '  ' '  z
     *
     * boundaries = [2, 3] (the stripped `<b>`, bracketing "n") and [5, 8]
     * (the stripped `<i>`, bracketing " y ").
     *
     * Walking it: `\n` at indices 1-2 is a real escape here (backslash
     * followed by 'n'), consuming both and emitting an actual newline
     * (0x0A) -- so [i] jumps from 1 to 3, stepping over boundary offset 2
     * without ever equaling it. With the old `==` check this wedged
     * [boundaryIndex] at the `<b>` boundaries forever, silently disabling
     * the `<i>` boundaries at 5 and 8 too -- collapsing " y " to a single
     * space on both sides instead of preserving the double space M2 requires.
     *
     * This is a direct unit-level reproduction of the real aapt2 2.20
     * divergence recorded in the PR #23 audit; per the audit, the fix does
     * NOT make the head of this input match aapt2 -- aapt2 processes escapes
     * per-chunk (never crossing a stripped tag boundary), so real aapt2
     * treats the lone trailing backslash before `<b>` as E3 (dropped) rather
     * than combining it with `<b>`'s content into a `\n` escape. That
     * divergence is a separate, pre-existing, out-of-scope bug (per-chunk
     * escaping is not implemented here at all). This test asserts only what
     * the boundary-drain fix owns: that the `<i>` double-spaces survive an
     * earlier boundary landing inside an escape, not full aapt2 parity for
     * this input.
     */
    @Test
    fun `boundary index bug - an escape-adjacent boundary does not wedge later resets`() {
        assertEquals("a\nx  y  z", processSpanned("a\\nx  y  z", listOf(2, 3, 5, 8)))
    }
}
