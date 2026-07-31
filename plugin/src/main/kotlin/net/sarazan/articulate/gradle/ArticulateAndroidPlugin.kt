package net.sarazan.articulate.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import net.sarazan.articulate.gradle.tasks.GenerateAndroidResTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownProjectException
import org.gradle.api.UnknownTaskException

/**
 * `net.sarazan.articulate.android` (PLAN.md §4.2/§4.5/D10). Applied to the
 * Android **app** module. Its only job is variant res wiring -- registering
 * [GenerateAndroidResTask]'s output directory (owned by whichever module
 * `net.sarazan.articulate` is applied to, conventionally `:i18n`; see
 * [ArticulateAndroidExtension.i18nProject]) as a generated res source
 * directory for every variant.
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
 * that a task ran. (An earlier revision of this doc said the opposite; the
 * machine had no SDK at the time. Superseded 2026-07-31.) Never write into a
 * variant's checked-in `res` source set; never use the legacy
 * `sourceSets["main"].res.srcDir` (both settled, §4.5).
 *
 * **Known limitation:** the cross-project lookup below is incompatible with
 * Gradle's isolated-projects mode. Under
 * `-Dorg.gradle.unsafe.isolated-projects=true` a two-module fixture fails with
 * `Cannot access project ':i18n' from project ':app'` (reproduced against
 * Gradle 8.7, 2026-07-31). Configuration caching alone is unaffected -- entries
 * are stored and reused normally, see [AndroidWiringFunctionalTest].
 */
class ArticulateAndroidPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("articulateAndroid", ArticulateAndroidExtension::class.java)
        extension.i18nProject.convention(":i18n")

        project.pluginManager.withPlugin("com.android.application") {
            val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

            androidComponents.onVariants { variant ->
                val i18nProjectPath = extension.i18nProject.get()
                val i18nProject = try {
                    // Force the i18n project to be fully evaluated before this
                    // cross-project lookup, regardless of project declaration/
                    // evaluation order in the consumer's settings file. Without
                    // this, a build where the app module happens to be
                    // evaluated before the i18n module would see this plugin
                    // throw a false "no generateAndroidRes task" error below --
                    // not because net.sarazan.articulate wasn't applied, but
                    // merely because that project hadn't registered its tasks
                    // yet at the moment this callback ran. onVariants fires
                    // once per project's own evaluation, with no guarantee
                    // sibling projects have already evaluated first.
                    project.evaluationDependsOn(i18nProjectPath)
                    project.project(i18nProjectPath)
                } catch (e: UnknownProjectException) {
                    throw GradleException(
                        "net.sarazan.articulate.android: articulateAndroid.i18nProject is set to " +
                            "'$i18nProjectPath', but no such project exists. Point it at the module " +
                            "net.sarazan.articulate is applied to, e.g. articulateAndroid { i18nProject.set(\":i18n\") }.",
                        e,
                    )
                }

                val generateAndroidRes = try {
                    i18nProject.tasks.named("generateAndroidRes", GenerateAndroidResTask::class.java)
                } catch (e: UnknownTaskException) {
                    throw GradleException(
                        "net.sarazan.articulate.android: project '$i18nProjectPath' has no " +
                            "'generateAndroidRes' task -- apply net.sarazan.articulate to it first.",
                        e,
                    )
                }

                variant.sources.res?.addGeneratedSourceDirectory(generateAndroidRes, GenerateAndroidResTask::androidResDir)
            }
        }
    }
}
