plugins {
    // Resolves JDK download URLs for Java toolchains and the daemon JVM criteria
    // in gradle/gradle-daemon-jvm.properties. Without it, a machine lacking the
    // required JDK cannot auto-provision one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "articulate"
