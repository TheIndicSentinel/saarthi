#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "LlamaBridgeStub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeInit(
    JNIEnv*, jobject, jstring, jint, jint, jint) {
    LOGI("Stub: nativeInit — submodule not built");
    return -1L;
}

JNIEXPORT jboolean JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeLoadLoraAdapter(
    JNIEnv*, jobject, jlong, jstring, jfloat) {
    LOGI("Stub: nativeLoadLoraAdapter");
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeClearLoraAdapter(
    JNIEnv*, jobject, jlong) {
    LOGI("Stub: nativeClearLoraAdapter");
}

JNIEXPORT jstring JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeGenerate(
    JNIEnv* env, jobject, jlong, jstring, jint, jfloat, jint) {
    return env->NewStringUTF("[llama.cpp stub]");
}

JNIEXPORT void JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeGenerateStream(
    JNIEnv* env, jobject, jlong, jstring, jint, jfloat, jint, jobject tokenCallback) {
    jclass cbClass = env->GetObjectClass(tokenCallback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");
    if (onToken) {
        jstring tok = env->NewStringUTF("[llama.cpp stub]");
        env->CallBooleanMethod(tokenCallback, onToken, tok);
        env->DeleteLocalRef(tok);
    }
}

JNIEXPORT void JNICALL
Java_com_saarthi_core_inference_engine_LlamaCppBridge_nativeRelease(
    JNIEnv*, jobject, jlong) {
    LOGI("Stub: nativeRelease");
}

} // extern "C"
