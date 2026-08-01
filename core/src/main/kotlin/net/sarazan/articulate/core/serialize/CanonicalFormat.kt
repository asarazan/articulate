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
     * VERIFIED 2026-08-01 — **keep `1.0`; Xcode does not upgrade it.**
     *
     * Settled by round-tripping a real Articulate-generated catalog through the
     * Xcode GUI: the file was edited and saved, so Xcode fully rewrote it — and
     * `"version" : "1.0"` survived untouched. The only differences from our
     * output were the two semantically edited fields. Indentation, separators,
     * member ordering and the absent trailing newline all came back byte-identical.
     *
     * That doubles as the round-trip proof milestone 1's fixture protocol was
     * built to obtain: **a full Xcode rewrite of our output reproduces our
     * output.** Demonstrated, not argued.
     *
     * A *newly created* Xcode 26.6 catalog carries `"1.2"` (the Brief predicted
     * `1.1`, which was already stale). We deliberately do not match it: `1.0`
     * expresses everything we emit, is accepted without rewriting, and stays
     * readable to older Xcode versions. Emitting a newer schema version than our
     * content requires would trade compatibility for nothing.
     *
     * Also observed: editing a source-language string makes Xcode mark sibling
     * locales `needs_review`. Correct Xcode behavior, and it means hand-editing a
     * generated catalog produces exactly the drift §5's gate exists to catch.
     */
    const val VERSION: String = "1.0"

    /** Constant on every entry. Settled: the catalog is a build artifact. */
    const val EXTRACTION_STATE: String = "manual"

    /** Constant on every string unit. Settled: translation state lives upstream. */
    const val STATE: String = "translated"

    /** VERIFIED 2026-07-31 — two spaces per level. */
    const val INDENT: String = "  "

    /**
     * VERIFIED 2026-07-31 — Xcode writes a *spaced* colon (`"key" : "value"`), which
     * no standard JSON pretty-printer emits. This is the single most likely reason a
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

    /**
     * VERIFIED 2026-07-31 — **Xcode does NOT end the file with a newline.**
     *
     * This was provisionally `true` and was wrong. Apple's own `xcstringstool sync`
     * rewrites a catalog ending at the final `}` (last byte `0x7d`), with no
     * trailing `0x0a`. A single extra byte would make every generated catalog
     * differ from what Xcode writes, so Xcode would rewrite it on open — defeating
     * the entire drift gate this constant exists to support.
     */
    const val TRAILING_NEWLINE: Boolean = false

    /**
     * VERIFIED 2026-07-31 — Xcode emits a blank line inside an empty object:
     * ```
     * "strings" : {
     *
     * }
     * ```
     * rather than `{}`. Confirmed against `xcstringstool sync` output, which
     * rendered a keyless entry exactly this way.
     */
    const val BLANK_LINE_IN_EMPTY_OBJECT: Boolean = true

    /**
     * VERIFIED 2026-07-31 — lowercase hex in `\uXXXX` escapes (`\u0001`, not
     * `\u0001` uppercased). Also confirmed in the same run: non-ASCII is written
     * as literal UTF-8 rather than escaped (`café`, `日本語`, even U+00A0 survive
     * verbatim), and `\t` `\n` `\"` `\\` `\f` use their JSON shorthands.
     */
    const val LOWERCASE_HEX_ESCAPES: Boolean = true

    /**
     * Ordering for every object's members, at every level.
     *
     * VERIFIED 2026-07-31 — alphabetical at every level, matching UTF-16
     * code-unit ordinal, i.e. [naturalOrder]. This is
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
