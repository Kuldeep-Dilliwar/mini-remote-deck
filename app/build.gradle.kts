@file:Suppress("UnstableApiUsage") // <--- THIS FIXES THE NEW WARNING

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "mini.remote.deck.project.hobby"
    compileSdk = 36

    defaultConfig {
        applicationId = "mini.remote.deck.project.hobby"
        minSdk = 23
        targetSdk = 36
        val envVersionName = System.getenv("BUILD_VERSION_NAME") ?: "1.0-Dev"
        val timestamp = (System.currentTimeMillis() / 60000).toInt()

        versionCode = timestamp
        versionName = envVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // NEW WAY: This replaces 'resourceConfigurations'
    androidResources {
        localeFilters += "en"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.protolite.well.known.types)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.androidx.navigation.compose) // Use the latest version available
    implementation(libs.androidx.material.icons.extended) // Use the latest version
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}