package net.sarazan.articulate.core.convert

import net.sarazan.articulate.core.diagnostics.Diagnostic
import net.sarazan.articulate.core.locale.AndroidLocaleMapper
import net.sarazan.articulate.core.model.Entry
import net.sarazan.articulate.core.model.LocaleTag
import net.sarazan.articulate.core.model.Localization
import net.sarazan.articulate.core.model.PluralCategory
import net.sarazan.articulate.core.model.StringCatalog
import net.sarazan.articulate.core.model.StringKey
import net.sarazan.articulate.core.model.StringUnit
import net.sarazan.articulate.core.parse.AndroidStringsParser
import net.sarazan.articulate.core.parse.ConversionException
import net.sarazan.articulate.core.parse.ParsedFile
import net.sarazan.articulate.core.parse.ParsedResource
import net.sarazan.articulate.core.parse.ValuesFileClassifier
import net.sarazan.articulate.core.parse.XmlPosition
import java.io.File

/**
 * The result of [AndroidToXcstringsConverter.convert]: the merged catalog
 * plus every non-fatal finding collected along the way, per PLAN.md §2.7.
 * [diagnostics] is ordered: first, one warning per presentation-only
 * companion file found during directory discovery (§4.3/§4.5c, sorted by
 * values directory then file name -- see
 * [AndroidToXcstringsConverter.discoverLocaleDirectories]); then locale
 * directories in the same sorted order `convert` reads them in, document
 * order within each file.
 */
data class ConversionResult(
    val catalog: StringCatalog,
    val diagnostics: List<Diagnostic>,
)

/**
 * Merges one or more parsed `strings.xml` files (one per locale directory)
 * into a [StringCatalog], applying every cross-file rule from
 * `docs/CONVERSIONS.md` §2.4-§2.6 that a single-file parse can't check on its
 * own: orphan keys, plural `other`-required, the plural numeric-specifier
 * requirement, specifier parity across locales, and `translatable="false"`
 * versus a supplied translation.
 *
 * Locale directory names (`values-de`, `values-pt-rBR`, ...) are mapped to
 * BCP-47 [LocaleTag]s by [AndroidLocaleMapper] (milestone 3, PLAN.md §3.1),
 * including its hard error for a non-locale qualifier (D5b) and the
 * cross-directory collision check two directories mapping to the same tag
 * requires.
 */
object AndroidToXcstringsConverter {

    /**
     * [inputDir] must contain one subdirectory per locale: `values/` for the
     * source language and `values-<tag>/` for every translation, each holding
     * a `strings.xml`. Directories without a `strings.xml` are ignored.
     *
     * [localeOverrides] is PLAN.md §3.1's escape hatch: any directory's
     * mapped tag can be pinned explicitly, keyed by the qualifier as written
     * (everything after `values-`, or `""` for bare `values`). The Gradle DSL
     * surface for this belongs to milestone 4; here it is a plain parameter.
     */
    fun convert(
        inputDir: File,
        sourceLanguage: String = "en",
        localeOverrides: Map<String, String> = emptyMap(),
    ): ConversionResult {
        val (qualifyingDirs, discoveryDiagnostics) = discoverLocaleDirectories(inputDir)
        val localeDirs = qualifyingDirs.map { dir -> dir to File(dir, "strings.xml") }

        val mapped: List<Triple<LocaleTag, File, File>> = localeDirs.map { (dir, xml) ->
            val tag = try {
                AndroidLocaleMapper.androidQualifierToBcp47(dir.name, sourceLanguage, localeOverrides)
            } catch (e: AndroidLocaleMapper.LocaleMappingException) {
                throw ConversionException(XmlPosition(xml.path, -1, -1), null, e.message.orEmpty())
            }
            Triple(LocaleTag(tag), dir, xml)
        }
        checkLocaleCollisions(mapped)

        val localeFiles: List<Pair<LocaleTag, ParsedFile>> = mapped.map { (tag, _, xml) ->
            tag to AndroidStringsParser.parse(xml)
        }

        val sourceTag = LocaleTag(sourceLanguage)
        val bySource = localeFiles.firstOrNull { it.first == sourceTag }
            ?: throw IllegalArgumentException(
                "no 'values' directory (source language '$sourceLanguage') found under ${inputDir.path}",
            )

        val sourceByName: Map<String, ParsedResource> = bySource.second.resources.associateBy { it.name }

        // K6: every key in a translation file must exist in the source file.
        for ((tag, parsed) in localeFiles) {
            if (tag == sourceTag) continue
            for (resource in parsed.resources) {
                if (resource.name !in sourceByName) {
                    throw ConversionException(
                        resource.position,
                        resource.name,
                        "key '${resource.name}' is defined in ${parsed.filePath} but not in the " +
                            "default locale (${bySource.second.filePath}) -- every translation must " +
                            "have a source entry (orphan translation)",
                    )
                }
            }
        }

        val entries = sortedMapOf<StringKey, Entry>(compareBy { it.value })
        for ((name, sourceResource) in sourceByName) {
            entries[StringKey(name)] = buildEntry(name, sourceResource, sourceTag, localeFiles)
        }

        val diagnostics = discoveryDiagnostics + localeFiles.flatMap { it.second.diagnostics }
        return ConversionResult(StringCatalog(sourceLanguage = sourceLanguage, entries = entries), diagnostics)
    }

