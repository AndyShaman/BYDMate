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

# Guard the reflective app_process entry point for the binder daemon
-keep public class com.bydmate.app.helper.HelperDaemon { public static void main(java.lang.String[]); }

# --- П10 R8 keep-set: reflective / JNI entry points R8 must not strip or rename ---

# sherpa-onnx: JNI classes with native methods and a companion System.loadLibrary.
# Native code resolves these by exact fully-qualified name; renaming or removing
# any of them breaks offline ASR (GigaAM) and neural TTS at runtime.
-keep class com.k2fsa.sherpa.** { *; }
-dontwarn com.k2fsa.sherpa.**

# Our TTS sample callback (com.bydmate.app.voice.TtsSamplesCallback) is
# instantiated in Kotlin and invoked from sherpa native code -- keep name+members.
-keep class com.bydmate.app.voice.TtsSamplesCallback { *; }

# The helper package runs under app_process via reflection (the bydmate_helper
# binder daemon: HelperDaemon.main entry + HelperBinderProtocol transaction codes).
-keep class com.bydmate.app.helper.** { *; }

# HiddenApiBypass reflects into hidden framework APIs (ServiceManager) at runtime.
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

# commons-suncalc references findbugs annotations at compile-time only; not on classpath.
-dontwarn edu.umd.cs.findbugs.annotations.**
