pluginManagement {
    // Consumes the plugin under development, not a published artifact --
    // this is the checked-in sample's whole point (PLAN.md §14/§4.5): it
    // must build with `../` unmodified so it stays a live smoke test.
    includeBuild("..")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "articulate-sample"

include(":i18n")
include(":shared")
include(":androidApp")
