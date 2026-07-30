package net.sarazan.articulate.core.serialize

/**
 * The only JSON shapes an `.xcstrings` file needs: nested objects whose leaves are
 * strings. No arrays, numbers or booleans occur in the format we emit — even
 * `version` is a string — so the emitter stays small enough to audit line by line
 * against Xcode's output.
 *
 * Routing the model through this tree rather than writing text directly means
 * sorting happens in exactly one place, so determinism cannot be forgotten at an
 * individual call site.
 */
internal sealed interface JsonValue {
    data class Str(val value: String) : JsonValue

    data class Obj(val members: Map<String, JsonValue>) : JsonValue
}

/** Renders to canonical text per [CanonicalFormat]. The one place formatting is decided. */
internal fun JsonValue.renderCanonical(): String = buildString {
    appendValue(this@renderCanonical, depth = 0)
    if (CanonicalFormat.TRAILING_NEWLINE) append(CanonicalFormat.LINE_ENDING)
}

private fun StringBuilder.appendValue(value: JsonValue, depth: Int) {
    when (value) {
        is JsonValue.Str -> appendEscaped(value.value)
        is JsonValue.Obj -> appendObject(value, depth)
    }
}

private fun StringBuilder.appendObject(obj: JsonValue.Obj, depth: Int) {
    append('{')
    append(CanonicalFormat.LINE_ENDING)

    if (obj.members.isEmpty()) {
        if (CanonicalFormat.BLANK_LINE_IN_EMPTY_OBJECT) append(CanonicalFormat.LINE_ENDING)
        appendIndent(depth)
        append('}')
        return
    }

    // Sorted here, once, for every level of every object — see CanonicalFormat.MEMBER_ORDER.
    val sorted = obj.members.entries.sortedWith(compareBy(CanonicalFormat.MEMBER_ORDER) { it.key })
    sorted.forEachIndexed { index, (key, member) ->
        appendIndent(depth + 1)
        appendEscaped(key)
        append(CanonicalFormat.MEMBER_SEPARATOR)
        appendValue(member, depth + 1)
        if (index != sorted.lastIndex) append(',')
        append(CanonicalFormat.LINE_ENDING)
    }

    appendIndent(depth)
    append('}')
}

private fun StringBuilder.appendIndent(depth: Int) {
    repeat(depth) { append(CanonicalFormat.INDENT) }
}

private const val HEX_LOWER = "0123456789abcdef"
private const val HEX_UPPER = "0123456789ABCDEF"

/**
 * Writes a JSON string literal.
 *
 * Non-ASCII characters are emitted literally as UTF-8 rather than `\u`-escaped —
 * Xcode writes accented and CJK text directly. Hex digits are produced by table
 * lookup rather than `String.format`, which would consult the default locale and
 * so could vary between machines.
 */
private fun StringBuilder.appendEscaped(raw: String) {
    val hex = if (CanonicalFormat.LOWERCASE_HEX_ESCAPES) HEX_LOWER else HEX_UPPER
    append('"')
    for (ch in raw) {
        when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch == '\b' -> append("\\b")
            ch == '\u000C' -> append("\\f")
            ch < ' ' -> {
                val code = ch.code
                append("\\u")
                append(hex[(code shr 12) and 0xF])
                append(hex[(code shr 8) and 0xF])
                append(hex[(code shr 4) and 0xF])
                append(hex[code and 0xF])
            }
            else -> append(ch)
        }
    }
    append('"')
}
