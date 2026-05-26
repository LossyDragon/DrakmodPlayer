import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.gradle.kotlinter)
    alias(libs.plugins.stability.analyzer)
    alias(libs.plugins.ksp)
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

        versionCode = 1
        versionName = "1.0"

        ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

        val apiKey = project.property("modArchiveApiKey") as String
        buildConfigField("String", "API_KEY", apiKey)
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
    implementation(project(":libxmp"))

    implementation(platform(libs.compose.bom))
    implementation(platform(libs.koin.bom))

    debugImplementation(libs.compose.ui.tooling.preview)

    ksp(libs.room.compiler)

    // implementation(libs.compose.placeholder) // TODO try out
    implementation("com.github.alorma.compose-settings:ui-tiles-expressive:3.1.0")
    implementation("com.github.skydoves:colorpicker-compose:1.1.4")
    implementation(libs.bundles.compose)
    implementation(libs.bundles.koin)
    implementation(libs.bundles.ktor)
    implementation(libs.bundles.nav3)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.immutable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.materialKolor)
    implementation(libs.media3.common)
    implementation(libs.media3.session)
    implementation(libs.reorderable)
    implementation(libs.room.ktx)
    implementation(libs.room.runtime)
    implementation(libs.timber)
}
