# Saarthi release ProGuard / R8 rules.
#
# Most deps (Hilt, Compose, Coil, Room, Timber, kotlinx-coroutines, DataStore)
# ship consumer-rules.pro in their AARs — those keep rules apply automatically.
# This file covers only the gaps R8's defaults can't infer.

# ── Annotation-processor shims (compile-time only, not in APK) ────────────
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**

# ── Hilt ───────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-dontwarn dagger.hilt.**

# ── Kotlin coroutines (readable stacks for our DebugLogger) ────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── litertlm-android (JNI) ────────────────────────────────────────────────
# Native code calls into these classes by reflection; obfuscating their names
# would break model load.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-keepclasseswithmembers class * {
    native <methods>;
}

# ── DataStore / protobuf ───────────────────────────────────────────────────
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.protobuf.**

# ── TensorFlow Lite GPU delegate (pulled in transitively by litertlm) ──────
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.gpu.**

# ── Timber (slf4j shim is optional, not on the classpath) ──────────────────
-dontwarn org.slf4j.**

# ── Stack-trace readability ────────────────────────────────────────────────
# Worth a small APK cost: SaarthiApp.kt's uncaught-handler logs the stack to
# saarthi_debug.log on disk, which is useless if R8 has stripped line numbers.
-keepattributes SourceFile, LineNumberTable
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
