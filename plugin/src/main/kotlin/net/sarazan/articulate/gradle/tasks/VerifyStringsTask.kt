package net.sarazan.articulate.gradle.tasks

import net.sarazan.articulate.core.convert.AndroidToXcstringsConverter
import net.sarazan.articulate.core.convert.MarkupPolicy
import net.sarazan.articulate.core.serialize.XcstringsWriter
import net.sarazan.articulate.gradle.reportDiagnostics
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * PLAN.md §5: the drift gate. **Declares inputs only, never outputs**, so it
 * can never report `UP-TO-DATE` and always re-verifies -- confirmed twice per
 * the plan: our own spec, and independently by ListenUp's production gate,
 * which carries the identical design ("a gate that must always re-verify"),
 * justified because the work is pure in-memory I/O and therefore cheap to
 * repeat every build.
 *
 * Regenerates the catalog in memory via the same [AndroidToXcstringsConverter]
 * pipeline the generate tasks use, then byte-compares it against the
 * committed file. [net.sarazan.articulate.core.serialize.XcstringsReader]
 * does not exist yet (parked on the pending Xcode fixture -- PLAN.md §1.4),
 * so per §5 this ships the byte-compare and a plain fix-command message,
 * without the structural added/removed/changed-key diff §5 describes as a
 * "diagnostic nicety" rather than part of the gate's guarantee.
 */
@UntrackedTask(
    because = "A drift gate must re-verify on every invocation. PLAN.md §5 requires it " +
        "never report UP-TO-DATE; declaring no outputs achieves that only incidentally, " +
        "whereas this states the intent and lets Gradle enforce it. Re-running is cheap: " +
        "the check is pure in-memory conversion plus a byte comparison.",
)
abstract class VerifyStringsTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringsDir: DirectoryProperty

    @get:Input
    abstract val sourceLanguage: Property<String>

    @get:Input
    abstract val localeOverrides: MapProperty<String, String>

    @get:Input
    abstract val markupPolicy: Property<MarkupPolicy>

    @get:Input
    abstract val warningsAsErrors: Property<Boolean>

    /**
     * Deliberately `@Internal`, not `@InputFile`/`@InputFiles`: this file is
     * legitimately absent on a first run before anyone has committed a
     * catalog, and that absence is exactly what the vacuous-gate guard below
     * must turn into a loud, specific failure (path + fix command). An
     * `@InputFile` annotation would have Gradle's own property validation
     * reject a missing file *before* this task action ever runs, with a
     * generic message carrying none of that detail. This is safe precisely
     * *because* the task declares no outputs at all: it never participates
     * in up-to-date-ness, so there is no incremental-build behavior this
     * annotation would otherwise be relied on for.
     */
    @get:Internal
    abstract val xcstringsFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val sourceDir = stringsDir.get().asFile
        val result = AndroidToXcstringsConverter.convert(sourceDir, sourceLanguage.get(), localeOverrides.get(), markupPolicy.get())
        reportDiagnostics(logger, result.diagnostics, warningsAsErrors.get(), "verifyStrings")

        // Vacuous-gate guard (§5, adopted from ListenUp): a gate that
        // verifies nothing manufactures false confidence, so zero parsed
        // keys is a hard failure, not a silent pass.
        if (result.catalog.entries.isEmpty()) {
            throw GradleException(
                "verifyStrings found zero keys under ${sourceDir.path} -- refusing to pass a gate that " +
                    "would verify nothing. Check that values/strings.xml exists and defines at least " +
                    "one <string> or <plurals>.",
            )
        }

        if (!xcstringsFile.isPresent) {
            throw GradleException(
                "verifyStrings: articulate { ios { catalog = file(...) } } is not configured -- nothing " +
                    "to verify against.",
            )
        }

        val committedFile = xcstringsFile.get().asFile
        if (!committedFile.isFile) {
            throw GradleException(
                "verifyStrings: expected catalog ${committedFile.path} does not exist. Run " +
                    "./gradlew generateStrings and commit the result.",
            )
        }

        val expectedBytes = XcstringsWriter.writeBytes(result.catalog)
        val actualBytes = committedFile.readBytes()

        if (!expectedBytes.contentEquals(actualBytes)) {
            throw GradleException(
                "${committedFile.path} is out of date with the strings source tree ($sourceDir) -- " +
                    "the ${result.catalog.entries.size} key(s) generated in memory do not byte-match " +
                    "the committed file. Run ./gradlew generateStrings and commit the result.",
            )
        }
    }
}
