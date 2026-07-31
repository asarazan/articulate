package net.sarazan.articulate.core.locale

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.stream.Stream
import kotlin.random.Random

/**
 * Row-by-row coverage of PLAN.md §3.1's mapping table plus a property test
 * and an output-uniqueness check, per §3.2's exit criteria.
 *
 * Every success row here is independently confirmed against a real `aapt2`
 * 2.19 binary (`aapt2 dump configurations` on a linked APK) -- see
 * `docs/CONVERSIONS.md` §11 and §13 for the recipe.
 */
class AndroidLocaleMapperTest {

    private fun map(dirName: String, sourceLanguage: String = "en") =
        AndroidLocaleMapper.androidQualifierToBcp47(dirName, sourceLanguage)

    // ---- exhaustive table test: PLAN.md §3.1 ----

    @TestFactory
    fun `every row of the mapping table produces the documented tag`(): Stream<DynamicTest> = listOf(
        "values" to "en",
        "values-de" to "de",
        "values-pt-rBR" to "pt-BR",
        "values-b+sr+Latn" to "sr-Latn",
        "values-b+zh+Hans+CN" to "zh-Hans-CN",
        // legacy ISO-639 remap (Java 17+ direction -- see docs/CONVERSIONS.md §11)
        "values-in" to "id",
        "values-iw" to "he",
        "values-ji" to "yi",
        // modern spellings also accepted, unchanged -- AAPT2 remaps nothing
        "values-id" to "id",
        "values-he" to "he",
        "values-yi" to "yi",
        // D5a: Chinese canonicalizes by script
        "values-zh-rCN" to "zh-Hans",
        "values-zh-rTW" to "zh-Hant",
        "values-zh-rHK" to "zh-Hant-HK",
        // map from the directory name, not an AAPT2-normalized form
        "values-b+es+419" to "es-419",
        // region/script casing normalized
        "values-pt-rbr" to "pt-BR",
        // plain qualifiers not otherwise called out in the table
        "values-fil" to "fil",
        "values-en-rGB" to "en-GB",
        "values-es-rES" to "es-ES",
    ).map { (dir, expected) ->
        DynamicTest.dynamicTest("$dir -> $expected") { assertEquals(expected, map(dir)) }
    }.stream()

    @TestFactory
    fun `non-locale qualifiers are a hard error (D5b)`(): Stream<DynamicTest> = listOf(
        "values-night",
        "values-v21",
        "values-sw600dp",
        "values-de-rDE-night",
    ).map { dir ->
        DynamicTest.dynamicTest(dir) {
            val e = org.junit.jupiter.api.Assertions.assertThrows(AndroidLocaleMapper.LocaleMappingException::class.java) { map(dir) }
            assertTrue(e.message!!.contains("not a locale directory")) { "unexpected message: ${e.message}" }
        }
    }.stream()

    @Test
    fun `values with a custom source language maps to that language`() {
        assertEquals("fr", map("values", sourceLanguage = "fr"))
    }

    @Test
    fun `localeOverrides pins an explicit output, bypassing normal parsing`() {
        assertEquals(
            "zh-Hans",
            AndroidLocaleMapper.androidQualifierToBcp47("values-zh-rCN", "en", mapOf("zh-rCN" to "zh-Hans")),
        )
        // The override can even rescue a directory that would otherwise be a hard D5b error.
        assertEquals(
            "de",
            AndroidLocaleMapper.androidQualifierToBcp47("values-de-night", "en", mapOf("de-night" to "de")),
        )
    }

    @Test
    fun `a non-values directory is rejected`() {
        org.junit.jupiter.api.Assertions.assertThrows(AndroidLocaleMapper.LocaleMappingException::class.java) {
            map("drawable-hdpi")
        }
    }

    // ---- property test: output always matches BCP-47 syntax ----

    /**
     * What this mapper can ever emit: `lang`, optionally `-Script` (4-letter
     * titlecase), optionally `-REGION` (2-letter uppercase or 3-digit UN M49).
     * Not the full BCP-47 grammar (no variants/extensions) -- deliberately
     * narrower, matching exactly what §3.1's table asks this function to
     * produce.
     */
    private val bcp47Shape = Regex("^[a-z]{2,3}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?$")

    private val languages = listOf("en", "de", "fr", "es", "it", "pt", "ru", "ja", "zh", "ar", "sr", "in", "iw", "ji")
    private val scripts = listOf("Latn", "Cyrl", "Hans", "Hant", "Arab")

    @Test
    fun `every generated directory name maps to something matching BCP-47 shape`() {
        val random = Random(20260731)
        var checked = 0
        repeat(500) {
            val lang = languages.random(random)
            val useBcp47Form = random.nextBoolean()
            val dir = if (useBcp47Form) {
                val subtags = mutableListOf(lang)
                if (random.nextBoolean()) subtags += scripts.random(random)
                if (random.nextBoolean()) subtags += listOf("US", "GB", "BR", "419").random(random)
                "values-b+${subtags.joinToString("+")}"
            } else {
                if (random.nextBoolean()) {
                    "values-$lang-r${listOf("US", "GB", "BR", "DE", "FR").random(random)}"
                } else {
                    "values-$lang"
                }
            }
            val result = runCatching { map(dir) }
            // Every generated name here is a well-formed locale qualifier by
            // construction, so it must always succeed and always match the shape --
            // never silently produce something malformed.
            val tag = result.getOrElse { error("expected '$dir' to map successfully, but got: ${it.message}") }
            assertTrue(bcp47Shape.matches(tag)) { "'$dir' -> '$tag' does not match BCP-47 shape $bcp47Shape" }
            checked++
        }
        assertTrue(checked == 500)
    }

    // ---- output uniqueness across a realistic locale set (§3.2) ----

    /**
     * `map(map(x))` is explicitly undefined (outputs are not valid inputs), so
     * idempotence can't be the invariant here -- instead, a realistic set of
     * directories a real app might ship must map to distinct tags, i.e. no
     * accidental collision among ordinary, non-adversarial input.
     */
    @Test
    fun `a realistic ~30-locale directory set maps to entirely distinct tags`() {
        val dirs = listOf(
            "values", "values-ar", "values-bg", "values-ca", "values-cs", "values-da",
            "values-de", "values-el", "values-es-rES", "values-es-rUS", "values-fi",
            "values-fr", "values-fr-rCA", "values-he", "values-hi", "values-hr",
            "values-hu", "values-id", "values-it", "values-ja", "values-ko",
            "values-lt", "values-lv", "values-nb", "values-nl", "values-pl",
            "values-pt-rBR", "values-pt-rPT", "values-ro", "values-ru", "values-sk",
            "values-sl", "values-sr", "values-sv", "values-th", "values-tr",
            "values-uk", "values-vi", "values-zh-rCN", "values-zh-rTW", "values-zh-rHK",
        )
        assertTrue(dirs.size >= 30) { "fixture set must be at least ~30 locales, has ${dirs.size}" }
        val tags = dirs.map { map(it) }
        val duplicates = tags.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(duplicates.isEmpty()) { "unexpected collisions in a realistic locale set: $duplicates" }
        assertEquals(dirs.size, tags.toSet().size)
    }
}