    /**
     * PLAN.md §4.3: every `values`/`values-<tag>` subdirectory of [inputDir]
     * that supplies a `strings.xml`, after every *other* `*.xml` file in
     * each such directory has been classified by [ValuesFileClassifier] --
     * which throws if any of them declares localizable content under a name
     * other than `strings.xml` (the original silent-loss bug this guards
     * against: discovery hardcoded to `strings.xml` silently dropped
     * `plurals.xml`/`arrays.xml` split files), and returns a [Diagnostic]
     * warning for genuine presentation resources (`colors.xml`,
     * `dimens.xml`, ...) -- RULED 2026-08-03: no longer silently accepted,
     * see [ValuesFileClassifier].
     *
     * Classification runs over *every* `values`/`values-<tag>` directory,
     * not only ones that happen to contain a `strings.xml` -- a directory
     * holding only e.g. `marketing.xml` with `<string>`s in it must still be
     * caught, rather than silently skipped the way "no strings.xml here"
     * used to skip it.
     *
     * Returns the qualifying locale directories (those with a `strings.xml`)
     * paired with every presentation-file [Diagnostic] collected along the
     * way, so [convert] can fold them into [ConversionResult.diagnostics]
     * without a second pass over the tree.
     *
     * Exposed (not `private`) so callers outside this function could
     * delegate here instead of re-deriving this list themselves --
     * `GenerateAndroidResTask` in `plugin` used to (deleted by §4.5b/c, the
     * strings tree is now registered as a res root directly, nothing is
     * copied). Reimplementing this filter independently, without the
     * classification step, is exactly how the original bug happened: that
     * task filtered for `strings.xml` on its own and silently copied
     * nothing else.
     */
    fun discoverLocaleDirectories(inputDir: File): Pair<List<File>, List<Diagnostic>> {
        val valuesDirs = inputDir.listFiles { f -> f.isDirectory }.orEmpty()
            .sortedBy { it.name }
            .filter { it.name == "values" || it.name.startsWith("values-") }

        val diagnostics = mutableListOf<Diagnostic>()
        for (dir in valuesDirs) {
            dir.listFiles { f -> f.isFile && f.name != "strings.xml" && f.extension == "xml" }.orEmpty()
                .sortedBy { it.name }
                .forEach { file -> diagnostics += ValuesFileClassifier.checkNotLocalizable(file) }
        }

        val qualifying = valuesDirs.filter { File(it, "strings.xml").isFile }
        return qualifying to diagnostics
    }

    /**
     * D5b/§3.1: two directories mapping to the same output tag is silent data
     * loss (whichever is read last wins), covering all three sources the
     * plan calls out -- legacy/modern spelling pairs, script canonicalization,
     * and (indirectly) the non-locale-qualifier case, since
     * [AndroidLocaleMapper] rejects that before a collision could form. Error
     * names both directories, as PLAN.md §3.1 requires.
     */
    private fun checkLocaleCollisions(mapped: List<Triple<LocaleTag, File, File>>) {
        val byTag = mapped.groupBy({ it.first }, { it.second })
        for ((tag, dirs) in byTag) {
            if (dirs.size > 1) {
                val xmlPaths = dirs.map { File(it, "strings.xml").path }
                throw ConversionException(
                    XmlPosition(xmlPaths[0], -1, -1),
                    null,
                    "locale directories ${dirs.joinToString(" and ") { "'${it.name}'" }} both map to " +
                        "locale tag '${tag.value}' -- ${xmlPaths.joinToString(" and ")} would silently " +
                        "overwrite each other's strings. Rename one directory, or pin an explicit " +
                        "mapping via localeOverrides.",
                )
            }
        }
    }

