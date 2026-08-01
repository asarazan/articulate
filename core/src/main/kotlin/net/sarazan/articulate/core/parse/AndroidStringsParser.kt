package net.sarazan.articulate.core.parse

import net.sarazan.articulate.core.diagnostics.Diagnostic
import net.sarazan.articulate.core.diagnostics.Severity
import net.sarazan.articulate.core.model.PluralCategory
import java.io.File
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

private val QUANTITY_TO_CATEGORY: Map<String, PluralCategory> = mapOf(
    "zero" to PluralCategory.ZERO,
    "one" to PluralCategory.ONE,
    "two" to PluralCategory.TWO,
    "few" to PluralCategory.FEW,
    "many" to PluralCategory.MANY,
    "other" to PluralCategory.OTHER,
)

/**
 * Parses one `strings.xml` file (one locale directory) into [ParsedResource]s,
 * running the full Android-side pipeline (S0-S4 of `docs/CONVERSIONS.md` §1)
 * per resource: entity resolution is the XML parser's job (S0); everything
 * from subtree flattening onward is [ContentFlattener] and
 * [AndroidTextPipeline].
 *
 * XXE-safe by construction: DTDs and external entities are disabled at the
 * factory, so no external resource can be pulled in by a crafted input file.
 */
internal object AndroidStringsParser {

    fun parse(file: File): ParsedFile = file.inputStream().use { parse(it, file.path) }

    fun parse(input: InputStream, filePath: String): ParsedFile {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val reader = factory.createXMLStreamReader(input)
        try {
            return parseDocument(reader, filePath)
        } catch (e: XMLStreamException) {
            // S0 failures (an invalid character reference such as `&#11;`, a stray
            // `&`, an unclosed tag) surface as a raw StAX exception whose message
            // names neither the file nor a fix -- PLAN.md §2.1 requires both of every
            // failure Articulate reports. Re-shape it here rather than letting the
            // parser's own diagnostic escape.
            throw ConversionException(
                XmlPosition(filePath, e.location?.lineNumber ?: -1, e.location?.columnNumber ?: -1),
                null,
                "XML parse error -- this file is not well-formed XML, so no string in it can be " +
                    "read: ${e.rawMessage()}. Note that XML 1.0 forbids most control characters " +
                    "outright, so an invalid XML character reference (e.g. '&#11;') must be " +
                    "removed rather than escaped; use Android's own '\\uXXXX' escape if the " +
                    "character is genuinely wanted.",
            )
        } finally {
            reader.close()
        }
    }

    /** StAX prefixes its messages with a multi-line `ParseError at [row,col]` banner; drop it. */
    private fun XMLStreamException.rawMessage(): String =
        message.orEmpty().substringAfterLast("Message: ").trim().ifEmpty { toString() }

