package net.sarazan.articulate.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import net.sarazan.articulate.gradle.tasks.ResolveArticulateAndroidResTask
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `net.sarazan.articulate.android` (PLAN.md §4.2/§4.5/D10). Applied to the
 * Android **app** module. Its only job is variant res wiring -- registering
 * the i18n-owning module's generated Android res tree (conventionally
 * `:i18n`, see [ArticulateAndroidExtension.i18nProject]) as a generated res
 * source directory for every variant.
 *
 * AGP is `compileOnly` (D9, pinned to the 8.5.2 floor) and referenced only
 * from this file, never from [ArticulatePlugin] or anything in `:core` --
 * D10's entire point is that a non-Android consumer of the base plugin never
 * pulls AGP onto its classpath.
 *
 * ---
 * **Verified against a real Android build** by [AndroidWiringFunctionalTest],
 * which runs AGP 8.5.2 on Gradle 8.7 (D9's floor, `compileSdk 34`) against an
 * installed SDK and compiles a Java source referencing `R.string.hello` --
 * proof the wired directory actually reached AGP's resource merge, not merely
 * that a task ran. Never write into a variant's checked-in `res` source set;
 * never use the legacy `sourceSets["main"].res.srcDir` (both settled, §4.5).
 *
 * **Cross-project mechanism, redesigned 2026-08-01 (PLAN.md §4.5/§13,
 * release-blocking).** The previous implementation resolved the producing
 * task by reaching directly into the i18n-owning project's task container
 * (`i18nProject.tasks.named("generateAndroidRes", GenerateAndroidResTask::class.java)`),
 * which (a) compared `GenerateAndroidResTask`'s `Class` identity across two
 * *different* plugin classloaders whenever a consumer applied the two plugin
 * IDs from separate module `plugins {}` blocks -- the natural, Android
 * Studio-default layout -- throwing `InvalidUserDataException: ... is not a
 * subclass of the given type` even though it is the same class by name, and
 * (b) was rejected outright under `-Dorg.gradle.unsafe.isolated-projects=true`
 * (`Cannot access project ':i18n' from project ':app'`).
 *
 * This plugin now never touches [ArticulateAndroidExtension.i18nProject]'s
 * project directly. Instead it declares a **resolvable** configuration
 * ([ARTICULATE_ANDROID_RES_INCOMING_CONFIGURATION]) carrying
 * [ARTICULATE_ANDROID_RES_ATTRIBUTE], with an ordinary project dependency on
 * the i18n-owning project -- resolved lazily against
 * [ArticulateExtension]'s (via [ArticulatePlugin]) matching **consumable**
 * configuration. That is dependency-graph resolution, not project-container
 * access: permitted under isolated projects, and the task dependency on
 * `generateAndroidRes` is carried implicitly (via that configuration's
 * artifact `builtBy`), with no `evaluationDependsOn` or `dependsOn` anywhere
 * in this file. [ResolveArticulateAndroidResTask] -- a class this plugin
 * instance itself registers, in the app module's own plugin classloader --
 * materializes the resolved files into a directory `addGeneratedSourceDirectory`
 * then owns; see that class's KDoc for why the copy is necessary. No
 * `GenerateAndroidResTask` type, and no method reference against it, crosses
 * the classloader boundary any more.
 */
class ArticulateAndroidPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("articulateAndroid", ArticulateAndroidExtension::class.java)
        extension.i18nProject.convention(":i18n")

        project.pluginManager.withPlugin("com.android.application") {
            val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

            // Resolvable side of the ArticulatePlugin.kt consumable
            // configuration. No project.project(path), no
            // evaluationDependsOn, no reach into :i18n's task container,
            // extensions, or properties -- see this class's KDoc.
            val articulateAndroidResIn = project.configurations.resolvable(
                ARTICULATE_ANDROID_RES_INCOMING_CONFIGURATION,
            ) { config ->
                config.attributes.attribute(ARTICULATE_ANDROID_RES_ATTRIBUTE, ARTICULATE_ANDROID_RES_ATTRIBUTE_VALUE)
            }.get()

            // Added as a lazy Provider, not `extension.i18nProject.get()`
            // read eagerly here: this plugin's own apply() runs before a
            // consumer's `articulateAndroid { i18nProject = ... }` block
            // does, so an eager read here would only ever observe the
            // ":i18n" convention. This also removes the ordering hazard the
            // old implementation needed `evaluationDependsOn` to defuse --
            // Gradle's own dependency resolution (not this plugin) now
            // decides when the i18n-owning project needs to be configured.
            project.dependencies.addProvider(
                articulateAndroidResIn.name,
                extension.i18nProject.map { i18nProjectPath -> project.dependencies.project(mapOf("path" to i18nProjectPath)) },
            )

            // Registered once per project, in THIS plugin instance's own
            // classloader -- reused across every variant below, exactly like
            // the single shared generateAndroidRes task the old
            // implementation wired into every variant.
            val resolveAndroidRes = project.tasks.register(
                "resolveArticulateAndroidRes",
                ResolveArticulateAndroidResTask::class.java,
            ) { task ->
                task.group = ARTICULATE_TASK_GROUP
                task.description = "Materializes the Android res tree generated by the i18n-owning module " +
                    "(resolved via $ARTICULATE_ANDROID_RES_INCOMING_CONFIGURATION, not a cross-project task " +
                    "lookup -- PLAN.md §4.5/§13) for AGP to merge into this app module's variants."
                // The Configuration object itself, not `.files`/`.resolve()`
                // pre-extracted: passing the Configuration directly is what
                // lets Gradle carry its implicit build-dependency metadata
                // (Configuration implements Buildable) into resolvedRes's own
                // TaskDependency -- verified load-bearing: an earlier revision
                // extracted `.files` inside a custom Provider (to wrap
                // resolution failures in a friendlier GradleException) and
                // that alone broke applying both plugin IDs to a single
                // module (Gradle's own implicit-dependency validator then
                // rejected it: "uses this output of task ':generateAndroidRes'
                // without declaring an explicit or implicit dependency").
                // Resolution failures (missing project; project missing
                // net.sarazan.articulate) still surface loudly -- as Gradle's
                // own "Could not resolve..." diagnostics, which already name
                // the project path and the missing attribute/variant -- just
                // not wrapped in Articulate's own message text.
                task.resolvedRes.from(articulateAndroidResIn)
                task.outputDir.convention(project.layout.buildDirectory.dir("generated/articulate/res"))
            }

            androidComponents.onVariants { variant ->
                variant.sources.res?.addGeneratedSourceDirectory(resolveAndroidRes, ResolveArticulateAndroidResTask::outputDir)
            }
        }
    }
}
