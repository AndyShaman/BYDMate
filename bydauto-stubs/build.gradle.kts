plugins {
    id("java-library")
}

// Compile-only mirror of the hidden BYD framework classes the push listeners subclass.
// Never packaged into the APK: the app depends on this module with compileOnly, and the real
// classes live in the head unit's own framework.jar (see the app's -dontwarn android.hardware.**).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
