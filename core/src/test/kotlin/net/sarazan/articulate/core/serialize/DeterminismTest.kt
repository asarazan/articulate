package net.sarazan.articulate.core.serialize

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The CI no-op check (regenerate, diff against committed file, fail on drift)
 * only works if output depends on nothing but the model's logical content --
 * not map insertion order, not the JVM's default locale, not wall-clock time.
 * Each test here isolates one such variable and holds everything else constant.
 */
class DeterminismTest {

    @Test
    fun `serializing the same catalog twice produces identical bytes`() {
        val catalog = TestCatalogs.sample()
        assertArrayEquals(XcstringsWriter.writeBytes(catalog), XcstringsWriter.writeBytes(catalog))
    }

    @Test
    fun `map insertion order does not affect output`() {
        assertEquals(
            XcstringsWriter.write(TestCatalogs.sample()),
            XcstringsWriter.write(TestCatalogs.sampleShuffled()),
        )
    }

    @Test
    fun `output is identical under a Turkish default locale`() {
        // The classic trap: no-arg toUpperCase()/toLowerCase() and some formatting
        // calls consult Locale.getDefault(). MEMBER_ORDER uses naturalOrder(),
        // i.e. code-unit comparison, which should never be locale-sensitive -- this
        // test exists to catch a future change that accidentally introduces one.
        val expected = XcstringsWriter.write(TestCatalogs.sample())
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))
        try {
            assertEquals(expected, XcstringsWriter.write(TestCatalogs.sample()))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `output ends with exactly one trailing newline`() {
        val text = XcstringsWriter.write(TestCatalogs.sample())
        assertFalse(text.endsWith("\n\n"))
        assertTrue(text.endsWith("}\n"))
    }

    @Test
    fun `output uses LF only, never CRLF`() {
        val text = String(XcstringsWriter.writeBytes(TestCatalogs.sample()), Charsets.UTF_8)
        assertFalse(text.contains("\r"))
    }

    @Test
    fun `output has no UTF-8 BOM`() {
        val bytes = XcstringsWriter.writeBytes(TestCatalogs.sample())
        val hasBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        assertFalse(hasBom)
    }
}
