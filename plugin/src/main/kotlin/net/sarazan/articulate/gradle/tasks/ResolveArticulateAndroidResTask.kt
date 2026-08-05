package net.sarazan.articulate.gradle.tasks

import net.sarazan.articulate.core.convert.AndroidToXcstringsConverter
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * PLAN.md §4.5/§13/§4.5b: lives entirely in the **app** module, registered by
 * `net.sarazan.articulate.android`'s own plugin instance -- never a class
 * the i18n-owning project's `net.sarazan.articulate` instance created. That
 * is what makes it safe to hand to AGP's
 * `addGeneratedSourceDirectory(taskProvider, ThisClass::outputDir)`: the
 * method reference and the task provider both originate in the app module's
 * own plugin classloader, so no class identity is ever compared across a
 * classloader boundary.
 *
 * Materializes [resolvedRes] -- the files resolved from the
 * `articulateAndroidResIncoming` configuration, i.e. the i18n-owning
 * project's strings *source* directory as of §4.5b (previously its
 * `generateAndroidRes` task's generated output) -- into [outputDir]. The
 * copy is required, not merely incidental: `addGeneratedSourceDirectory`
 * makes AGP *own* [outputDir]'s final location (relocating it into `:app`'s
 * own build dir on both the AGP 8.5.2 and 9.1 cells this plugin targets --
 * verified empirically, both converge on
 * `app/build/generated/res/resolveArticulateAndroidRes/`), so this task
 * must actually write into wherever AGP ultimately points that property, not
 * merely expose the resolved files as-is.
 *
 * **Selective by discovery, not a blanket recursive copy.** §4.5b's premise
 * is that the resolved artifact is now :i18n's real *source* directory
 * rather than a task's clean, strings-only output -- and per §4.3, that
 * directory is explicitly allowed to be a real `app/src/main/res` a
 * consumer already had (colors.xml, drawables, styles, and all), or, more
 * mundanely, just carry a stray `.DS_Store` (observed in this repo's own
 * `sample/i18n`). A naive `File.copyRecursively` over the whole resolved
 * directory would copy every one of those into AGP's resource merge for
 * *this app module too* -- silently duplicating unrelated resources, or
 * worse, colliding with the app's own. [AndroidToXcstringsConverter.discoverLocaleDirectories]
 * is the same content-based discovery §4.3's multi-file rule already
 * established core-side (and `GenerateAndroidResTask` used, before it was
 * deleted) -- reusing it here keeps this task's copy scoped
 * to exactly `strings.xml` under each `values` / `values-<tag>` directory,
 * nothing else, matching what actually gets validated.
 *
 * **§4.5b's `addStaticSourceDirectory` alternative was prototyped and
 * abandoned -- see `ArticulateAndroidPlugin`'s KDoc for the full account.**
 * In short: AGP's `SourceDirectories.addStaticSourceDirectory(String)` takes
 * a plain path, not a `Provider`, so getting a real cross-project path into
 * it requires eagerly resolving [resolvedRes]'s configuration. Every point in
 * the configuration phase where that resolution is *safe* (i.e. does not
 * collide with Gradle's "cannot create a new configuration in this project
 * once any configuration has been resolved" rule, which AGP trips on when it
 * later creates a subsequent variant's own configurations) is *after* AGP
 * has already snapshotted each variant's resource source-directory list --
 * confirmed directly by inspecting `mapDebugSourceSetPaths`'s output, which
 * never contained the statically-added directory no matter how late (short
 * of literally being ignored) the call was deferred. There is no timing
 * window in which both conditions hold under AGP 8.5.2 (the D9 floor at the
 * time this was probed; the floor has since moved to AGP 9.1.0, PLAN.md
 * §E2). This task's copy-based approach is therefore still the
 * mechanism that actually gets content into AGP's resource merge; it does
 * **not** fix the pre-existing IDE-visibility defect recorded in PLAN.md
 * §4.5 ("IDE visibility of generated Android res -- OPEN DEFECT") --
 * that defect remains open.
 *
 * `resolvedRes` is still declared `@InputFiles` against the resolvable
 * configuration for the reason unrelated to content: that is what carries
 * the implicit build-graph dependency on `validateStrings` (via the
 * configuration artifact's `builtBy`, PLAN.md §4.5b point 2) into a real
 * build whenever AGP schedules this task as part of a variant's resource
 * processing, so resolving the configuration *for a build* still runs
 * validation first.
 */
@CacheableTask
abstract class ResolveArticulateAndroidResTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resolvedRes: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun resolve() {
        val out = outputDir.get().asFile
        // Disposable output (mirrors the deleted GenerateAndroidResTask,
        // §4.4): cleared and rewritten wholesale each run so a locale
        // directory removed upstream can never linger here as a stale
        // generated one.
        out.deleteRecursively()
        out.mkdirs()
        for (source in resolvedRes.files) {
            if (!source.isDirectory) continue
            // §4.3/§4.5b (see this class's KDoc): discovery-scoped, not a
            // blanket recursive copy -- only values*/strings.xml, exactly
            // what generateXcstrings/validateStrings validate.
            for (localeDir in AndroidToXcstringsConverter.discoverLocaleDirectories(source)) {
                val targetDir = File(out, localeDir.name).apply { mkdirs() }
                File(localeDir, "strings.xml").copyTo(File(targetDir, "strings.xml"), overwrite = true)
            }
        }
    }
}
