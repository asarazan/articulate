package net.sarazan.articulate.core.convert

import net.sarazan.articulate.core.parse.ConversionException
import net.sarazan.articulate.core.parse.XmlPosition

/**
 * One `%`-specifier found in a built Android string, before conversion.
 *
 * `argumentIndex` is null for a non-positional specifier (`%s`) and 1-based
 * for a positional one (`%1$s`). `kind` is `null` only for `%%`, which
 * consumes no argument and is excluded from the positional-discipline count.
 */
private data class Specifier(
    val raw: String,
    val argumentIndex: Int?,
    val flags: String,
    val width: String,
    val precision: String?,
    val conversion: Char,
    val startIndex: Int,
)

/**
 * Converts Android/Java `Formatter` placeholders in an already Android-built
 * string (post [net.sarazan.articulate.core.parse.AndroidTextPipeline]) into
 * their iOS/Foundation equivalents, per `docs/CONVERSIONS.md` §8 (P0-P7).
 *
 * Implements Articulate's *own* positional-discipline and specifier-legality
 * scan rather than trusting AAPT2's -- research found two verified holes in
 * AAPT2's own checker (styled strings skip it entirely; any of `D F K M W Z k
 * m w y z` as a conversion char short-circuits it to "pass") that this
 * project cannot inherit. See P0.
 */
internal object PlaceholderConverter {

    /**
     * Converts [value]'s placeholders. When [formatted] is false (Android's
     * `formatted="false"`), specifier syntax is not interpreted at all -- every
     * literal `%` is doubled to `%%` instead, since `.xcstrings` has no
     * equivalent opt-out of iOS's own format interpretation (K7).
     */
    fun convert(value: String, formatted: Boolean, position: XmlPosition, key: String?): String {
        if (!formatted) return value.replace("%", "%%")

        val specifiers = scan(value, position, key)
        // Per-specifier legality is checked *before* positional discipline, and the
        // order is load-bearing for the error message. `%y and %s and %d` (the
        // `error-time-format-shortcircuit` case) violates both rules at once: it uses
        // an unconvertible conversion character *and* has three unnumbered arguments.
        // Reporting "non-positional format" first would send an author who typed `%y`
        // off to add position numbers, only to hit a second error about the specifier
        // they actually got wrong. An unsupported conversion character also means the
        // argument list cannot be interpreted at all, so it is the more fundamental of
        // the two defects as well as the more actionable one.
        val converted = rewrite(value, specifiers, position, key)
        checkPositionalDiscipline(specifiers, position, key)
        return converted
    }

    /** iOS-side conversion tokens, longest first so "lld" isn't mistaken for "l" + literal "ld". */
    private val CONVERTED_TOKENS = listOf("lld", "llx", "llX", "llo", "@", "f", "e", "E")

    /**
     * Each specifier's argument index -> its iOS conversion token, for
     * locale-parity checks (P7). Position-indexed rather than a plain
     * unordered multiset: `en: "%1$@ %2$lld"` vs `de: "%1$lld %2$@"` has an
     * *identical* multiset but swaps which argument is which type -- exactly
     * the "positional type swap" `xcstringstool` was verified not to catch
     * (P7), which this project's whole thesis is that Articulate must. A
     * string with fewer than two specifiers has no explicit index (positional
     * discipline only requires one when there are >=2); it is keyed as
     * argument 1 implicitly.
     */
    fun specifierTypesByIndex(convertedValue: String): Map<Int, String> {
        val found = scanConverted(convertedValue)
        return if (found.size == 1 && found[0].first == null) {
            mapOf(1 to found[0].second)
        } else {
            found.associate { (index, token) -> (index ?: 1) to token }
        }
    }

    /** True if [convertedValue] references the substituted number via a non-object specifier (T2). */
    fun hasNumericSpecifier(convertedValue: String): Boolean = scanConverted(convertedValue).any { it.second != "@" }

