plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kojii.frontend"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.kojii.frontend"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
}
