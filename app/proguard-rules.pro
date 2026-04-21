# MediaPipe
-keep class com.google.mediapipe.** { *; }
# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
# Keep JNI entry points for llama.cpp bridge
-keep class com.saarthi.core.inference.engine.LlamaCppBridge { *; }
# DataStore
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
