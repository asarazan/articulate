rootProject.name = "Showcase"

pluginManagement {
    // Consumes the Articulate plugin under development, not a published
    // artifact (PLAN.md §15.4/§15.6 lane A) -- mirrors sample/settings.gradle.kts.
    // TODO: switch to the published Gradle Portal coordinates after 0.1.0 ships.
    includeBuild("..")

    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Needed for `updateDaemonJvm` to resolve toolchain download URLs, and for
    // toolchain auto-provisioning generally; matches the repo root and sample/.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":androidApp")
include(":sharedLogic")
include(":i18n")