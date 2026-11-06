plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI run number becomes the version code so each new APK installs over the previous one.
// CI: the Actions run number. Local builds for a TV: ./gradlew assembleRelease -PbuildNumber=N
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
    ?: (findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.osama.xtreamtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.osama.xtreamtv"
        minSdk = 21
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.3.3"
        multiDexEnabled = true
        // Weekly QA (qa/README.md): UI tests on an Android TV emulator against a fake Xtream server.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    }

    testOptions {
        // Each test runs in its own process: one crash fails one test, not the whole run.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
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
    // Video over OkHttp so IPv4 can be tried first (see PlaybackTuning.http).
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // "Now playing" for the TV (the mini player's title, remote media keys).
    implementation("androidx.media3:media3-session:$media3")
    implementation("io.coil-kt:coil:2.7.0")
    // Installs the startup profile (src/main/baseline-prof.txt) on sideloaded installs too.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.tvprovider:tvprovider:1.0.0")

    // Weekly QA tests only (never inside the APK the TV installs).
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test.services:storage:1.5.0")
    androidTestUtil("androidx.test:orchestrator:1.5.1")
    androidTestUtil("androidx.test.services:test-services:1.5.0")
}
