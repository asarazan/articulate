package net.sarazan.articulate.core.diagnostics

import net.sarazan.articulate.core.parse.XmlPosition

/**
 * Severity of a [Diagnostic].
 *
 * Only [WARNING] is ever constructed today: every hard-error rule in
 * `docs/CONVERSIONS.md` already fails fast through
 * [net.sarazan.articulate.core.parse.ConversionException] (PLAN.md §2's goal
 * -- "anything not explicitly supported is a hard parse/convert error, never
 * a pass-through guess"), so nothing in `core` needs to *also* describe an
 * error as a non-fatal diagnostic. The enum still names [WARNING] explicitly
 * rather than collapsing this to a boolean, so a future non-error severity
 * has somewhere to go without another signature change.
 */
enum class Severity { WARNING }

/**
 * One non-fatal finding surfaced while converting, per PLAN.md §2.7.
 *
 * Two rules produce these today: **M5** (a foreign-namespace tag is warned
 * on, dropped, its children kept) and **K1** (a key containing `.`/`-`/
 * non-ASCII is warned on but still accepted, since Xcode's
 * `xcstringstool generate-symbols` and Kotlin/Java `R.` access both need
 * plain-ASCII-identifier keys to generate anything usable).
 */
data class Diagnostic(
    val severity: Severity,
    val position: XmlPosition,
    val key: String?,
    val message: String,
) {
    /** `path:line: key 'x': message`, matching [net.sarazan.articulate.core.parse.ConversionException]'s shape. */
    override fun toString(): String {
        val where = if (key != null) "$position: key '$key': " else "$position: "
        return "$where$message"
    }
}
