plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.atgeo.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.atgeo.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation("org.atgeo:atgeo-client")
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
