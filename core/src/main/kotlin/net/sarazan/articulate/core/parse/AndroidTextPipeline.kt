package net.sarazan.articulate.core.parse

/**
 * Implements pipeline stages S2-S4 from `docs/CONVERSIONS.md` §1: edge trim,
 * the reference probe, and the backslash-escape/quote/whitespace-collapse pass
 * that AAPT2 calls `StringBuilder`.
 *
 * Callers reach this once S1 (subtree flattening, [ContentFlattener]) has
 * run. Under `markupPolicy = ERROR` (default) a real span is a hard error
 * before this pipeline ever runs, so [hasRealSpan]/[spanBoundaries] are
 * always `false`/empty on that path. Under `markupPolicy = STRIP` a real
 * span's tag is already gone from [ContentFlattener]'s output text, but two
 * things still change here per `docs/CONVERSIONS.md`: **W2** -- edge
 * trimming (S2) is skipped entirely for the whole string when [hasRealSpan]
 * is `true`, not just around the span -- and **M2/Q2** -- AAPT2's
 * `ResetTextState()` clears both the whitespace-collapse flag and the
 * quoting flag at every offset in [spanBoundaries], so a space or open quote
 * immediately adjacent to a stripped tag is never merged across that
 * boundary with whitespace/quoting on the other side.
 */
internal object AndroidTextPipeline {

    /**
     * Runs S2-S4 over [raw] (already S0/S1-flattened: entities resolved,
     * `xliff:g`/foreign-namespace tags unwrapped). [hasRealSpan] and
     * [spanBoundaries] come straight from [FlattenedContent] -- see the
     * class KDoc for what they change under `markupPolicy = STRIP`.
     *
     * Throws [ConversionException] for: an unescaped leading `@`/`?` (K4 — the
     * raw text would silently become an Android reference we cannot resolve),
     * a bad `\uXXXX` escape (E2), a rejected `\r` (E1 — Android's own bug, not
     * reproduced here), a bare apostrophe outside quoting (A1), or an odd
     * number of unescaped `"` (Q1 — Articulate's own stricter rule).
     */
    fun process(
        raw: String,
        position: XmlPosition,
        key: String?,
        hasRealSpan: Boolean = false,
        spanBoundaries: List<Int> = emptyList(),
    ): String {
        // W2: edge trim (S2) applies only when no span was seen anywhere in
        // the string. Trimming here would also invalidate the offsets in
        // [spanBoundaries] (computed against the untrimmed rawText), which is
        // exactly why W2 and this ordering constraint travel together.
        val trimmed = if (hasRealSpan) raw else raw.trim(::isAsciiWhitespace)
        if (trimmed.isNotEmpty() && (trimmed[0] == '@' || trimmed[0] == '?')) {
            throw ConversionException(
                position,
                key,
                "unescaped leading '${trimmed[0]}' makes this value an Android reference " +
                    "(e.g. @string/other), which Articulate cannot resolve. Escape it as " +
                    "'\\${trimmed[0]}' if you meant the literal character, or inline the " +
                    "referenced value directly.",
            )
        }
        return build(trimmed, spanBoundaries, position, key)
    }

