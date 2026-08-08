package net.sarazan.articulate.gradle.tasks

import net.sarazan.articulate.core.convert.AndroidToXcstringsConverter
import net.sarazan.articulate.gradle.reportDiagnostics
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * PLAN.md §4.5b: validation gate for the strings source tree, replacing
 * `GenerateAndroidResTask`'s copy-and-validate step now that the copy itself
 * is dead work (verified premise: the copy was `copyTo`, nothing more) --
 * see PLAN.md §4.5b for the full genealogy.
 *
 * Runs the same [AndroidToXcstringsConverter.convert] pipeline
 * `generateXcstrings` uses, purely as a correctness gate, producing no
 * output: this is the task `ArticulatePlugin` wires as the
 * `articulateAndroidResElements` consumable artifact's `builtBy`, so
 * resolving that configuration *for a build* runs this first.
 *
 * **No declared outputs, [UntrackedTask]** -- exactly [net.sarazan.articulate.gradle.tasks.VerifyStringsTask]'s
 * reasoning: a gate must re-verify every time it participates in a real
 * build's task graph, not report `UP-TO-DATE` and skip. Re-running is cheap
 * (pure in-memory parse).
 */
@UntrackedTask(
    because = "A validation gate must re-run whenever it participates in a real build's task graph, " +
        "never report UP-TO-DATE. Declaring no outputs achieves that only incidentally; this states " +
        "the intent and lets Gradle enforce it. Re-running is cheap: pure in-memory parse.",
)
abstract class ValidateStringsTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringsDir: DirectoryProperty

    @get:Input
    abstract val sourceLanguage: Property<String>

    @get:Input
    abstract val localeOverrides: MapProperty<String, String>

    @get:Input
    abstract val warningsAsErrors: Property<Boolean>

    @TaskAction
    fun validate() {
        val sourceDir = stringsDir.get().asFile
        val result = AndroidToXcstringsConverter.convert(sourceDir, sourceLanguage.get(), localeOverrides.get())
        reportDiagnostics(logger, result.diagnostics, warningsAsErrors.get(), "validateStrings")
    }
}
