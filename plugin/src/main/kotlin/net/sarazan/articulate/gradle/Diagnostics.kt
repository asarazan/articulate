package net.sarazan.articulate.gradle

import net.sarazan.articulate.core.diagnostics.Diagnostic
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger

/**
 * PLAN.md §4.7: logs every diagnostic from §2.7's channel at `WARN`, with its
 * file, key, and message ([Diagnostic.toString] already renders that shape).
 * When [warningsAsErrors] is `true`, fails the task instead -- listing every
 * offender, not just the first, so one build surfaces the whole set.
 *
 * Takes only a [Logger] (execution-time-safe, see `Task.getLogger()`) plus
 * plain data -- never a `Project` -- so every call site stays
 * configuration-cache compatible.
 */
internal fun reportDiagnostics(
    logger: Logger,
    diagnostics: List<Diagnostic>,
    warningsAsErrors: Boolean,
    taskName: String,
) {
    if (diagnostics.isEmpty()) return

    for (diagnostic in diagnostics) {
        logger.warn("{}: {}", taskName, diagnostic)
    }

    if (warningsAsErrors) {
        throw GradleException(
            buildString {
                append(taskName)
                append(": warningsAsErrors is true and ")
                append(diagnostics.size)
                append(" diagnostic(s) were found:\n")
                for (diagnostic in diagnostics) {
                    append("  - ")
                    append(diagnostic.toString())
                    append('\n')
                }
                append("Fix the source, or set articulate { warningsAsErrors = false } to downgrade these to advisory warnings.")
            },
        )
    }
}
