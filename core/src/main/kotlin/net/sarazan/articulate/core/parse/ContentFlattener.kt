package net.sarazan.articulate.core.parse

import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/** The XLIFF 1.2 namespace AAPT2 special-cases for `<xliff:g>` placeholder metadata. */
internal const val XLIFF_NAMESPACE_URI = "urn:oasis:names:tc:xliff:document:1.2"

/** One `<xliff:g id="..." example="...">` tag's attributes, in document order. */
internal data class XliffPlaceholder(val id: String?, val example: String?)

/** M5: one dropped foreign-namespace tag, in document order. */
internal data class ForeignNamespaceTag(val localName: String, val namespaceUri: String, val position: XmlPosition)

/** The result of flattening one `<string>`/`<item>` element's content (S1). */
internal data class FlattenedContent(
    val rawText: String,
    val hasRealSpan: Boolean,
    val spanTagName: String?,
    val spanPosition: XmlPosition?,
    val xliffPlaceholders: List<XliffPlaceholder>,
    val foreignNamespaceTags: List<ForeignNamespaceTag>,
)

/**
 * Implements S1 ("subtree flatten") from `docs/CONVERSIONS.md` §1 and §6.
 *
 * A no-namespace child element is a real style span (M1) — under this
 * milestone's default `markupPolicy = ERROR`, its mere presence is a hard
 * error, so this flattener only needs to *detect* one, not build correct
 * span-boundary text for it (the `STRIP`/`VERBATIM` policies that would need
 * that are unimplemented placeholders — see PLAN.md D4).
 *
 * `<xliff:g>` (M4) and foreign-namespace tags (M5) are transparent: their tag
 * is dropped, their text content is folded into the same continuous stream as
 * their surroundings with no state reset, and their children are recursed
 * into (so a real span nested inside either is still detected).
 */
internal class ContentFlattener(private val filePath: String) {

    /**
     * [key] is the resource name the content belongs to, threaded through purely
     * so a failure raised in here still names its key -- PLAN.md §2.1 requires
     * every message to name the file *and* the key, and a flattener-level error
     * (nested `<xliff:g>`) is as much about one entry as any other.
     */
    fun flatten(reader: XMLStreamReader, endLocalName: String, key: String?): FlattenedContent {
        val text = StringBuilder()
        val xliffPlaceholders = mutableListOf<XliffPlaceholder>()
        val foreignNamespaceTags = mutableListOf<ForeignNamespaceTag>()
        var hasRealSpan = false
        var spanTagName: String? = null
        var spanPosition: XmlPosition? = null

        fun recordSpan(name: String, position: XmlPosition) {
            if (!hasRealSpan) {
                hasRealSpan = true
                spanTagName = name
                spanPosition = position
            }
        }

        fun walk(insideXliffG: Boolean) {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        text.append(reader.text)
                    }

                    XMLStreamConstants.START_ELEMENT -> {
                        val position = reader.position(filePath)
                        val uri = reader.namespaceURI ?: ""
                        val local = reader.localName
                        when {
                            uri == XLIFF_NAMESPACE_URI && local == "g" -> {
                                if (insideXliffG) {
                                    throw ConversionException(
                                        position,
                                        key,
                                        "illegal nested XLIFF 'g' tag -- <xliff:g> cannot contain " +
                                            "another <xliff:g>. Remove the inner tag: one <xliff:g> " +
                                            "already annotates the whole placeholder",
                                    )
                                }
                                val id = reader.getAttributeValue(null, "id")
                                val example = reader.getAttributeValue(null, "example")
                                xliffPlaceholders += XliffPlaceholder(id, example)
                                walk(insideXliffG = true)
                            }

                            uri == XLIFF_NAMESPACE_URI -> {
                                // M4: non-`g` XLIFF tags are silently ignored -- tag and
                                // content both dropped, no text contributed.
                                skipElement(reader)
                            }

                            uri.isEmpty() -> {
                                recordSpan(local, position)
                                // Still recurse so a nested real span or malformed nested
                                // xliff:g is caught rather than silently swallowed, and so
                                // the span's own text doesn't corrupt sibling flattening.
                                walk(insideXliffG)
                            }

                            else -> {
                                // M5: foreign namespace -- warn, drop tag, keep children,
                                // no state reset. Recorded here so the caller can surface
                                // it as a [net.sarazan.articulate.core.diagnostics.Diagnostic]
                                // (§2.7); this layer only detects and reports the tag.
                                foreignNamespaceTags += ForeignNamespaceTag(local, uri, position)
                                walk(insideXliffG)
                            }
                        }
                    }

                    XMLStreamConstants.END_ELEMENT -> {
                        // XML events are strictly nested, so whichever frame is currently
                        // running -- the top-level call for [endLocalName] itself, or a
                        // recursive call entered right after a child's START_ELEMENT --
                        // the first unconsumed END_ELEMENT it sees is always that frame's
                        // own closing tag.
                        return
                    }

                    else -> Unit
                }
            }
        }

        walk(insideXliffG = false)
        check(reader.localName == endLocalName) {
            "internal error: expected </$endLocalName>, found </${reader.localName}>"
        }

        return FlattenedContent(text.toString(), hasRealSpan, spanTagName, spanPosition, xliffPlaceholders, foreignNamespaceTags)
    }
}

/**
 * Consumes an element (reader currently on its START_ELEMENT event) without
 * emitting text. Used both for non-`g` XLIFF tags (M4) and for top-level
 * resource types this converter doesn't process (`<color>`, `<dimen>`, ...),
 * which are out of scope but shouldn't break unrelated resources in the file.
 */
internal fun skipElement(reader: XMLStreamReader) {
    var depth = 1
    while (depth > 0 && reader.hasNext()) {
        when (reader.next()) {
            XMLStreamConstants.START_ELEMENT -> depth++
            XMLStreamConstants.END_ELEMENT -> depth--
            else -> Unit
        }
    }
}

internal fun XMLStreamReader.position(filePath: String): XmlPosition {
    val loc = location
    return XmlPosition(filePath, loc.lineNumber, loc.columnNumber)
}
