package net.sarazan.articulate.core.convert

/**
 * How to handle genuine inline styling markup (`<b>`, `<i>`, `<u>`,
 * `<annotation>`, `<font>`, `<br/>`, ...) inside a `<string>`/`<plurals>` item.
 *
 * Per PLAN.md D4, the enum ships in v0 so the DSL shape a caller builds
 * against doesn't change later, even though only [ERROR] is implemented this
 * milestone -- [STRIP] and [VERBATIM] are deferred (`STRIP` to v0.1 per the
 * plan, `VERBATIM` unscheduled). [AndroidToXcstringsConverter] always behaves
 * as [ERROR] today; there is deliberately no parameter wiring this in yet,
 * since adding one before a second policy exists would be speculative API
 * surface with no caller. `plugin`'s `ArticulatePlugin` fails the build fast
 * at configuration time if a consumer sets anything other than [ERROR], so
 * this gap is never reached silently -- see `ArticulateExtension.markupPolicy`.
 */
enum class MarkupPolicy {
    /** Hard error on any real span (default, and the only implemented behavior). */
    ERROR,

    /** Strip the tag, keep the inner text. Unimplemented -- see class KDoc. */
    STRIP,

    /** Pass the raw tag through as literal text. Unimplemented -- see class KDoc. */
    VERBATIM,
}
