#include <jni.h>
#include "../audio/aaudio_engine.h"

using namespace vibe;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_music_vibe_playback_audio_aaudio_AAudioDirectEngine_nativeCreate(
        JNIEnv* /* env */, jobject /* thiz */) {
    return reinterpret_cast<jlong>(new AAudioEngine());
}

JNIEXPORT jboolean JNICALL
Java_com_music_vibe_playback_audio_aaudio_AAudioDirectEngine_nativeOpen(
        JNIEnv* /* env */, jobject /* thiz */,
        jlong handle, jint sampleRate, jint channelCount, jint framesPerBurst) {
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->open(sampleRate, channelCount, framesPerBurst) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_vibe_playback_audio_aaudio_AAudioDirectEngine_nativeStart(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_vibe_playback_audio_aaudio_AAudioDirectEngine_nativePause(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->pause() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_vibe_playback_audio_aaudio_AAudioDirectEngine_nativeFlush(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->flush() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_vibe_playback_audio_aaudio_AAudioDirectEngine_nativeStop(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->stop() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_music_vibe_playback_audio_aaudio_AAudioDirectEngine_nativeClose(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    if (engine) engine->close();
}

JNIEXPORT void JNICALL
Java_com_music_vibe_playback_audio_aaudio_AAudioDirectEngine_nativeDestroy(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    delete engine;
}

JNIEXPORT jint JNICALL
Java_com_music_vibe_playback_audio_aaudio_AAudioDirectEngine_nativeWrite(
        JNIEnv* env, jobject /* thiz */,
        jlong handle, jfloatArray data, jint offset, jint numFrames, jlong timeoutNs) {
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    if (!engine || !data || numFrames <= 0) return 0;

    jfloat* rawElements = env->GetFloatArrayElements(data, nullptr);
    if (!rawElements) return 0;

    int32_t written = engine->write(rawElements + offset, numFrames, timeoutNs);
    env->ReleaseFloatArrayElements(data, rawElements, JNI_ABORT);
    return written;
}

JNIEXPORT jboolean JNICALL
Java_com_music_vibe_playback_audio_aaudio_AAudioDirectEngine_nativeIsExclusive(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->isExclusive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_music_vibe_playback_audio_aaudio_AAudioDirectEngine_nativeGetSampleRate(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    return engine ? engine->getSampleRate() : 0;
}

JNIEXPORT jint JNICALL
Java_com_music_vibe_playback_audio_aaudio_AAudioDirectEngine_nativeGetFramesPerBurst(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    return engine ? engine->getFramesPerBurst() : 0;
}

JNIEXPORT jint JNICALL
Java_com_music_vibe_playback_audio_aaudio_AAudioDirectEngine_nativeGetXRunCount(
        JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    return engine ? engine->getXRunCount() : 0;
}

} // extern "C"
