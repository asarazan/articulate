package net.sarazan.articulate.core.serialize

/**
 * Every formatting behaviour that must match Xcode byte-for-byte, in one object.
 *
 * ## Why this exists
 *
 * JSON has no comment syntax, so a generated `.xcstrings` cannot carry a
 * "DO NOT EDIT" header — and such headers are unenforceable anyway. The
 * enforcement mechanism is instead byte-determinism plus a CI no-op check:
 * regenerate, and if the committed file changes, the build fails. That only
 * works if our output is *exactly* what Xcode itself would write, so that
 * opening the file in Xcode never rewrites it.
 *
 * ## Provisional values
 *
 * Values marked PROVISIONAL below are best guesses pending the observed-Xcode
 * fixture (`core/src/test/fixtures/xcode/`, see its README). They are gathered
 * here specifically so that landing the fixture changes exactly one file. When
 * it lands: correct these, run `regenerateCorpus`, review the diff.
 *
 * Nothing downstream should hardcode any of these.
 */
object CanonicalFormat {

    /**
     * The catalog schema version Xcode writes.
     *
     * PROVISIONAL — highest-priority fixture question. Xcode 26 is documented to
     * have moved `1.0` → `1.1`; emitting the wrong one means Xcode rewrites the
     * file on open, which defeats the entire drift gate. Do not guess this in
     * production: the fixture decides it.
     */
    const val VERSION: String = "1.0"

    /** Constant on every entry. Settled: the catalog is a build artifact. */
    const val EXTRACTION_STATE: String = "manual"

    /** Constant on every string unit. Settled: translation state lives upstream. */
    const val STATE: String = "translated"

    /** PROVISIONAL — two spaces per level. */
    const val INDENT: String = "  "

    /**
     * PROVISIONAL — Xcode writes a *spaced* colon (`"key" : "value"`), which no
     * standard JSON pretty-printer emits. This is the single most likely reason a
     * library-based serializer would fail the no-op check, and the reason the
     * writer here is hand-rolled.
     */
    const val MEMBER_SEPARATOR: String = " : "

    /** LF, never CRLF. Not expected to be in question. */
    const val LINE_ENDING: String = "\n"

    /**
     * UTF-8, no BOM. [Charsets.UTF_8]'s encoder never emits a byte-order mark, so
     * this is effectively invariant rather than fixture-dependent — but it is a
     * named constant here regardless, so nothing downstream needs to know that
     * fact on its own.
     */
    val CHARSET: java.nio.charset.Charset = Charsets.UTF_8

    /** PROVISIONAL — file ends with a newline. */
    const val TRAILING_NEWLINE: Boolean = true

    /**
     * PROVISIONAL, moderate confidence — Xcode appears to emit a blank line inside
     * an empty object:
     * ```
     * "strings" : {
     *
     * }
     * ```
     * rather than `{}`. Believed from observed catalogs but unverified; an empty
     * `strings` object is worth including in the fixture explicitly.
     */
    const val BLANK_LINE_IN_EMPTY_OBJECT: Boolean = true

    /**
     * PROVISIONAL — lowercase hex in `\uXXXX` escapes.
     */
    const val LOWERCASE_HEX_ESCAPES: Boolean = true

    /**
     * Ordering for every object's members, at every level.
     *
     * PROVISIONAL — UTF-16 code-unit ordinal, i.e. [naturalOrder]. This is
     * deliberately *not* [String.compareTo] under a default locale and not any
     * collator: ordering must not vary with the machine's locale. The
     * "shuffled input produces identical bytes" and "Turkish locale produces
     * identical bytes" tests exist to hold this.
     *
     * Applies uniformly — top-level keys, entry members, locale tags, plural
     * categories — because Xcode appears to sort everything alphabetically. If
     * the fixture shows a level where it does not, that level needs its own
     * comparator rather than a change here.
     */
    val MEMBER_ORDER: Comparator<String> = naturalOrder()
}
