package net.sarazan.articulate.core.parse

import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * PLAN.md §4.3: classifies a `*.xml` file found in a `values`/`values-<tag>`
 * directory *other than* `strings.xml` as either "presentation resource,
 * silently ignore" or "carries localizable content, not yet supported."
 *
 * **Content-keyed, not filename-keyed.** What matters is which elements the
 * file declares at the top level of its `<resources>` root, not what the
 * file is called. That is what lets a real `app/src/main/res/values/`
 * directory -- routinely full of `colors.xml`, `dimens.xml`, `styles.xml` --
 * work untouched when the strings source dir is pointed at it (a
 * legitimate, supported adoption path), while a copy file an author *named*
 * something other than `strings.xml`, e.g. `marketing.xml`, is still caught
 * if it actually declares `<string>`s: filename-matching alone would miss it.
 *
 * `<string>`, `<plurals>`, and `<string-array>` at the top level are a hard
 * error: Android's resource merger folds them into the same string table
 * `strings.xml` produces, so silently skipping them here is exactly the
 * silent-loss bug this classifier exists to close. Everything else
 * (`<color>`, `<dimen>`, `<style>`, `<bool>`, `<integer>`, `<item>`, ...) is
 * presentation and is silently ignored -- genuinely not this tool's concern.
 *
 * **Deliberately the detection half only** of full multi-file support
 * (§4.3). Once a file is known to carry localizable content, actually
 * parsing it instead of erroring is a later, separate change: swap
 * [reportUnsupportedContent] for a call into [AndroidStringsParser]. Keeping
 * "does this file carry content" (this class) and "what do we do once we
 * know" (the throw below) as separate steps is what makes that swap a
 * one-line change instead of a rewrite.
 *
 * Malformed XML is treated the same way [AndroidStringsParser] treats a
 * malformed `strings.xml`: a hard error, not a silent ignore. A file this
 * classifier cannot parse is a file it cannot prove is presentation-only,
 * and guessing "probably fine" is exactly the failure mode this whole
 * mechanism exists to prevent.
 */
internal object ValuesFileClassifier {

    /** The three element names that make a file "carries localizable content" (§4.3's table). */
    private val CONTENT_ELEMENTS = setOf("string", "plurals", "string-array")

    /**
     * Scans [file] and throws [ConversionException] if it declares any
     * localizable content at the top level of its `<resources>` root.
     * Returns normally for a presentation-only (or otherwise irrelevant)
     * file -- the caller silently moves on.
     */
    fun checkNotLocalizable(file: File) {
        val found = linkedMapOf<String, MutableList<String?>>()
        try {
            file.inputStream().use { input ->
                val factory = XMLInputFactory.newFactory().apply {
                    setProperty(XMLInputFactory.SUPPORT_DTD, false)
                    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                }
                val reader = factory.createXMLStreamReader(input)
                try {
                    scan(reader, file.path, found)
                } finally {
                    reader.close()
                }
            }
        } catch (e: XMLStreamException) {
            throw ConversionException(
                XmlPosition(file.path, e.location?.lineNumber ?: -1, e.location?.columnNumber ?: -1),
                null,
                "XML parse error in ${file.path} -- this file is not well-formed XML, so Articulate " +
                    "cannot confirm it carries no localizable content, and guessing would risk the " +
                    "same silent data loss this check exists to prevent. Fix the XML (see the " +
                    "underlying error below), or remove the file if it is not an Android resource " +
                    "file: ${e.rawMessage()}",
            )
        }

        if (found.isNotEmpty()) reportUnsupportedContent(file, found)
    }

    /** StAX prefixes its messages with a multi-line `ParseError at [row,col]` banner; drop it (mirrors [AndroidStringsParser]). */
    private fun XMLStreamException.rawMessage(): String =
        message.orEmpty().substringAfterLast("Message: ").trim().ifEmpty { toString() }

    /**
     * Walks the direct children of `<resources>`, recording every one whose
     * local name is in [CONTENT_ELEMENTS] (name attribute included, for a
     * useful error message) and skipping over everything else -- including
     * the subtree of a recorded content element, since only its presence at
     * the top level matters here, not its contents.
     */
    private fun scan(reader: XMLStreamReader, filePath: String, found: MutableMap<String, MutableList<String?>>) {
        while (reader.hasNext() && reader.eventType != XMLStreamConstants.START_ELEMENT) {
            reader.next()
        }
        if (reader.eventType != XMLStreamConstants.START_ELEMENT || reader.localName != "resources") {
            throw ConversionException(
                reader.position(filePath),
                null,
                "expected root element <resources>, found <${reader.localName}> in $filePath -- this " +
                    "does not look like an Android resource file",
            )
        }

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    val local = reader.localName
                    if (local in CONTENT_ELEMENTS) {
                        found.getOrPut(local) { mutableListOf() }.add(reader.getAttributeValue(null, "name"))
                    }
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> {
                    if (reader.localName == "resources") return
                }

                else -> Unit
            }
        }
    }

    private fun reportUnsupportedContent(file: File, found: Map<String, List<String?>>): Nothing {
        val elementsDescription = found.entries.joinToString(", ") { (tag, names) ->
            "<$tag> (${names.joinToString(", ") { it ?: "<unnamed>" }})"
        }
        throw ConversionException(
            XmlPosition(file.path, -1, -1),
            null,
            "${file.path} declares $elementsDescription -- multi-file values-*/ directories are not " +
                "yet supported; only strings.xml is read from each values-*/ directory today. Move " +
                "these element(s) into strings.xml in the same directory instead.",
        )
    }
}
