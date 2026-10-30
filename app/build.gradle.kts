plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI run number becomes the version code so each new APK installs over the previous one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.osama.xtreamtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.osama.xtreamtv"
        minSdk = 21
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        multiDexEnabled = true
    }

    // Personal sideload app: a fixed, committed key so updates install over the old version.
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "xtreamtv"
            keyAlias = "xtream"
            keyPassword = "xtreamtv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
}
