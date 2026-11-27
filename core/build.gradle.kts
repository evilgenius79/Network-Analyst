import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    // Live tests talk to real servers; they only run when explicitly requested.
    val live = providers.gradleProperty("liveTests").isPresent
    systemProperty("liveTests", live.toString())
    providers.gradleProperty("daemonHost").orNull?.let { systemProperty("daemonHost", it) }
    testLogging {
        events("failed", "skipped")
        showStandardStreams = live || providers.gradleProperty("daemonHost").isPresent
    }
}
