package net.sarazan.articulate.core.parse

/**
 * A source location for error messages: the file, plus a 1-based line/column
 * when known (StAX supplies these for every event; some synthetic positions,
 * such as "the file as a whole", have no column and use `-1`).
 */
data class XmlPosition(val filePath: String, val line: Int, val column: Int) {

    /** `path:line: ` prefix matching aapt2's own diagnostic shape. */
    override fun toString(): String = if (line >= 0) "$filePath:$line" else filePath
}

/**
 * The one exception type for every parse/convert failure in this module.
 *
 * Per the milestone's exit criteria, every message must name the file, the key
 * (when one is known), and the fix — not just report that something failed. The
 * constructor enforces the file/position on every throw; [key] is optional
 * because some errors (a malformed `<resources>` root, a duplicate key spanning
 * two names) are not about one resource entry.
 */
class ConversionException(
    val position: XmlPosition,
    val key: String?,
    detail: String,
) : Exception(buildMessage(position, key, detail))

private fun buildMessage(position: XmlPosition, key: String?, detail: String): String {
    val where = if (key != null) "$position: key '$key': " else "$position: "
    return "$where$detail"
}
