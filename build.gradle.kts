import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.animalsniffer)
}

group = "org.atgeo"
version = "0.1.0"

java {
    // JVM 11 is the floor for server-side users; the Android floor (API 24) is
    // enforced separately by animalsniffer below.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // The library compiles against the JDK, so a call Android gained after
    // API 24 compiles and passes every test, then fails on a device. This
    // signature makes such a call fail the build (spec §2).
    signature(variantOf(libs.android.api.signature) { artifactType("signature") })
}

animalsniffer {
    sourceSets = listOf(project.sourceSets.main.get())
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "atgeo-client"
            from(components["java"])
        }
    }
}
