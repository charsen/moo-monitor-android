group = "com.mooeen.monitor"
version = "0.1.0"

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mooeen.monitor"
    compileSdk = 34

    // 复合构建（includeBuild）靠 group:name 做依赖替换，宿主写
    // implementation("com.mooeen.monitor:monitor:0.1.0") 即可指向本模块。
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // 让 android.jar 的桩方法返回默认值而不是抛 "Stub!"，纯 JVM 单测里也能安全触碰 Build / Log。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.json.test)
    testImplementation(libs.robolectric)
}
