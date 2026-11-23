plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.splitaudio"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.splitaudio"
        minSdk = 31
        targetSdk = 36
        versionCode = 23
        versionName = "0.6.0"
    }
    buildTypes {
        release {
            // libadb-android 与 conscrypt 通过原生库反射调用，保持关闭混淆
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    // 路由守护进程源码（route/AppRoute.java，默认包）一并编入单元测试
    sourceSets {
        getByName("test") { java.srcDir("../route") }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // 内置无线 ADB 客户端（Shizuku 不可用时兜底）
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    testImplementation("junit:junit:4.13.2")
}