    /** Finds each already-converted specifier's argument index (null if implicit) and conversion token. */
    private fun scanConverted(s: String): List<Pair<Int?, String>> {
        val result = mutableListOf<Pair<Int?, String>>()
        var i = 0
        while (i < s.length) {
            if (s[i] != '%' || i + 1 >= s.length) {
                i++
                continue
            }
            if (s[i + 1] == '%') {
                i += 2
                continue
            }
            var j = i + 1
            var index: Int? = null
            val indexStart = j
            while (j < s.length && s[j].isDigit()) j++
            if (j < s.length && s[j] == '$' && j > indexStart) {
                index = s.substring(indexStart, j).toInt()
                j++
            } else {
                j = indexStart
            }
            while (j < s.length && s[j] in "-+ 0#") j++
            while (j < s.length && s[j].isDigit()) j++
            if (j < s.length && s[j] == '.') {
                j++
                while (j < s.length && s[j].isDigit()) j++
            }
            val token = CONVERTED_TOKENS.firstOrNull { s.startsWith(it, j) }
            if (token != null) {
                j += token.length
                result += (index to token)
                i = j
            } else {
                i++
            }
        }
        return result
    }

    private fun scan(value: String, position: XmlPosition, key: String?): List<Specifier> {
        val result = mutableListOf<Specifier>()
        var i = 0
        while (i < value.length) {
            if (value[i] != '%') {
                i++
                continue
            }
            if (i + 1 >= value.length) {
                throw ConversionException(position, key, "malformed format specifier: '%' at end of string")
            }
            if (value[i + 1] == '%') {
                i += 2
                continue
            }
            if (value[i + 1] == '<') {
                throw ConversionException(
                    position,
                    key,
                    "argument reuse ('%<') has no iOS equivalent -- translators may reorder " +
                        "arguments, so each substitution must name its own position explicitly " +
                        "(e.g. '%1\$s')",
                )
            }

            val start = i
            var j = i + 1

            var argumentIndex: Int? = null
            val indexStart = j
            while (j < value.length && value[j].isDigit()) j++
            if (j < value.length && value[j] == '$' && j > indexStart) {
                argumentIndex = value.substring(indexStart, j).toInt()
                j++
            } else {
                j = indexStart
            }

            val flagsStart = j
            while (j < value.length && value[j] in "-+ 0#,(") j++
            val flags = value.substring(flagsStart, j)

            val widthStart = j
            while (j < value.length && value[j].isDigit()) j++
            val width = value.substring(widthStart, j)

            var precision: String? = null
            if (j < value.length && value[j] == '.') {
                j++
                val precisionStart = j
                while (j < value.length && value[j].isDigit()) j++
                precision = value.substring(precisionStart, j)
            }

            if (j >= value.length) {
                throw ConversionException(position, key, "malformed format specifier '${value.substring(start)}'")
            }
            val conversion = value[j]
            j++

            if ((conversion == 't' || conversion == 'T') && j < value.length) {
                j++ // consume the date/time suffix char so the error points at the whole thing
            }

            result += Specifier(value.substring(start, j), argumentIndex, flags, width, precision, conversion, start)
            i = j
        }
        return result
    }

    private fun checkPositionalDiscipline(specifiers: List<Specifier>, position: XmlPosition, key: String?) {
        if (specifiers.size < 2) return
        if (specifiers.any { it.argumentIndex == null }) {
            throw ConversionException(
                position,
                key,
                "multiple substitutions specified in non-positional format; use explicit " +
                    "position numbers (e.g. '%1\$s %2\$d') on every specifier -- translators may " +
                    "reorder arguments, and unnumbered reordering silently corrupts the result",
            )
        }
    }

    private fun rewrite(value: String, specifiers: List<Specifier>, position: XmlPosition, key: String?): String {
        if (specifiers.isEmpty()) return value
        val out = StringBuilder()
        var cursor = 0
        for (spec in specifiers) {
            out.append(value, cursor, spec.startIndex)
            out.append(convertOne(spec, position, key))
            cursor = spec.startIndex + spec.raw.length
        }
        out.append(value, cursor, value.length)
        return out.toString()
    }

