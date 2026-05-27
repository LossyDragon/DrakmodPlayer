import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.gradle.kotlinter)
    alias(libs.plugins.kotlin.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.stability.analyzer)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexplicit-backing-fields")
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get()))
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencyAnalysis {
    issues {
        onUnusedDependencies {
            exclude(
                "com.github.skydoves:compose-stability-runtime",
                "com.github.alorma.compose-settings:ui-tiles",
            )
        }
    }
}

android {
    namespace = "com.lossydragon.modplayer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lossydragon.modplayer"

        /*
         * https://apilevels.com/
         */
        minSdk = 26 // Oreo
        targetSdk = 37 // Cinnamon Bun (terrible codename).

        versionCode = 2
        versionName = "1.0"

        ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

        val apiKey = project.property("modArchiveApiKey") as String
        buildConfigField("String", "API_KEY", apiKey)
    }
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
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
            if (System.getenv("RELEASE_KEYSTORE_PATH").isNullOrBlank().not()) {
                signingConfig = signingConfigs.getByName("release")
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
    implementation(project(":libxmp"))

    implementation(platform(libs.compose.bom))
    implementation(platform(libs.koin.bom))

    debugImplementation(libs.compose.ui.tooling.preview)

    ksp(libs.room.compiler)

    implementation(libs.bundles.compose)
    implementation(libs.bundles.koin)
    implementation(libs.bundles.ktor)
    implementation(libs.bundles.media3)
    implementation(libs.bundles.nav3)
    implementation(libs.bundles.room)
    implementation(libs.bundles.serialization)
    implementation(libs.colorpicker.compose)
    implementation(libs.materialKolor)
    implementation(libs.reorderable)
    implementation(libs.timber)
}
