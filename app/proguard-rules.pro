# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.* { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# osmdroid
-keep class org.osmdroid.** { *; }

# Strip debug/verbose logs in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# Keep data classes for Room
-keepclassmembers class com.bydmate.app.data.local.entity.** { *; }
-keepclassmembers class com.bydmate.app.data.local.dao.** { *; }
