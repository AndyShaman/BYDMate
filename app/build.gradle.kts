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
        versionCode = 19
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    lint {
        // Not targeting Google Play -- DiLink sideload only
        disable += "ExpiredTargetSdkVersion"
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

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // JSON parsing
    implementation("org.json:json:20231013")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
