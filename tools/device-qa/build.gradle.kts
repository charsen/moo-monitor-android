plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mooeen.monitor.deviceqa"
    // The SDK now depends on AndroidX artifacts whose AAR metadata requires API 35.
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mooeen.monitor.deviceqa"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { implementation(project(":monitor")) }
