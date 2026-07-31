package net.sarazan.articulate.gradle.tasks

import net.sarazan.articulate.core.convert.AndroidToXcstringsConverter
import net.sarazan.articulate.gradle.reportDiagnostics
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * PLAN.md §4.4: regenerates the per-locale Android `values-<tag>/strings.xml`
 * tree from the strings source tree. Disposable -- never committed, under a
 * build directory (the "narrow output declaration" lesson from ListenUp, §4.1).
 *
 * **Where the output lands is not this task's decision** whenever
 * [net.sarazan.articulate.gradle.ArticulateAndroidPlugin] is wiring it:
 * `addGeneratedSourceDirectory(task, wiredWith)` makes AGP set [androidResDir]
 * itself, so the tree materializes under the *consuming app project's* build
 * dir (`app/build/generated/res/generateAndroidRes/`), not this module's.
 * §4.4, corrected 2026-07-30 against a real built fixture. The convention set
 * by `ArticulatePlugin` (`build/generated/i18n/res`) therefore applies only
 * when nothing wires the property -- do not reason about it when debugging a
 * variant-wired build.
 *
 * Runs the full [AndroidToXcstringsConverter.convert] pipeline (parse +
 * every cross-locale validation rule in `docs/CONVERSIONS.md`) purely as a
 * correctness gate, so a source tree that would fail `generateXcstrings`
 * fails the Android build too, rather than only surfacing on the iOS side --
 * then discards the resulting [net.sarazan.articulate.core.model.StringCatalog]
 * and copies each already-validated `values-<tag>/strings.xml` byte-for-byte into
 * the output tree under its original Android directory name, since AGP needs
 * Android-native resource XML, not the xcstrings model. §4.4 explicitly
 * requires both generate tasks to parse+convert independently rather than
 * share an intermediate (a config-cache hazard) -- this duplicates
 * [GenerateXcstringsTask]'s parse deliberately.
 */
@CacheableTask
abstract class GenerateAndroidResTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringsDir: DirectoryProperty

    @get:Input
    abstract val sourceLanguage: Property<String>

    @get:Input
    abstract val localeOverrides: MapProperty<String, String>

    @get:Input
    abstract val warningsAsErrors: Property<Boolean>

    /**
     * `build/generated/i18n/res` by convention (set by
     * [net.sarazan.articulate.gradle.ArticulatePlugin]) -- but **overridden by
     * AGP** whenever `net.sarazan.articulate.android` wires this property via
     * `addGeneratedSourceDirectory`; see this class's KDoc.
     */
    @get:OutputDirectory
    abstract val androidResDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val sourceDir = stringsDir.get().asFile
        val result = AndroidToXcstringsConverter.convert(sourceDir, sourceLanguage.get(), localeOverrides.get())
        reportDiagnostics(logger, result.diagnostics, warningsAsErrors.get(), "generateAndroidRes")

        val outDir = androidResDir.get().asFile
        // Disposable output (§4.4): cleared and rewritten wholesale each run
        // rather than incrementally patched, so a locale directory removed
        // from source can never linger as a stale generated one.
        outDir.deleteRecursively()
        outDir.mkdirs()

        val localeDirs = sourceDir.listFiles { f -> f.isDirectory }.orEmpty()
            .filter { it.name == "values" || it.name.startsWith("values-") }
            .filter { File(it, "strings.xml").isFile }

        for (dir in localeDirs) {
            val targetDir = File(outDir, dir.name).apply { mkdirs() }
            File(dir, "strings.xml").copyTo(File(targetDir, "strings.xml"), overwrite = true)
        }
    }
}
