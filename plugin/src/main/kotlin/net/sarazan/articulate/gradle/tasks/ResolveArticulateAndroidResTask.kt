package net.sarazan.articulate.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * PLAN.md §4.5/§13: lives entirely in the **app** module, registered by
 * `net.sarazan.articulate.android`'s own plugin instance -- never a class
 * the i18n-owning project's `net.sarazan.articulate` instance created. That
 * is what makes it safe to hand to AGP's
 * `addGeneratedSourceDirectory(taskProvider, ThisClass::outputDir)`: the
 * method reference and the task provider both originate in the app module's
 * own plugin classloader, so no class identity is ever compared across a
 * classloader boundary (contrast the cross-project
 * `GenerateAndroidResTask::class.java`/`GenerateAndroidResTask::androidResDir`
 * lookups this replaces).
 *
 * Materializes [resolvedRes] -- the files resolved from the
 * `articulateAndroidResIncoming` configuration, i.e. the i18n-owning
 * project's generated Android res tree, obtained via dependency resolution
 * rather than a cross-project task lookup -- into [outputDir]. The copy is
 * required, not merely incidental: `addGeneratedSourceDirectory` makes AGP
 * *own* [outputDir]'s final location (see [GenerateAndroidResTask]'s own
 * KDoc for the identical behavior on the producer side), so this task must
 * actually write into wherever AGP ultimately points that property, not
 * merely expose the resolved files as-is.
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
        // Disposable output (mirrors GenerateAndroidResTask, §4.4): cleared
        // and rewritten wholesale each run so a locale directory removed
        // upstream can never linger here as a stale generated one.
        out.deleteRecursively()
        out.mkdirs()
        for (source in resolvedRes.files) {
            if (source.isDirectory) {
                source.copyRecursively(out, overwrite = true)
            }
        }
    }
}
