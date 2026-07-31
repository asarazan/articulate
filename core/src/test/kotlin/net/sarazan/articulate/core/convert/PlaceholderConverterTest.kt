package net.sarazan.articulate.core.convert

import net.sarazan.articulate.core.parse.ConversionException
import net.sarazan.articulate.core.parse.XmlPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.util.stream.Stream

/**
 * Row-by-row coverage of the conversion table in `docs/CONVERSIONS.md` P1 (and
 * PLAN.md §2.3).
 *
 * The corpus exercises a handful of these end to end, but the table enumerates
 * fourteen specifier families and most of them -- `%c`, `%C`, `%S`, `%a`, `%A`,
 * `%n`, `%b`, `%B`, `%h`, `%H`, `%tX`, `%(d`, bare width on `%s` -- had no test
 * of any kind. "Anything not explicitly supported is a hard error, never a
 * pass-through guess" (PLAN.md §2 goal) is only true if every row is checked;
 * an unlisted conversion character silently falling through to `%@` would be
 * exactly the shipped-translation bug this project exists to prevent.
 */
class PlaceholderConverterTest {

    private val pos = XmlPosition("test.xml", 1, 1)

    private fun convert(value: String): String = PlaceholderConverter.convert(value, true, pos, "k")

    private fun errorFor(value: String): String =
        runCatching { convert(value) }
            .fold(
                onSuccess = { error("expected '$value' to be rejected, but it converted to '$it'") },
                onFailure = { e ->
                    check(e is ConversionException) { "expected a ConversionException for '$value', got $e" }
                    e.message.orEmpty()
                },
            )

    // ---- rows that convert ----

    @TestFactory
    fun `supported specifiers convert exactly as the P1 table says`(): Stream<DynamicTest> = listOf(
        // Android -> iOS
        "%s" to "%@",
        "%1\$s" to "%1\$@",
        "%d" to "%lld",
        "%1\$d" to "%1\$lld",
        "%f" to "%f",
        "%.2f" to "%.2f",
        "%e" to "%e",
        "%E" to "%E",
        "%x" to "%llx",
        "%X" to "%llX",
        "%o" to "%llo",
        // P4: flags, width and precision are preserved around the inserted `ll`.
        "%#08x" to "%#08llx",
        "%#o" to "%#llo",
        "%08d" to "%08lld",
        "%-8d" to "%-8lld",
        "%1\$08x" to "%1\$08llx",
        // `%%` consumes no argument: it survives verbatim and does not count
        // toward positional discipline (so this is not a two-argument string).
        "%% off %s" to "%% off %@",
        // A specifier surrounded by text, to pin the splice points.
        "Hi %1\$s, you have %2\$d left" to "Hi %1\$@, you have %2\$lld left",
    ).map { (input, expected) ->
        DynamicTest.dynamicTest("$input -> $expected") { assertEquals(expected, convert(input)) }
    }.stream()

    // ---- rows that must hard-error ----

    @TestFactory
    fun `every unsupported specifier family is a hard error naming a fix`(): Stream<DynamicTest> = listOf(
        // specifier to a distinctive fragment the message must carry
        "%g" to "not compatible across platforms",
        "%G" to "not compatible across platforms",
        "%a" to "hex-float",
        "%A" to "hex-float",
        "%c" to "8-bit unsigned char",
        "%C" to "8-bit unsigned char",
        "%S" to "null-terminated UTF-16 array",
        "%n" to "platform line separator",
        "%b" to "Java-only conversion",
        "%B" to "Java-only conversion",
        "%h" to "Java-only conversion",
        "%H" to "Java-only conversion",
        "%tY" to "date/time format specifiers",
        "%TY" to "date/time format specifiers",
        "%,d" to "grouping flag",
        "%(d" to "parenthesize-negatives",
        "%.3s" to "precision on a string specifier",
        "%10s" to "width/flags on '%s'",
        "%1\$s and %<d" to "argument reuse",
        // P0's Time-format short-circuit set: AAPT2 abandons its scan on any of
        // these, so Articulate must reject each one on its own account.
        "%D" to "unsupported format specifier",
        "%F" to "unsupported format specifier",
        "%K" to "unsupported format specifier",
        "%M" to "unsupported format specifier",
        "%W" to "unsupported format specifier",
        "%Z" to "unsupported format specifier",
        "%k" to "unsupported format specifier",
        "%m" to "unsupported format specifier",
        "%w" to "unsupported format specifier",
        "%y" to "unsupported format specifier",
        "%z" to "unsupported format specifier",
    ).map { (input, fragment) ->
        DynamicTest.dynamicTest("$input is rejected") {
            val message = errorFor(input)
            assertTrue(message.contains(fragment)) {
                "message for '$input' did not mention '$fragment'.\nActual: $message"
            }
            assertTrue(message.contains("test.xml") && message.contains("key 'k'")) {
                "message for '$input' must name the file and the key.\nActual: $message"
            }
        }
    }.stream()

    // ---- P0 positional discipline ----

    @org.junit.jupiter.api.Test
    fun `two or more specifiers must all be positional`() {
        assertTrue(errorFor("%s and %d").contains("non-positional format"))
        assertEquals("%1\$@ and %2\$lld", convert("%1\$s and %2\$d"))
    }

    @org.junit.jupiter.api.Test
    fun `a single non-positional specifier is fine`() {
        assertEquals("%@", convert("%s"))
    }

    /**
     * Ordering check, not just an error check: `%y and %s and %d` breaks both
     * rules at once, and the message must name the specifier the author actually
     * got wrong rather than send them to renumber arguments first. This is the
     * `error-time-format-shortcircuit` corpus case's real content.
     */
    @org.junit.jupiter.api.Test
    fun `an unsupported specifier is reported ahead of a positional problem`() {
        val message = errorFor("%y and %s and %d")
        assertTrue(message.contains("unsupported format specifier '%y'")) { message }
        assertTrue(!message.contains("non-positional")) { message }
    }

    // ---- K7 formatted="false" ----

    @org.junit.jupiter.api.Test
    fun `formatted false doubles every percent and interprets nothing`() {
        assertEquals("50%% off %%s", PlaceholderConverter.convert("50% off %s", false, pos, "k"))
        // Even a specifier that would otherwise hard-error is inert here.
        assertEquals("%%g", PlaceholderConverter.convert("%g", false, pos, "k"))
    }

    // ---- T2 / P7 helpers, used by the cross-locale checks ----

    @org.junit.jupiter.api.Test
    fun `hasNumericSpecifier rejects object slots and accepts numeric ones`() {
        assertTrue(!PlaceholderConverter.hasNumericSpecifier("%@ item"))
        assertTrue(!PlaceholderConverter.hasNumericSpecifier("Several items"))
        assertTrue(PlaceholderConverter.hasNumericSpecifier("%lld item"))
        assertTrue(PlaceholderConverter.hasNumericSpecifier("%1\$lld item"))
    }

    @org.junit.jupiter.api.Test
    fun `specifierTypesByIndex distinguishes a positional type swap from a matching multiset`() {
        val source = PlaceholderConverter.specifierTypesByIndex("%1\$@ %2\$lld")
        val swapped = PlaceholderConverter.specifierTypesByIndex("%1\$lld %2\$@")
        assertEquals(mapOf(1 to "@", 2 to "lld"), source)
        assertEquals(mapOf(1 to "lld", 2 to "@"), swapped)
        assertTrue(source != swapped) {
            "a positional type swap must not compare equal -- xcstringstool does not catch it (P7)"
        }
    }
}
