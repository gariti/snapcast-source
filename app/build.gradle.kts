plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.slowshell.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.slowshell.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    // Two distribution flavors, IDENTICAL for the custom link (control channel
    // + spectrum + party PCM). They exist for the deferred messaging layer,
    // whose push transport and restricted-permission strategy must diverge:
    //   foss -> UnifiedPush, may request telephony/all-files perms later
    //   play -> FCM, Play-policy-safe permission set only
    // Per-flavor behavior goes through com.slowshell.app.features.FlavorFeatures
    // (one impl per flavor source set) — never `if (BuildConfig.FLAVOR == ...)`.
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            isDefault = true
        }
        create("play") {
            dimension = "distribution"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true   // ControlChannelClient reports VERSION_NAME in its hello
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["foss"].java.srcDirs("src/foss/kotlin")
    sourceSets["play"].java.srcDirs("src/play/kotlin")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.jtransforms)
}
