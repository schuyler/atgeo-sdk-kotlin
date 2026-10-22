// A separate Gradle build, so that the library's own build never applies AGP
// and needs no Android SDK. It consumes the library from the parent directory.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "atgeo-android-sample"

includeBuild("..")
include(":app")
