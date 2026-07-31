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
}
