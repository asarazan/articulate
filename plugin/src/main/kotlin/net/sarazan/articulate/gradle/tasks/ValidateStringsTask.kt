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
 * PLAN.md §4.5b: the gate that replaces `GenerateAndroidResTask`'s validation
 * side-effect once that task's only other job -- copying an already-valid
 * Android resource tree byte-for-byte -- is deleted as dead work (§4.5b's
 * verified premise: the copy was `copyTo`, nothing more).
 *
 * Runs the exact same [AndroidToXcstringsConverter.convert] pipeline
 * `GenerateAndroidResTask` used to run purely as a correctness gate --
 * every D4/D6/D5b/§4.3 hard error in `docs/CONVERSIONS.md` -- so a strings
 * tree that would fail `generateXcstrings` fails the Android build too,
 * rather than only surfacing on the iOS side. Produces no output: the
 * **set** of rejected inputs must not change from what `GenerateAndroidResTask`
 * rejected, and this is the mechanism `ArticulatePlugin` wires as the
 * `articulateAndroidResElements` consumable artifact's `builtBy`, so
 * resolving that configuration *for a build* runs this first (§4.5b point 2).
 *
 * **No declared outputs, [UntrackedTask]** -- exactly [net.sarazan.articulate.gradle.tasks.VerifyStringsTask]'s
 * reasoning: a gate must re-verify every time it participates in a real
 * build's task graph, not report `UP-TO-DATE` and skip. Re-running is cheap
 * (pure in-memory parse), and per §4.5b, resolving the consumable
 * configuration merely *for a path* (e.g. at IDE sync) never runs this task
 * at all -- only a real task graph edge (via `builtBy`) does, and only then
 * should the parse actually happen.
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
