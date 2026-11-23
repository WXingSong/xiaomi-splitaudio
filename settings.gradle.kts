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
        // JitPack 仅用于提供内置无线 ADB 客户端 libadb-android 及其依赖 spake2
        exclusiveContent {
            forRepository { maven { url = uri("https://jitpack.io") } }
            filter { includeGroupAndSubgroups("com.github.MuntashirAkon") }
        }
    }
}
rootProject.name = "xiaomi-splitaudio"
include(":app")
