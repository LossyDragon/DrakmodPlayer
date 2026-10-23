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
        //noinspection ChromeOsAbiSupport
        ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild.cmake {
            cppFlags += listOf("-std=c++17")
            arguments += listOf(
                "-DBUILD_SHARED=OFF",
                "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                "-DANDROID_STL=c++_shared"
            )
        }
    }

    buildTypes {
        // Optimised + symbols: an -O0 libopenmpt/libxmp can't keep up with the audio callback,
        // but a plain Release build has nothing for the native debugger to work with.
        debug {
            externalNativeBuild.cmake.arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
        }
        release {
            externalNativeBuild.cmake.arguments += "-DCMAKE_BUILD_TYPE=Release"
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
