@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.helllabs.libxmp"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild.cmake {
            cppFlags += listOf("-std=c++17")
            arguments += listOf(
                "-DCMAKE_BUILD_TYPE=Release", // DEBUG
                "-DBUILD_SHARED=OFF",
                "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                "-DANDROID_STL=c++_shared"
            )
        }
    }

    externalNativeBuild.cmake.path = file("src/main/cpp/CMakeLists.txt")

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }

    buildFeatures.prefab = true

    dependencies {
        // Jetpack Compose
        implementation(platform(libs.compose.bom))
        implementation(libs.compose.runtime)
        // Audio Renderer
        implementation(libs.oboe)
    }
}