    private fun convertOne(spec: Specifier, position: XmlPosition, key: String?): String {
        val prefix = buildString {
            append('%')
            if (spec.argumentIndex != null) append(spec.argumentIndex).append('$')
        }

        fun error(detail: String): Nothing = throw ConversionException(position, key, "'${spec.raw}': $detail")

        return when (spec.conversion) {
            's' -> {
                if (spec.precision != null) {
                    error(
                        "precision on a string specifier has no iOS equivalent -- '%@' does not " +
                            "honor precision, so the truncation would silently vanish. Truncate the " +
                            "value before formatting instead",
                    )
                }
                if (spec.width.isNotEmpty() || spec.flags.isNotEmpty()) {
                    error("width/flags on '%s' are not supported -- '%@' does not honor them")
                }
                "$prefix@"
            }

            'S' -> error(
                "'%S' means something different on each platform (Java: uppercased string; " +
                    "Foundation: a null-terminated UTF-16 array) -- there is no safe conversion",
            )

            'd' -> {
                requireNoIntegerPrecision(spec, ::error)
                requireNoGroupingOrParens(spec, ::error)
                "$prefix${spec.flags}${spec.width}lld"
            }

            'x', 'X', 'o' -> {
                requireNoIntegerPrecision(spec, ::error)
                requireNoGroupingOrParens(spec, ::error)
                val suffix = if (spec.conversion == 'x') "llx" else if (spec.conversion == 'X') "llX" else "llo"
                "$prefix${spec.flags}${spec.width}$suffix"
            }

            'f', 'e', 'E' -> spec.raw

            'g', 'G' -> error(
                "'%g'/'%G' are not compatible across platforms: Java keeps trailing zeros " +
                    "(1.0 -> \"1.00000\"), C/Swift strip them (1.0 -> \"1\"), and there is no " +
                    "faithful mechanical rewrite. Use '%.Nf' or '%e' instead",
            )

            'a', 'A' -> error("hex-float specifiers ('%a'/'%A') are unsupported -- they are rare enough to reject rather than guess at")

            'c', 'C' -> error(
                "'%c'/'%C' mean something different on each platform (Java: a Unicode character; " +
                    "Foundation: an 8-bit unsigned char / one UTF-16 unit) -- there is no safe conversion",
            )

            'n' -> error("'%n' (platform line separator) has no Foundation equivalent -- use a literal '\\n' instead")

            'b', 'B', 'h', 'H' -> error("'%${spec.conversion}' is a Java-only conversion with no iOS equivalent")

            't', 'T' -> error("date/time format specifiers ('%t...') are a Java-only conversion family with no iOS equivalent")

            // Every conversion character in AAPT2's Time-format short-circuit set
            // (`D F K M W Z k m w y z`, see P0) lands here, which is how Articulate
            // closes that hole: AAPT2 abandons its own scan on seeing one of them and
            // compiles the string clean, whereas here it is simply an unknown
            // conversion and is rejected outright.
            else -> error(
                "unsupported format specifier '%${spec.conversion}' -- Articulate converts only " +
                    "%s, %d, %f, %e, %E, %x, %X, %o and a literal %%. Fix the specifier, or use " +
                    "formatted=\"false\" if this '%' was meant literally",
            )
        }
    }

    private inline fun requireNoIntegerPrecision(spec: Specifier, error: (String) -> Nothing) {
        if (spec.precision != null) {
            error("precision is not valid on an integer conversion")
        }
    }

    private inline fun requireNoGroupingOrParens(spec: Specifier, error: (String) -> Nothing) {
        if (',' in spec.flags) {
            error(
                "the ',' grouping flag has no Foundation equivalent -- use NumberFormatter / " +
                    "formatted(.number) on the iOS side for locale-aware grouping",
            )
        }
        if ('(' in spec.flags) {
            error("the '(' parenthesize-negatives flag has no Foundation equivalent")
        }
    }
}
