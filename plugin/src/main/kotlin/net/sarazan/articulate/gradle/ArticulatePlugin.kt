package net.sarazan.articulate.gradle

import net.sarazan.articulate.core.convert.MarkupPolicy
import net.sarazan.articulate.gradle.tasks.GenerateAndroidResTask
import net.sarazan.articulate.gradle.tasks.GenerateXcstringsTask
import net.sarazan.articulate.gradle.tasks.VerifyStringsTask
import org.gradle.api.Plugin
import org.gradle.api.Project

/** Task group every Articulate task registers under, for `./gradlew tasks` grouping. */
internal const val ARTICULATE_TASK_GROUP = "articulate"

/**
 * `net.sarazan.articulate` (PLAN.md §4.2/D10). Applied to the module that
 * owns the strings source tree (conventionally `:i18n`). Owns source
 * discovery, the `articulate { }` extension, and all four tasks:
 * [GenerateAndroidResTask], [GenerateXcstringsTask], the `generateStrings`
 * aggregate, and [VerifyStringsTask].
 *
 * No AGP on this plugin's classpath (D10): a KMP-only or non-Android consumer
 * never pulls it in. Android variant wiring is [ArticulateAndroidPlugin]'s
 * job alone, applied separately to the app module.
 */
class ArticulatePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("articulate", ArticulateExtension::class.java)

        // Conventions (§4.3: "the plugin resolves that as a DirectoryProperty
        // with a convention, overridable via the extension"). Set here, not
        // in ArticulateExtension itself, so the extension class carries no
        // Project/ProjectLayout dependency of its own.
        extension.stringsDir.convention(project.layout.projectDirectory.dir("src/main/strings"))
        extension.sourceLanguage.convention("en")
        extension.markupPolicy.convention(MarkupPolicy.ERROR)
        extension.warningsAsErrors.convention(false)
        extension.ios.table.convention("Shared")

        val generateAndroidRes = project.tasks.register("generateAndroidRes", GenerateAndroidResTask::class.java) { task ->
            task.group = ARTICULATE_TASK_GROUP
            // §4.4 (CORRECTED): "any user-facing documentation of 'where do the
            // generated Android resources go' must name the app module's build
            // dir, not :i18n's" -- so this user-visible `gradlew tasks` line has
            // to name both cases, since the convention below only survives when
            // net.sarazan.articulate.android is NOT wiring the output.
            task.description = "Regenerates the disposable per-locale Android values*/strings.xml tree. " +
                "Output goes to the app module's build/generated/res/generateAndroidRes when " +
                "net.sarazan.articulate.android wires it, otherwise this module's build/generated/i18n/res."
            task.stringsDir.set(extension.stringsDir)
            task.sourceLanguage.set(extension.sourceLanguage)
            task.localeOverrides.set(extension.localeOverrides)
            task.warningsAsErrors.set(extension.warningsAsErrors)
            task.androidResDir.set(project.layout.buildDirectory.dir("generated/i18n/res"))
        }

        val generateXcstrings = project.tasks.register("generateXcstrings", GenerateXcstringsTask::class.java) { task ->
            task.group = ARTICULATE_TASK_GROUP
            task.description = "Regenerates the committed .xcstrings catalog from the strings source tree -- commit the result."
            task.stringsDir.set(extension.stringsDir)
            task.sourceLanguage.set(extension.sourceLanguage)
            task.localeOverrides.set(extension.localeOverrides)
            task.warningsAsErrors.set(extension.warningsAsErrors)
            task.xcstringsFile.set(extension.ios.catalog)
        }

        // PLAN.md §4.4: a lifecycle task with no outputs of its own,
        // dependsOn-ing both generate tasks. Keeps the name `generateStrings`
        // because that's the string already baked into verifyStrings' own
        // failure message and the CI recipe.
        project.tasks.register("generateStrings") { task ->
            task.group = ARTICULATE_TASK_GROUP
            task.description = "Aggregate: regenerates both the Android res tree and the committed .xcstrings catalog."
            task.dependsOn(generateAndroidRes, generateXcstrings)
        }

        project.tasks.register("verifyStrings", VerifyStringsTask::class.java) { task ->
            task.group = ARTICULATE_TASK_GROUP
            task.description = "Fails the build if the committed .xcstrings catalog has drifted from the strings source tree."
            task.stringsDir.set(extension.stringsDir)
            task.sourceLanguage.set(extension.sourceLanguage)
            task.localeOverrides.set(extension.localeOverrides)
            task.warningsAsErrors.set(extension.warningsAsErrors)
            task.xcstringsFile.set(extension.ios.catalog)
        }
    }
}
