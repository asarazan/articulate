plugins {
    // Resolves JDK download URLs for Java toolchains and the daemon JVM criteria
    // in gradle/gradle-daemon-jvm.properties. Without it, a machine lacking the
    // required JDK cannot auto-provision one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Repositories live here, not in module build files, so a stray project-level
    // repository can't silently change where artifacts resolve from.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // AGP itself (plugin/'s compileOnly dependency, pinned per D9) is only
        // published to Google's Maven repo, never Maven Central.
        google()
    }
}

rootProject.name = "articulate"

// `sample/` (PLAN.md §14/§4.5) is deliberately NOT included here -- it is
// its own composite build (`sample/settings.gradle.kts` does
// `pluginManagement { includeBuild("..") }` against this root), which is
// exactly what gives its modules genuinely distinct plugin classloaders
// (needed to reproduce the §4.5/§13 cross-project bug at all -- see
// SampleCompositeBuildFunctionalTest). Including it as an ordinary
// subproject here would collapse it back onto this build's single
// classloader and defeat that purpose.
include("core")
include("plugin")
