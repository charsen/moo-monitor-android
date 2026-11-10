pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "moo-monitor-android"
include(":monitor")

// 仅在显式执行设备验收时加载，不进入宿主的常规复合构建。
if (providers.gradleProperty("monitorDeviceQa").isPresent) {
    include(":device-qa")
    project(":device-qa").projectDir = file("tools/device-qa")
}
