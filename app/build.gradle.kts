plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.lumen.reader"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "app.lumen.reader"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "1.5.0"
        androidResources.localeFilters += arrayOf("en")
    }

    signingConfigs {
        // One key for every build, kept in the repo so updates always install over the
        // previous version, whichever machine built them. Keep this repository private.
        create("lumen") {
            storeFile = rootProject.file("keystore/lumen.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lumen")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("lumen")
            vcsInfo.include = false
        }
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += arrayOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt"
            )
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            val mirror = providers.gradleProperty("robolectricRepoUrl").orNull
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                if (mirror != null) it.systemProperty("robolectric.dependency.repo.url", mirror)
            }
        }
    }
    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material.ripple)
    implementation(libs.compose.material.icons)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.compose.material3)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.motionprediction)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
