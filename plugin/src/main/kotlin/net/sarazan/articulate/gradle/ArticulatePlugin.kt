package net.sarazan.articulate.gradle

import net.sarazan.articulate.core.convert.MarkupPolicy
import net.sarazan.articulate.gradle.tasks.GenerateXcstringsTask
import net.sarazan.articulate.gradle.tasks.ValidateStringsTask
import net.sarazan.articulate.gradle.tasks.VerifyStringsTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

/** Task group every Articulate task registers under, for `./gradlew tasks` grouping. */
internal const val ARTICULATE_TASK_GROUP = "articulate"

/**
 * `net.sarazan.articulate` (PLAN.md §4.2/D10). Applied to the module that
 * owns the strings source tree (conventionally `:i18n`). Owns source
 * discovery, the `articulate { }` extension, and all four tasks:
 * [ValidateStringsTask], [GenerateXcstringsTask], the `generateStrings`
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

        // Task 4 correctness fix (PLAN.md §13's M4/M5 audit): STRIP/VERBATIM
        // are declared DSL surface (D4) but core always behaves as ERROR
        // regardless of what's set -- silently. A user who asks for STRIP and
        // gets ERROR-shaped behavior with no diagnostic is exactly the "95%
        // right is worse than none" hazard the brief forbids. afterEvaluate,
        // not read eagerly here, so this observes whatever the consumer's own
        // `articulate { markupPolicy = ... }` block (which runs after this
        // apply()) actually set -- reading extension.markupPolicy.get() here
        // instead would only ever see the ERROR convention just above.
        project.afterEvaluate {
            val policy = extension.markupPolicy.get()
            if (policy != MarkupPolicy.ERROR) {
                throw GradleException(
                    "net.sarazan.articulate: markupPolicy = $policy is not yet implemented -- only " +
                        "MarkupPolicy.ERROR is supported in v0. See PLAN.md §2.2's D4 ruling. Remove the " +
                        "markupPolicy override or set it to MarkupPolicy.ERROR.",
                )
            }
        }

        // IDE visibility. A module applying only this plugin registers ZERO
        // source sets, so Android Studio's *Android* view renders it as an
        // empty module -- no expand arrow, no strings.xml. Project view always
        // showed the tree; the default view did not, which is what a new user
        // sees first. Since the whole authoring story is "hand-edit
        // strings.xml the way you would in Android", a strings tree the
        // default view cannot display is an adoption problem, not a cosmetic
        // one. `java-base` supplies a SourceSetContainer with no jar and no
        // assemble wiring; registering stringsDir as a resource root is what
        // puts the tree into the IDE model. Uses the extension property, not
        // the literal path, so an overridden stringsDir stays correct.
        //
        // Gated on nothing else already modelling sources: an Android or KMP
        // module renders fine and neither needs nor wants a spare Java source
        // set. afterEvaluate because *absence* can only be judged once the
        // consumer's whole plugins {} block has run -- plugin order within it
        // is not ours to assume.
        project.afterEvaluate {
            val alreadyModelled = project.extensions.findByName("sourceSets") != null ||
                project.extensions.findByName("android") != null ||
                project.extensions.findByName("kotlin") != null
            if (!alreadyModelled) {
                project.pluginManager.apply("java-base")
                project.extensions.getByType(SourceSetContainer::class.java).create("strings") { sourceSet ->
                    sourceSet.resources.srcDir(extension.stringsDir)
                }
            }
        }

        // PLAN.md §4.5b: GenerateAndroidResTask is gone -- its own KDoc
        // recorded the verified premise that it was `copyTo`, nothing more,
        // because the strings source tree (values*/strings.xml) is *already*
        // valid Android resource layout. validateStrings replaces it as a
        // pure correctness gate: same AndroidToXcstringsConverter.convert
        // pipeline, same diagnostics, no output, no copy.
        val validateStrings = project.tasks.register("validateStrings", ValidateStringsTask::class.java) { task ->
            task.group = ARTICULATE_TASK_GROUP
            task.description = "Parses and validates the strings source tree exactly as generateXcstrings would, " +
                "with no output of its own -- the gate net.sarazan.articulate.android's consumable configuration " +
                "runs via builtBy before a real build merges the source tree into Android resources."
            task.stringsDir.set(extension.stringsDir)
            task.sourceLanguage.set(extension.sourceLanguage)
            task.localeOverrides.set(extension.localeOverrides)
            task.warningsAsErrors.set(extension.warningsAsErrors)
        }

        // PLAN.md §4.5b (supersedes §4.5/§13's generated-output artifact):
        // the consumable configuration now carries the SOURCE directory
        // itself, not a task's generated output. The files are always on
        // disk, so a consumer resolving this configuration merely for a
        // *path* (e.g. an IDE model at sync) needs nothing executed --
        // `builtBy(validateStrings)` only matters when a real task in the
        // consuming build's task graph declares this configuration as an
        // input, which is what makes resolving it *for a build* run
        // validation first. See ArticulateAndroidResSharing.kt for why this
        // configuration and its attribute are safe to reference identically
        // from both plugin classes despite usually running in different
        // plugin classloaders.
        val articulateAndroidResElements = project.configurations.consumable(
            ARTICULATE_ANDROID_RES_ELEMENTS_CONFIGURATION,
        ) { config ->
            config.attributes.attribute(ARTICULATE_ANDROID_RES_ATTRIBUTE, ARTICULATE_ANDROID_RES_ATTRIBUTE_VALUE)
        }
        project.artifacts.add(articulateAndroidResElements.name, extension.stringsDir) { artifact ->
            artifact.builtBy(validateStrings)
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

        // PLAN.md §4.4/§4.5b: a lifecycle task with no outputs of its own.
        // Keeps the name `generateStrings` because that's the string already
        // baked into verifyStrings' own failure message and the CI recipe --
        // even though there is no longer an Android res tree to generate,
        // `./gradlew generateStrings` remains the one command that
        // regenerates the committed catalog *and* validates the Android path.
        project.tasks.register("generateStrings") { task ->
            task.group = ARTICULATE_TASK_GROUP
            task.description = "Aggregate: regenerates the committed .xcstrings catalog and validates the strings " +
                "source tree for the Android path."
            task.dependsOn(generateXcstrings, validateStrings)
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