    private fun parseDocument(reader: XMLStreamReader, filePath: String): ParsedFile {
        val flattener = ContentFlattener(filePath)
        val resources = mutableListOf<ParsedResource>()
        val diagnostics = mutableListOf<Diagnostic>()
        val seenNames = mutableSetOf<String>()
        var pendingComment: String? = null

        while (reader.hasNext() && reader.eventType != XMLStreamConstants.START_ELEMENT) {
            reader.next()
        }
        if (reader.eventType != XMLStreamConstants.START_ELEMENT || reader.localName != "resources") {
            throw ConversionException(
                reader.position(filePath),
                null,
                "expected root element <resources>, found <${reader.localName}>",
            )
        }

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.COMMENT -> {
                    // Only a comment *immediately* preceding a <string>/<plurals> counts
                    // (§2.5); any other event in between clears it below.
                    pendingComment = reader.text.trim().takeIf { it.isNotEmpty() }
                }

                XMLStreamConstants.START_ELEMENT -> {
                    val position = reader.position(filePath)
                    val commentForThisElement = pendingComment
                    pendingComment = null
                    when (reader.localName) {
                        "string" -> {
                            val resource = parseStringElement(reader, flattener, filePath, position, commentForThisElement, diagnostics)
                            checkDuplicate(seenNames, resource.name, position, filePath)
                            resources += resource
                        }

                        "plurals" -> {
                            val resource = parsePluralsElement(reader, flattener, filePath, position, commentForThisElement, diagnostics)
                            checkDuplicate(seenNames, resource.name, position, filePath)
                            resources += resource
                        }

                        "string-array" -> throw arrayNotSupported(reader, position, "<string-array>")

                        // Android resolves `<array>` to the *same* resource type as
                        // `<string-array>` -- verified with aapt2, which puts
                        // `array/classic_array` and `array/generic_array` side by side
                        // in one `type array` block. Rejecting only the `<string-array>`
                        // spelling let this spelling through silently, which is data
                        // loss wearing the same clothes as the multi-file bug: our
                        // discovery keyed on an element name where Android keys on a
                        // resource type.
                        "array" -> throw arrayNotSupported(reader, position, "<array>")

                        // `<item type="...">` is Android's generic declaration form and
                        // is NOT interchangeable across types -- each branch below is
                        // what aapt2 actually produced, not what the docs imply.
                        "item" -> when (reader.getAttributeValue(null, "type")) {
                            // Compiles to a `string` resource indistinguishable from
                            // `<string>`. Previously skipped, so ordinary translatable
                            // copy went missing with verifyStrings green -- it never
                            // knew the key existed.
                            "string" -> {
                                val resource = parseStringElement(
                                    reader, flattener, filePath, position, commentForThisElement, diagnostics,
                                    elementName = "item",
                                )
                                checkDuplicate(seenNames, resource.name, position, filePath)
                                resources += resource
                            }

                            "array" -> throw arrayNotSupported(reader, position, "<item type=\"array\">")

                            "plurals" -> {
                                val name = reader.getAttributeValue(null, "name") ?: "<unnamed>"
                                throw ConversionException(
                                    position,
                                    name,
                                    "<item type=\"plurals\"> does not declare a plurals resource. Android " +
                                        "compiles it to a *styled string* -- the nested <item quantity=\"...\"> " +
                                        "elements become markup spans, not quantity variants, so '$name' is " +
                                        "already broken on Android itself and would be wrong on iOS too. " +
                                        "Use <plurals name=\"$name\"> instead.",
                                )
                            }

                            // Every other type (color, dimen, bool, integer, style...)
                            // is a presentation resource and genuinely not ours --
                            // same rule the multi-file check applies (§4.3).
                            else -> skipElement(reader)
                        }

                        else -> skipElement(reader)
                    }
                }

                XMLStreamConstants.CHARACTERS -> {
                    if (reader.text.isNotBlank()) pendingComment = null
                }

                XMLStreamConstants.END_ELEMENT -> {
                    if (reader.localName == "resources") return ParsedFile(filePath, resources, diagnostics)
                }

                else -> Unit
            }
        }
        return ParsedFile(filePath, resources, diagnostics)
    }

    /**
     * D6's rejection, shared by every spelling that reaches Android's `array`
     * resource type. [spelling] names the one actually written so the message
     * points at the user's own text rather than a canonical form they did not
     * use.
     */
    private fun arrayNotSupported(
        reader: XMLStreamReader,
        position: XmlPosition,
        spelling: String,
    ): ConversionException {
        val name = reader.getAttributeValue(null, "name") ?: "<unnamed>"
        return ConversionException(
            position,
            name,
            "$spelling is not supported -- iOS has no array resource, and " +
                "auto-indexing would invent both a key convention and a type " +
                "policy Articulate can't know is right. Split '$name' into plain " +
                "<string> resources instead, e.g. '${name}_0', '${name}_1', ...",
        )
    }

    private fun parseStringElement(
        reader: XMLStreamReader,
        flattener: ContentFlattener,
        filePath: String,
        position: XmlPosition,
        pendingComment: String?,
        diagnostics: MutableList<Diagnostic>,
        elementName: String = "string",
    ): ParsedResource.StringResource {
        val name = requireName(reader, position)
        checkKeyLegality(name, position)
        keyIrregularityWarning(name, position)?.let { diagnostics += it }
        val translatable = parseBooleanAttr(reader, "translatable", position, name) ?: true
        val formatted = parseBooleanAttr(reader, "formatted", position, name) ?: true

        // [elementName] is the tag the content closes with -- "string" normally,
        // "item" for the `<item type="string">` spelling (§2.2). Everything else
        // about the two is identical, which is not an assumption: aapt2 compiles
        // both to the same `string` resource type, byte-indistinguishable in the
        // resource table.
        val flattened = flattener.flatten(reader, elementName, name)
        if (flattened.hasRealSpan) throw styledMarkupError(flattened, filePath, name)
        diagnostics += foreignNamespaceWarnings(flattened, name)

        val value = AndroidTextPipeline.process(flattened.rawText, position, name)
        val comment = mergeComments(pendingComment, buildXliffComment(flattened.xliffPlaceholders))
        return ParsedResource.StringResource(name, translatable, formatted, comment, position, value)
    }

    private fun parsePluralsElement(
        reader: XMLStreamReader,
        flattener: ContentFlattener,
        filePath: String,
        position: XmlPosition,
        pendingComment: String?,
        diagnostics: MutableList<Diagnostic>,
    ): ParsedResource.PluralResource {
        val name = requireName(reader, position)
        checkKeyLegality(name, position)
        keyIrregularityWarning(name, position)?.let { diagnostics += it }
        val translatable = parseBooleanAttr(reader, "translatable", position, name) ?: true
        val formatted = parseBooleanAttr(reader, "formatted", position, name) ?: true

        val variants = linkedMapOf<PluralCategory, String>()
        var mergedComment = pendingComment
        var sawEnd = false

        while (!sawEnd && reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    val itemPosition = reader.position(filePath)
                    if (reader.localName != "item") {
                        skipElement(reader)
                        continue
                    }
                    val quantityAttr = reader.getAttributeValue(null, "quantity")
                        ?: throw ConversionException(itemPosition, name, "<item> in <plurals> is missing required 'quantity' attribute")
                    val category = QUANTITY_TO_CATEGORY[quantityAttr]
                        ?: throw ConversionException(
                            itemPosition,
                            name,
                            "<item> in <plurals> has invalid value '$quantityAttr' for attribute " +
                                "'quantity'. Legal values are: zero, one, two, few, many, other",
                        )
                    if (variants.containsKey(category)) {
                        throw ConversionException(
                            itemPosition,
                            name,
                            "duplicate quantity '$quantityAttr' -- each quantity may appear at most " +
                                "once in a <plurals>. Remove or merge the repeated <item>",
                        )
                    }
                    val flattened = flattener.flatten(reader, "item", name)
                    if (flattened.hasRealSpan) throw styledMarkupError(flattened, filePath, name)
                    diagnostics += foreignNamespaceWarnings(flattened, name)
                    val value = AndroidTextPipeline.process(flattened.rawText, itemPosition, name)
                    variants[category] = value
                    val xliffComment = buildXliffComment(flattened.xliffPlaceholders)
                    if (xliffComment != null) mergedComment = mergeComments(mergedComment, xliffComment)
                }

                XMLStreamConstants.END_ELEMENT -> {
                    if (reader.localName == "plurals") sawEnd = true
                }

                else -> Unit
            }
        }

        return ParsedResource.PluralResource(name, translatable, mergedComment, position, formatted, variants)
    }

    private fun requireName(reader: XMLStreamReader, position: XmlPosition): String =
        reader.getAttributeValue(null, "name")
            ?: throw ConversionException(position, null, "<${reader.localName}> is missing required 'name' attribute")

    private fun checkDuplicate(seenNames: MutableSet<String>, name: String, position: XmlPosition, filePath: String) {
        if (!seenNames.add(name)) {
            throw ConversionException(
                position,
                name,
                "duplicate value for resource 'string/$name' in $filePath -- a name may only be " +
                    "defined once per file, whether as <string> or <plurals>",
            )
        }
    }

    private fun styledMarkupError(flattened: FlattenedContent, filePath: String, key: String): ConversionException =
        ConversionException(
            flattened.spanPosition ?: XmlPosition(filePath, -1, -1),
            key,
            "inline styling markup <${flattened.spanTagName}> is not supported -- .xcstrings values " +
                "are flat strings with no span concept, so styling information provably cannot " +
                "survive the conversion. Remove the markup, or opt in to markupPolicy = STRIP " +
                "(strip tags, keep text) once available.",
        )

    private fun parseBooleanAttr(reader: XMLStreamReader, attrName: String, position: XmlPosition, key: String): Boolean? {
        val raw = reader.getAttributeValue(null, attrName) ?: return null
        return when (raw.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw ConversionException(
                position,
                key,
                "invalid value for '$attrName'. Must be a boolean (true or false), found '$raw'",
            )
        }
    }

    /**
     * K1's grammar: `XID_Start | _` then `(XID_Continue | . | -)*`. Approximated
     * with [Character.isUnicodeIdentifierStart]/[Character.isUnicodeIdentifierPart]
     * rather than a full UAX #31 table -- close enough for this milestone, and
     * verified to exclude '$' and leading digits the same way AAPT2 does (see
     * docs/CONVERSIONS.md K1).
     */
    private fun checkKeyLegality(name: String, position: XmlPosition) {
        if (name.isEmpty()) {
            throw ConversionException(position, name, "resource name must not be empty")
        }
        val first = name.codePointAt(0)
        if (first != '_'.code && !Character.isUnicodeIdentifierStart(first)) {
            throw ConversionException(
                position,
                name,
                "invalid key '$name': resource names must start with a letter or underscore, " +
                    "not '${String(Character.toChars(first))}'",
            )
        }
        var offset = Character.charCount(first)
        while (offset < name.length) {
            val cp = name.codePointAt(offset)
            val ok = cp == '.'.code || cp == '-'.code || Character.isUnicodeIdentifierPart(cp)
            if (!ok) {
                throw ConversionException(
                    position,
                    name,
                    "invalid key '$name': resource names may only contain letters, digits, '.', " +
                        "'-', or '_' -- found '${String(Character.toChars(cp))}'",
                )
            }
            offset += Character.charCount(cp)
        }
    }

    /**
     * K1's "we are stricter than AAPT2" clause: `.`/`-`/non-ASCII are legal
     * per the AOSP grammar (accepted by [checkKeyLegality] above) but break
     * Xcode's `xcstringstool generate-symbols` and Kotlin/Java `R.` access,
     * so we warn on them without rejecting them.
     */
    private fun keyIrregularityWarning(name: String, position: XmlPosition): Diagnostic? {
        val hasDotOrDash = name.any { it == '.' || it == '-' }
        val hasNonAscii = name.any { it.code > 0x7F }
        if (!hasDotOrDash && !hasNonAscii) return null

        val reasons = buildList {
            if (hasDotOrDash) add("'.' or '-'")
            if (hasNonAscii) add("non-ASCII characters")
        }.joinToString(" and ")

        return Diagnostic(
            Severity.WARNING,
            position,
            name,
            "key '$name' contains $reasons -- Xcode's 'xcstringstool generate-symbols' and " +
                "Kotlin/Java 'R.' access both need a plain-ASCII identifier to generate anything " +
                "usable from a key, so this key will not produce a usable generated symbol on " +
                "either platform. The key is still accepted; consider renaming it to use only " +
                "ASCII letters, digits, and '_'.",
        )
    }

    /** M5: one [Diagnostic] per foreign-namespace tag [ContentFlattener] dropped for this resource. */
    private fun foreignNamespaceWarnings(flattened: FlattenedContent, key: String): List<Diagnostic> =
        flattened.foreignNamespaceTags.map { tag ->
            Diagnostic(
                Severity.WARNING,
                tag.position,
                key,
                "warn: ignoring element '<${tag.localName}>' with unknown namespace " +
                    "'${tag.namespaceUri}' -- the tag is dropped and its children's text is kept",
            )
        }
}

private fun XliffPlaceholder.describe(): String? = when {
    id != null && example != null -> "$id: $example"
    id != null -> id
    example != null -> example
    else -> null
}

private fun buildXliffComment(placeholders: List<XliffPlaceholder>): String? {
    val parts = placeholders.mapNotNull { it.describe() }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
}

private fun mergeComments(a: String?, b: String?): String? = when {
    a != null && b != null -> "$a\n\n$b"
    a != null -> a
    b != null -> b
    else -> null
}
