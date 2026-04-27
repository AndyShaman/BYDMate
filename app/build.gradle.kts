plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bydmate.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bydmate.app"
        minSdk = 29
        // targetSdk 29 matches TripInfo — grants full legacy file access
        // on DiLink Android 12 (requestLegacyExternalStorage works).
        // targetSdk 30+ would break listFiles() on /storage/emulated/0/energydata/
        targetSdk = 29
        versionCode = 259
        versionName = "2.4.18.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "BYDMate-v${variant.versionName}.apk"
        }
    }

    lint {
        // Not targeting Google Play -- DiLink sideload only
        disable += "ExpiredTargetSdkVersion"
    }

    sourceSets {
        // MigrationTestHelper (Robolectric) resolves schemas via merged variant
        // assets — not the "test" sourceSet. Both debug and release need them
        // so testDebugUnitTest AND testReleaseUnitTest can find Migration*Test
        // schemas. ~50KB extra in release APK is acceptable (sideload only).
        getByName("debug") {
            assets.srcDirs("$projectDir/schemas")
        }
        getByName("release") {
            assets.srcDirs("$projectDir/schemas")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // android.util.Log is unavailable in pure JVM unit tests without Robolectric.
            // returnDefaultValues = true makes all unmocked Android API methods return 0/null/false
            // instead of throwing RuntimeException — this keeps AutoserviceChargingDetector
            // testable without requiring Robolectric for every test class.
            isReturnDefaultValues = true
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
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-compiler:2.53.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // osmdroid (maps)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // JSON parsing
    implementation("org.json:json:20231013")

    // Sun/moon position calculation for time_of_day trigger
    implementation("org.shredzone.commons:commons-suncalc:3.7")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    // ADB-on-device for autoservice access (path H, read-only)
    // com.cgutman:adblib does not exist on MavenCentral (only com.tananaev:adblib does).
    // Task 4 will use a hand-rolled ADB client fallback.
    // implementation("com.cgutman:adblib:1.0.0")
}
