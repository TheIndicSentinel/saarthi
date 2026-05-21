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
-renamesourcefileattribute SourceFile

# ── @Keep + AndroidX annotation surface ────────────────────────────────────
-keep,allowobfuscation,allowshrinking @androidx.annotation.Keep class *
-keep class * { @androidx.annotation.Keep *; }
-keep,allowobfuscation,allowshrinking @interface androidx.annotation.Keep

# ── Kotlin metadata ────────────────────────────────────────────────────────
# Without these, R8 strips Kotlin metadata that reflection-heavy libs
# (Hilt/JSR-330, Compose tooling, kotlinx.serialization) need to resolve
# generics. The release build emits "kotlin metadata" warnings until this
# block is in place.
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keepclassmembers class kotlin.coroutines.SafeContinuation { java.lang.Object result; }
-keepclassmembers class kotlin.coroutines.jvm.internal.BaseContinuationImpl { ** label; }
-dontwarn kotlin.reflect.**
-dontwarn kotlin.coroutines.jvm.internal.**

# ── Saarthi inference surface (called via JNI + reflection) ────────────────
# LiteRT's native callbacks reflect into our engine classes by name; obfuscating
# them would silently break the conversation matrix on release builds.
-keep class com.saarthi.core.inference.engine.** { *; }
-keep class com.saarthi.core.inference.model.** { *; }
-keep interface com.saarthi.core.inference.engine.** { *; }
-keepclassmembers class com.saarthi.core.inference.** {
    public <init>(...);
}

# ── Hilt-generated factories ───────────────────────────────────────────────
# @Inject constructors must keep their signature for the generated
# *_Factory / *_HiltModules classes to resolve their constructor args.
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}
-keep class **_HiltModules*  { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }
-keepclasseswithmembernames class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# ── Compose runtime + ui ──────────────────────────────────────────────────
# Compose ships consumer-rules.pro, but its tooling-only classes can hit
# obfuscation edge cases on layout inspector / preview surfaces.
-dontwarn androidx.compose.runtime.**
-dontwarn androidx.compose.ui.tooling.**

# ── Firebase Crashlytics (optional — only effective when google-services.json
#    is present and the firebase plugins are applied; harmless otherwise) ──
-keepattributes *Annotation*
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
