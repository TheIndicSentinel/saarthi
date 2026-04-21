# MediaPipe — keep runtime classes, suppress missing proto/annotation-processor refs
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.mediapipe.proto.**

# AutoValue / javapoet annotation-processor shims (compile-time only, not in APK)
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-dontwarn dagger.hilt.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# llama.cpp JNI bridge — all members must survive R8 (native methods + TokenCallback)
-keep class com.saarthi.core.inference.engine.LlamaCppBridge { *; }
-keep interface com.saarthi.core.inference.engine.LlamaCppBridge$TokenCallback { *; }

# DataStore / protobuf
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.protobuf.**

# Timber
-dontwarn org.slf4j.**
