// Android Studio's default shape: AGP requested here, in this module's own
// `plugins {}` block, with a version -- not in the root script. Combined
// with :i18n's separate block (see ../i18n/build.gradle.kts), this is the
// two-distinct-classloader layout PLAN.md §4.5/§13 flags as release-blocking:
// this module's plugin classloader carries our plugin's jars *plus* AGP's,
// while :i18n's carries only ours.
plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.4.10"
    id("net.sarazan.articulate.android") version "0.1.0"
}

android {
    namespace = "net.sarazan.articulate.sample.androidApp"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.sarazan.articulate.sample.androidApp"
        minSdk = 24
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

articulateAndroid {
    i18nProject.set(":i18n")
}

dependencies {
    // :shared is here to exercise the *optional* shared-keys question
    // (PLAN.md §14) -- see the sample's README before assuming KMP is
    // required to use Articulate. It is not.
    implementation(project(":shared"))
}
