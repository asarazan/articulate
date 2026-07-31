package net.sarazan.articulate.core.parse

import net.sarazan.articulate.core.diagnostics.Diagnostic
import net.sarazan.articulate.core.model.PluralCategory

/**
 * One `<string>`/`<plurals>` entry as read from a single `strings.xml` file,
 * fully through the Android-side pipeline (S0-S4): escapes resolved, spans
 * rejected, quoting/whitespace collapsed, comment/xliff metadata attached.
 * Still Android-shaped -- placeholder conversion and locale merging are the
 * converter's job, not the parser's.
 */
internal sealed interface ParsedResource {
    val name: String
    val translatable: Boolean
    val comment: String?
    val position: XmlPosition

    data class StringResource(
        override val name: String,
        override val translatable: Boolean,
        val formatted: Boolean,
        override val comment: String?,
        override val position: XmlPosition,
        val value: String,
    ) : ParsedResource

    data class PluralResource(
        override val name: String,
        override val translatable: Boolean,
        override val comment: String?,
        override val position: XmlPosition,
        val formatted: Boolean,
        val variants: Map<PluralCategory, String>,
    ) : ParsedResource
}

/**
 * Everything parsed from one `strings.xml` file (one locale directory).
 *
 * [diagnostics] is every non-fatal M5/K1 finding from this file, in document
 * order (§2.7) -- errors never reach here, since [ConversionException] always
 * unwinds the parse instead.
 */
internal data class ParsedFile(
    val filePath: String,
    val resources: List<ParsedResource>,
    val diagnostics: List<Diagnostic> = emptyList(),
)
