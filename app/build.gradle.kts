import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexplicit-backing-fields")
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get()))
    }
}

android {
    namespace = "com.lossydragon.modplayer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lossydragon.modplayer"

        minSdk = 24
        targetSdk = 37

        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isJniDebuggable = true
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    splits {
        abi {
            // isEnable = true
            isUniversalApk = true
        }
    }
}

dependencies {
    // Local Projects
    implementation(project(":libxmp"))

    debugImplementation(libs.compose.ui.tooling.preview)
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.communityCompose)
}