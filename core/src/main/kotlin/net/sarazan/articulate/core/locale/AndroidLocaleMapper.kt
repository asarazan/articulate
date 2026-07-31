package net.sarazan.articulate.core.locale

/**
 * Maps an Android `values-*` qualifier directory name to the BCP-47 locale
 * tag Xcode's String Catalog format expects, per PLAN.md §3.1 and
 * `docs/CONVERSIONS.md` §11.
 *
 * Implemented as an explicit hand-rolled table + a small parser --
 * deliberately **not** `java.util.Locale`, whose legacy-code remapping
 * behavior (`in`/`iw`/`ji`) is version- and flag-dependent, where this needs
 * deterministic, testable output regardless of which JDK runs it.
 *
 * Every rule here is verified against a real `aapt2` 2.19 binary
 * (`aapt2 dump configurations` on a linked APK, recipe in
 * `docs/CONVERSIONS.md` §13) rather than assumed:
 *  - AAPT2 performs **no** legacy/modern remapping in either direction --
 *    `values-in` and `values-id` both compile and both survive verbatim, so
 *    Articulate must accept either spelling on input (the modern spelling is
 *    always the output, per Java 17+'s own remap direction).
 *  - AAPT2 does **not** uniformly preserve BCP-47 spelling: `values-b+es+419`
 *    round-trips through AAPT2's own config representation as `es-r419`.
 *    Mapping must therefore read the *directory name itself*, never an
 *    AAPT2-normalized form.
 *  - A non-locale qualifier is not silently dropped by AAPT2 -- `values-de`
 *    and `values-de-night` normalize to `de` and `de-rDE-night-v8`
 *    respectively, two distinct configs. Stripping the qualifier ourselves
 *    would map both to `de`, silently losing whichever directory is read
 *    second (D5b) -- so this is a hard error instead.
 */
object AndroidLocaleMapper {

    /**
     * Thrown for a directory name this mapper cannot turn into a locale tag
     * -- most commonly D5b's non-locale-qualifier rule. Carries no source
     * position of its own (there is no XML position for a directory name);
     * callers with file context re-wrap this as a
     * [net.sarazan.articulate.core.parse.ConversionException].
     */
    class LocaleMappingException(message: String) : Exception(message)

    /**
     * Direction confirmed against the Java 17+ javadoc specifically (the
     * remap direction reversed in Java 17; a pre-17 citation would say the
     * opposite) -- see `docs/CONVERSIONS.md` §11.
     */
    private val LEGACY_LANGUAGE_REMAP: Map<String, String> = mapOf(
        "in" to "id",
        "iw" to "he",
        "ji" to "yi",
    )

    /** D5a: Chinese canonicalizes by script, not region -- every Apple default keys Chinese this way. */
    private val ZH_REGION_TO_SCRIPT: Map<String, String> = mapOf(
        "CN" to "zh-Hans",
        "TW" to "zh-Hant",
        "HK" to "zh-Hant-HK",
    )

    private val LANGUAGE_TOKEN = Regex("^[A-Za-z]{2,3}$")
    private val REGION_TOKEN = Regex("^[rR]([A-Za-z]{2})$")

    /**
     * [dirName] is the bare directory name (`values`, `values-de`,
     * `values-pt-rBR`, `values-b+es+419`, ...).
     *
     * [localeOverrides] is PLAN.md §3.1's escape hatch -- "allowing any
     * canonicalization to be overridden explicitly" -- keyed by the
     * qualifier exactly as written (everything after `values-`, or `""` for
     * bare `values`), checked *before* any parsing or validation, so it can
     * override a canonicalization this function would otherwise choose, or
     * even a directory this function would otherwise reject as a non-locale
     * qualifier.
     *
     * Throws [LocaleMappingException] for a non-locale qualifier (D5b) or an
     * unrecognized BCP-47 subtag shape.
     */
    fun androidQualifierToBcp47(
        dirName: String,
        sourceLanguage: String = "en",
        localeOverrides: Map<String, String> = emptyMap(),
    ): String {
        val qualifier = when {
            dirName == "values" -> ""
            dirName.startsWith("values-") -> dirName.removePrefix("values-")
            else -> throw LocaleMappingException(
                "'$dirName' is not an Android values directory -- expected 'values' or a name " +
                    "starting with 'values-'",
            )
        }

        localeOverrides[qualifier]?.let { return it }

        if (qualifier.isEmpty()) return sourceLanguage

        return if (qualifier.startsWith("b+")) parseBcp47Form(qualifier) else parseLegacyForm(qualifier)
    }

