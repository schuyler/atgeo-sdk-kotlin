plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.atgeo.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.atgeo.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("org.atgeo:atgeo-client")
}
