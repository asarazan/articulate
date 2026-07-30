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
    }
}

rootProject.name = "articulate"

// `plugin` and `sample` land in milestone 4 (D1: multi-module).
include("core")
