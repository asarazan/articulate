package net.sarazan.articulate.gradle.tasks

import net.sarazan.articulate.core.convert.AndroidToXcstringsConverter
import net.sarazan.articulate.core.serialize.XcstringsWriter
import net.sarazan.articulate.gradle.reportDiagnostics
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * PLAN.md §4.4: regenerates the *committed* `.xcstrings` catalog from the
 * strings source tree. Unlike the Android path -- where, since §4.5b, the
 * strings source tree itself doubles as the Android resource output, so
 * there is no separate generated file at all -- this file is checked into
 * the repo (§4.4 "Why one output is committed and the other isn't" --
 * Xcode sits outside Gradle's build graph, so the catalog must already be
 * on disk when a developer hits Cmd+B).
 *
 * Byte-deterministic via [XcstringsWriter] and the same canonical rules
 * milestone 1 established: re-running without a source change reproduces
 * identical bytes, which is exactly the property [VerifyStringsTask]'s gate
 * depends on.
 */
@CacheableTask
abstract class GenerateXcstringsTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringsDir: DirectoryProperty

    @get:Input
    abstract val sourceLanguage: Property<String>

    @get:Input
    abstract val localeOverrides: MapProperty<String, String>

    @get:Input
    abstract val warningsAsErrors: Property<Boolean>

    /** DSL-configured via `articulate { ios { catalog = file(...) } } }`. */
    @get:OutputFile
    abstract val xcstringsFile: RegularFileProperty

    @TaskAction
    fun generate() {
        if (!xcstringsFile.isPresent) {
            throw GradleException(
                "generateXcstrings: articulate { ios { catalog = file(...) } } is not configured -- " +
                    "nowhere to write the generated catalog.",
            )
        }

        val result = AndroidToXcstringsConverter.convert(stringsDir.get().asFile, sourceLanguage.get(), localeOverrides.get())
        reportDiagnostics(logger, result.diagnostics, warningsAsErrors.get(), "generateXcstrings")

        val target = xcstringsFile.get().asFile
        target.parentFile?.mkdirs()
        target.writeBytes(XcstringsWriter.writeBytes(result.catalog))
    }
}
