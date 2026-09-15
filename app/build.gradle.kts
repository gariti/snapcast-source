plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lattice.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lattice.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.0"
        // The gesture tests are the only way to put TWO fingers on this app:
        // a stock Pixel refuses `sendevent` to /dev/input/* from adb, and
        // `adb shell input` has never spoken multi-touch.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Two distribution flavors, IDENTICAL for the custom link (control channel
    // + spectrum + party PCM). They exist for the deferred messaging layer,
    // whose push transport and restricted-permission strategy must diverge:
    //   foss -> UnifiedPush, may request telephony/all-files perms later
    //   play -> FCM, Play-policy-safe permission set only
    // Per-flavor behavior goes through com.lattice.app.features.FlavorFeatures
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
            // R8 + resource shrinking: material-icons-extended alone is ~50 MB
            // unshrunk, and the sideload path caps at 30 MB. Signed with the
            // debug key so `adb install -r` / a sideload upgrades in place.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
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
    sourceSets["androidTest"].java.srcDirs("src/androidTest/kotlin")
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
    implementation(libs.androidx.material.icons.extended)
    // Desktop auth: BiometricPrompt (+ the FragmentActivity it needs).
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.jtransforms)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