    private fun buildEntry(
        name: String,
        sourceResource: ParsedResource,
        sourceTag: LocaleTag,
        localeFiles: List<Pair<LocaleTag, ParsedFile>>,
    ): Entry {
        val localizations = linkedMapOf<LocaleTag, Localization>()
        val positions = linkedMapOf<LocaleTag, XmlPosition>()
        var sawTranslationForUntranslatable: Pair<LocaleTag, XmlPosition>? = null

        for ((tag, parsed) in localeFiles) {
            val resource = parsed.resources.firstOrNull { it.name == name } ?: continue

            if (!sourceResource.translatable && tag != sourceTag) {
                sawTranslationForUntranslatable = tag to resource.position
                continue
            }

            checkTypeMatches(sourceResource, resource, tag, parsed.filePath)
            positions[tag] = resource.position
            localizations[tag] = when (resource) {
                is ParsedResource.StringResource -> Localization.Simple(
                    StringUnit(PlaceholderConverter.convert(resource.value, resource.formatted, resource.position, name)),
                )

                is ParsedResource.PluralResource -> {
                    val variants = sortedMapOf<PluralCategory, StringUnit>()
                    for ((category, value) in resource.variants) {
                        variants[category] = StringUnit(
                            PlaceholderConverter.convert(value, resource.formatted, resource.position, name),
                        )
                    }
                    if (PluralCategory.OTHER !in variants) {
                        throw ConversionException(
                            resource.position,
                            name,
                            "plural 'other' is missing in ${parsed.filePath} -- both Android and " +
                                "iOS fall back to 'other' at runtime, so every locale that defines " +
                                "this plural must supply it",
                        )
                    }
                    Localization.Plural(variants)
                }
            }
        }

        if (sawTranslationForUntranslatable != null) {
            val (tag, position) = sawTranslationForUntranslatable
            throw ConversionException(
                position,
                name,
                "key '$name' is marked translatable=\"false\" in the default locale, but a " +
                    "translation was supplied for locale '${tag.value}' -- remove the translation " +
                    "or drop translatable=\"false\"",
            )
        }

        checkPluralNumericSpecifier(name, sourceResource, localizations[sourceTag])
        checkSpecifierParity(name, sourceTag, localizations, positions)

        return Entry(
            localizations = localizations,
            comment = sourceResource.comment,
            shouldTranslate = sourceResource.translatable,
        )
    }

    private fun checkTypeMatches(source: ParsedResource, other: ParsedResource, tag: LocaleTag, otherFile: String) {
        val sourceIsPlural = source is ParsedResource.PluralResource
        val otherIsPlural = other is ParsedResource.PluralResource
        if (sourceIsPlural != otherIsPlural) {
            val sourceKind = if (sourceIsPlural) "<plurals>" else "<string>"
            val otherKind = if (otherIsPlural) "<plurals>" else "<string>"
            throw ConversionException(
                other.position,
                other.name,
                "key '${other.name}' is a $sourceKind in the default locale but a $otherKind in " +
                    "$otherFile (locale '${tag.value}') -- a key must be the same resource type in " +
                    "every locale",
            )
        }
    }

    /**
     * T2: at least one variant of the plural, *as declared in the source
     * language*, must contain a numeric (non-`%@`) specifier, or the
     * generated catalog will fail to build in Xcode. Scope per
     * `docs/CONVERSIONS.md` T2: checked against the source language only when
     * the source defines this key as a plural -- other locales are exempt
     * once the source conforms. (Every key is required to exist in the
     * source by [convert]'s orphan check, so the "source absent" branch of
     * T2's scope table never applies here.)
     */
    private fun checkPluralNumericSpecifier(name: String, sourceResource: ParsedResource, sourceLocalization: Localization?) {
        if (sourceResource !is ParsedResource.PluralResource) return
        val plural = sourceLocalization as? Localization.Plural ?: return
        val hasNumeric = plural.variations.values.any { PlaceholderConverter.hasNumericSpecifier(it.value) }
        if (!hasNumeric) {
            throw ConversionException(
                sourceResource.position,
                name,
                "Plural variation requires referencing the number in the string. To maintain " +
                    "grammatical correctness for strings that do not reference the number of " +
                    "items, use separate top-level strings for one and greater than one.",
            )
        }
    }

    /** P7: every locale's specifier multiset must match the source's, per matching plural category. */
    private fun checkSpecifierParity(
        name: String,
        sourceTag: LocaleTag,
        localizations: Map<LocaleTag, Localization>,
        positions: Map<LocaleTag, XmlPosition>,
    ) {
        val source = localizations[sourceTag] ?: return
        for ((tag, localization) in localizations) {
            if (tag == sourceTag) continue
            val position = positions.getValue(tag)
            when {
                source is Localization.Simple && localization is Localization.Simple -> {
                    requireSameShape(
                        name,
                        tag,
                        position,
                        PlaceholderConverter.specifierTypesByIndex(source.unit.value),
                        PlaceholderConverter.specifierTypesByIndex(localization.unit.value),
                    )
                }

                source is Localization.Plural && localization is Localization.Plural -> {
                    for ((category, unit) in localization.variations) {
                        val sourceUnit = source.variations[category] ?: continue
                        requireSameShape(
                            name,
                            tag,
                            position,
                            PlaceholderConverter.specifierTypesByIndex(sourceUnit.value),
                            PlaceholderConverter.specifierTypesByIndex(unit.value),
                        )
                    }
                }

                else -> Unit // type mismatch already rejected in checkTypeMatches
            }
        }
    }

    private fun requireSameShape(name: String, tag: LocaleTag, position: XmlPosition, source: Map<Int, String>, other: Map<Int, String>) {
        if (source != other) {
            throw ConversionException(
                position,
                name,
                "locale '${tag.value}' uses format specifiers $other but the default locale uses " +
                    "$source -- every translation must use the same argument positions and types as " +
                    "the source, or the app will crash or render garbage at runtime",
            )
        }
    }

}
