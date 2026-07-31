package net.sarazan.articulate.core.serialize

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `map insertion order does not affect output bytes`() {
        assertArrayEquals(
            XcstringsWriter.writeBytes(TestCatalogs.sample()),
            XcstringsWriter.writeBytes(TestCatalogs.sampleShuffled()),
        )
    }

    @Test
    fun `output bytes are identical under a Turkish default locale`() {
        // The classic trap: no-arg toUpperCase()/toLowerCase() and some formatting
        // calls consult Locale.getDefault(). MEMBER_ORDER uses naturalOrder(),
        // i.e. code-unit comparison, which should never be locale-sensitive -- this
        // test exists to catch a future change that accidentally introduces one.
        val expected = XcstringsWriter.writeBytes(TestCatalogs.sample())
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))
        try {
            assertArrayEquals(expected, XcstringsWriter.writeBytes(TestCatalogs.sample()))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `output ends at the closing brace with no trailing newline`() {
        // Verified 2026-07-31 against Apple's own `xcstringstool sync`, which
        // rewrites a catalog ending at the final `}` with no trailing 0x0a.
        // This was previously asserted the other way round and was wrong: one
        // extra byte makes every generated catalog differ from what Xcode
        // writes, so Xcode rewrites on open and the drift gate fights it forever.
        val bytes = XcstringsWriter.writeBytes(TestCatalogs.sample())
        assertEquals('}'.code.toByte(), bytes.last())
        assertFalse(
            String(bytes, CanonicalFormat.CHARSET).endsWith("\n"),
            "Xcode does not terminate .xcstrings with a newline",
        )
    }

    @Test
    fun `output uses LF only, never CRLF`() {
        val text = String(XcstringsWriter.writeBytes(TestCatalogs.sample()), CanonicalFormat.CHARSET)
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
