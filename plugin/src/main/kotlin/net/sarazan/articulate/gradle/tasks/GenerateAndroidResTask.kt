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
 * **[androidResDir] always lands at `ArticulatePlugin`'s convention
 * (`build/generated/i18n/res`)** -- unlike before the §4.5/§13 cross-project
 * redesign (2026-08-01), [net.sarazan.articulate.gradle.ArticulateAndroidPlugin]
 * no longer wires AGP's `addGeneratedSourceDirectory` onto this task directly
 * (that would compare this class's identity across a classloader boundary
 * whenever the two plugin IDs are applied from separate module `plugins {}`
 * blocks -- the release-blocking bug the redesign fixes). This module's
 * generated tree is instead exposed as a consumable configuration
 * (`articulateAndroidResElements`) and consumed by a task the app module's
 * own plugin instance registers -- see `ArticulateAndroidResSharing.kt` and
 * `ResolveArticulateAndroidResTask`. *That* task, not this one, is what AGP
 * ends up relocating into the consuming app project's build dir.
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

        // §4.3: discovery/classification lives in core, not here -- this task
        // used to filter for "values*/strings.xml" itself, which is exactly
        // how a companion plurals.xml/arrays.xml got silently dropped with no
        // error. AndroidToXcstringsConverter.convert() above already threw if
        // any such file exists; this just reuses the same validated list
        // rather than re-deriving it independently.
        val localeDirs = AndroidToXcstringsConverter.discoverLocaleDirectories(sourceDir)

        for (dir in localeDirs) {
            val targetDir = File(outDir, dir.name).apply { mkdirs() }
            File(dir, "strings.xml").copyTo(File(targetDir, "strings.xml"), overwrite = true)
        }
    }
}
