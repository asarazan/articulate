package net.sarazan.articulate.core.convert

/**
 * How to handle genuine inline styling markup (`<b>`, `<i>`, `<u>`,
 * `<annotation>`, `<font>`, `<br/>`, ...) inside a `<string>`/`<plurals>` item.
 *
 * Per PLAN.md D4, the enum ships in v0 so the DSL shape a caller builds
 * against doesn't change later. **`STRIP` is implemented as of v0.1
 * (2026-08-10, this issue's ruling)**, together with the three span-boundary
 * text rules it exposes (`docs/CONVERSIONS.md` M2/Q2/W2) -- the M3 audit
 * found those rules were detected but never built, so `STRIP` could not ship
 * against tested behavior until they were. `VERBATIM` remains unscheduled:
 * pure pass-through, lower priority, no caller yet.
 * [AndroidToXcstringsConverter.convert] takes `markupPolicy` as a parameter
 * and threads it through [net.sarazan.articulate.core.parse.AndroidStringsParser]/
 * [net.sarazan.articulate.core.parse.AndroidTextPipeline]. `plugin`'s
 * `ArticulatePlugin` still fails the build fast at configuration time for
 * [VERBATIM] -- see `ArticulateExtension.markupPolicy`.
 */
enum class MarkupPolicy {
    /** Hard error on any real span (default). */
    ERROR,

    /**
     * Strip the tag, keep the inner text -- implemented. The stripped text
     * still has to answer three questions AAPT2 itself answers in a specific
     * (and non-obvious) way at every span boundary: whitespace collapsing
     * resets (M2, "span-double-space"), edge trimming is suppressed for the
     * whole string (W2), and quoting state resets (Q2, "a quoted region
     * cannot span an inline tag"). See [net.sarazan.articulate.core.parse.ContentFlattener]
     * for how span boundaries are tracked and [net.sarazan.articulate.core.parse.AndroidTextPipeline]
     * for where they're applied.
     */
    STRIP,

    /** Pass the raw tag through as literal text. Unimplemented -- see class KDoc. */
    VERBATIM,
}