    /**
     * The `values-b+<lang>+<subtag>+...` form. Verified: AAPT2 tolerates a
     * further Android qualifier appended after a `-` (`values-b+es+419-v21`
     * normalizes to `es-r419-v21` rather than erroring), so a leftover after
     * the BCP-47 portion is a genuine non-locale qualifier under D5b, not a
     * malformed directory.
     */
    private fun parseBcp47Form(qualifier: String): String {
        val dashIndex = qualifier.indexOf('-', startIndex = 2)
        val bcp47Part = if (dashIndex >= 0) qualifier.substring(0, dashIndex) else qualifier
        val leftover = if (dashIndex >= 0) qualifier.substring(dashIndex + 1) else ""
        if (leftover.isNotEmpty()) throwNonLocaleQualifier(qualifier, leftover)

        val subtags = bcp47Part.removePrefix("b+").split("+")
        if (subtags.isEmpty() || subtags[0].isEmpty()) {
            throw LocaleMappingException("'values-$qualifier' has no language subtag after 'b+'")
        }
        val language = remapLegacyLanguage(subtags[0].lowercase())
        val rest = subtags.drop(1).map { formatBcp47Subtag(qualifier, it) }
        return (listOf(language) + rest).joinToString("-")
    }

    /** One non-language BCP-47 subtag: 4 letters = script, 2 letters = region, 3 digits = UN M49 region. */
    private fun formatBcp47Subtag(qualifier: String, subtag: String): String = when {
        subtag.length == 4 && subtag.all { it.isLetter() } ->
            subtag.lowercase().replaceFirstChar { it.uppercase() }
        subtag.length == 2 && subtag.all { it.isLetter() } ->
            subtag.uppercase()
        subtag.length == 3 && subtag.all { it.isDigit() } ->
            subtag
        else -> throw LocaleMappingException(
            "'values-$qualifier': unrecognized BCP-47 subtag '$subtag' -- Articulate recognizes a " +
                "4-letter script, a 2-letter region, or a 3-digit UN M49 region",
        )
    }

    /**
     * The legacy `values-<lang>[-r<REGION>]` form. Anything left over after
     * the language and optional region is a non-locale qualifier (D5b): the
     * language token itself failing to look like a language code (`night`,
     * `v21`, `sw600dp`) is caught the same way, as a zero-token "region"
     * leftover.
     */
    private fun parseLegacyForm(qualifier: String): String {
        val tokens = qualifier.split("-")
        val languageToken = tokens[0]
        if (!LANGUAGE_TOKEN.matches(languageToken)) {
            throwNonLocaleQualifier(qualifier, languageToken)
        }
        val language = languageToken.lowercase()

        var region: String? = null
        var consumed = 1
        if (tokens.size > 1) {
            REGION_TOKEN.matchEntire(tokens[1])?.let { match ->
                region = match.groupValues[1].uppercase()
                consumed = 2
            }
        }

        val leftover = tokens.drop(consumed).joinToString("-")
        if (leftover.isNotEmpty()) throwNonLocaleQualifier(qualifier, leftover)

        // D5a: zh + a legacy region qualifier canonicalizes to script.
        if (language == "zh" && region != null) {
            ZH_REGION_TO_SCRIPT[region]?.let { return it }
        }

        val remapped = remapLegacyLanguage(language)
        return if (region != null) "$remapped-$region" else remapped
    }

    private fun remapLegacyLanguage(language: String): String = LEGACY_LANGUAGE_REMAP[language] ?: language

    private fun throwNonLocaleQualifier(qualifier: String, offending: String): Nothing = throw LocaleMappingException(
        "'values-$qualifier' is not a locale directory -- '$offending' is not a recognized locale " +
            "qualifier. This module is platform-neutral (D5b): density/night-mode/API-level " +
            "qualifiers (e.g. 'values-night', 'values-v21', 'values-sw600dp') have no iOS meaning " +
            "and cannot be silently dropped, since that would map two distinct Android configs " +
            "(e.g. 'values-de' and 'values-de-night') onto the same locale tag and lose one of " +
            "them. Move the qualifier out into an Android-only resource set, or use " +
            "localeOverrides to pin this directory to a tag explicitly.",
    )
}