    private fun build(raw: String, spanBoundaries: List<Int>, position: XmlPosition, key: String?): String {
        val out = StringBuilder()
        var i = 0
        var quoted = false
        var lastWasCollapsedSpace = false
        var boundaryIndex = 0
        // Q1's odd-quote-count check must count literal '"' characters, not
        // read the final value of [quoted] -- a span boundary forcibly resets
        // [quoted] to false without a quote character being consumed, so an
        // even number of real quotes around a span ("x <b>y</b> z") would
        // otherwise look odd to a check that only inspects the final toggle
        // state. [quoted] itself still drives whitespace-collapse/apostrophe
        // behavior and is still reset at boundaries, per Q2 -- only the
        // end-of-string oddness check needs the independent raw count.
        var quoteCount = 0

        fun emit(c: Char) {
            out.append(c)
            lastWasCollapsedSpace = false
        }

        while (i < raw.length) {
            // M2/Q2: a span boundary resets both the whitespace-collapse flag
            // and the quoting flag, exactly as AAPT2's ResetTextState() does
            // at every span start and end. A `while`, not an `if`, because an
            // empty span (`<b></b>`) or two adjacent spans contribute equal
            // or consecutive offsets that must all fire before index [i]
            // advances. Compares with `<=`, not `==`: [i] advances by more
            // than one inside the escape branch below (2 for `\n`, 6 for
            // `\uXXXX`), so a boundary offset that lands inside an escape is
            // skipped over and would never equal [i] again -- draining with
            // `<=` still fires it (once [i] catches up or passes it) instead
            // of wedging [boundaryIndex] there forever and silently disabling
            // every later boundary reset in the string.
            while (boundaryIndex < spanBoundaries.size && spanBoundaries[boundaryIndex] <= i) {
                lastWasCollapsedSpace = false
                quoted = false
                boundaryIndex++
            }
            val c = raw[i]
            when {
                c == '\\' -> {
                    if (i + 1 >= raw.length) {
                        // E3: trailing lone backslash is silently dropped.
                        i++
                        continue
                    }
                    val n = raw[i + 1]
                    when (n) {
                        't' -> { emit('\t'); i += 2 }
                        'n' -> { emit('\n'); i += 2 }
                        '#' -> { emit('#'); i += 2 }
                        '@' -> { emit('@'); i += 2 }
                        '?' -> { emit('?'); i += 2 }
                        '"' -> { emit('"'); i += 2 } // E1: escaped quote never toggles quoting
                        '\'' -> { emit('\''); i += 2 }
                        '\\' -> { emit('\\'); i += 2 }
                        'u' -> {
                            if (i + 6 > raw.length || !isHex4(raw, i + 2)) {
                                throw ConversionException(
                                    position,
                                    key,
                                    "invalid unicode escape sequence in string \"${raw.excerpt(i)}\" " +
                                        "-- \\u must be followed by exactly 4 hex digits",
                                )
                            }
                            val code = raw.substring(i + 2, i + 6).toInt(16)
                            emit(code.toChar())
                            i += 6
                        }
                        'r' -> throw ConversionException(
                            position,
                            key,
                            "escape sequence '\\r' is not a carriage return in Android string " +
                                "resources -- AAPT2 drops the backslash and emits a literal 'r', " +
                                "which is almost never what was intended. Use \\n for a line break, " +
                                "or remove the backslash if a literal 'r' is really wanted.",
                        )
                        else -> { emit(n); i += 2 } // E1: unknown escape -> backslash dropped
                    }
                }
                c == '"' -> {
                    quoted = !quoted
                    quoteCount++
                    i++
                }
                c == '\'' && !quoted -> throw ConversionException(
                    position,
                    key,
                    "unescaped apostrophe in string \"${raw.excerpt(i)}\" -- escape it as \\' or " +
                        "wrap the value in double quotes",
                )
                isAsciiWhitespace(c) && !quoted -> {
                    if (!lastWasCollapsedSpace) {
                        out.append(' ')
                        lastWasCollapsedSpace = true
                    }
                    i++
                }
                else -> {
                    emit(c)
                    i++
                }
            }
        }

        if (quoteCount % 2 != 0) {
            throw ConversionException(
                position,
                key,
                "odd number of unescaped '\"' characters -- a single stray quote turns on " +
                    "quoting for the rest of the string, silently changing its meaning. " +
                    "Escape it as \\\" if a literal quote was intended.",
            )
        }
        return out.toString()
    }
}

/**
 * Guarded to ASCII per W3 -- non-ASCII Unicode whitespace (U+00A0, U+2003,
 * U+2008, ...) is deliberately *not* whitespace here, matching AAPT2's
 * `codepoint <= 0x7F` guard, even though the official Android docs claim
 * otherwise (verified wrong against real AAPT2 -- see CONVERSIONS.md W3).
 */
internal fun isAsciiWhitespace(c: Char): Boolean =
    c.code <= 0x7F && (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C')

private fun isHex4(s: String, start: Int): Boolean {
    for (offset in 0 until 4) {
        if (!isHexDigit(s[start + offset])) return false
    }
    return true
}

private fun isHexDigit(c: Char): Boolean =
    c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

/** A short window around [at] for error messages, mirroring aapt2's quoted-fragment style. */
private fun String.excerpt(at: Int, radius: Int = 20): String {
    val start = (at - radius).coerceAtLeast(0)
    val end = (at + radius).coerceAtMost(length)
    return substring(start, end)
}
