#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <mutex>

#define TAG "NativeAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

class AAudioEngine {
public:
    static AAudioEngine& getInstance() {
        static AAudioEngine instance;
        return instance;
    }

    bool init(int32_t sampleRate, int32_t channelCount, int32_t framesPerBurst) {
        std::lock_guard<std::mutex> lock(mutex_);
        closeInternal();

        sampleRate_ = (sampleRate > 0) ? sampleRate : 48000;
        channelCount_ = (channelCount > 0) ? channelCount : 2;
        framesPerBurst_ = (framesPerBurst > 0) ? framesPerBurst : 256;

        AAudioStreamBuilder* builder = nullptr;
        aaudio_result_t result = AAudio_createStreamBuilder(&builder);
        if (result != AAUDIO_OK || builder == nullptr) {
            LOGE("Failed to create AAudioStreamBuilder: %s", AAudio_convertResultToText(result));
            return false;
        }

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setSampleRate(builder, sampleRate_);
        AAudioStreamBuilder_setChannelCount(builder, channelCount_);

        // 1. Request Exclusive sharing mode to bypass AudioFlinger mixer
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        if (framesPerBurst_ > 0) {
            AAudioStreamBuilder_setBufferCapacityInFrames(builder, framesPerBurst_ * 4);
        }

        result = AAudioStreamBuilder_openStream(builder, &stream_);
        if (result != AAUDIO_OK) {
            LOGW("Exclusive AAudioStream open failed: %s; falling back to SHARED mode",
                 AAudio_convertResultToText(result));

            // 2. Fallback to shared low-latency mode if exclusive MMAP is unsupported on route
            AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
            result = AAudioStreamBuilder_openStream(builder, &stream_);
            if (result != AAUDIO_OK) {
                LOGE("Shared AAudioStream open failed: %s", AAudio_convertResultToText(result));
                AAudioStreamBuilder_delete(builder);
                stream_ = nullptr;
                return false;
            }
        }

        AAudioStreamBuilder_delete(builder);

        aaudio_sharing_mode_t actualMode = AAudioStream_getSharingMode(stream_);
        isExclusive_ = (actualMode == AAUDIO_SHARING_MODE_EXCLUSIVE);
        sampleRate_ = AAudioStream_getSampleRate(stream_);
        channelCount_ = AAudioStream_getChannelCount(stream_);
        framesPerBurst_ = AAudioStream_getFramesPerBurst(stream_);

        LOGI("AAudioStream initialized: %d Hz, %d channels, burst=%d, mode=%s",
             sampleRate_, channelCount_, framesPerBurst_,
             isExclusive_ ? "EXCLUSIVE (MMAP Bypass)" : "SHARED");

        return true;
    }

    bool start() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return false;
        aaudio_stream_state_t state = AAudioStream_getState(stream_);
        if (state == AAUDIO_STREAM_STATE_STARTED) {
            return true;
        }
        aaudio_result_t result = AAudioStream_requestStart(stream_);
        if (result == AAUDIO_OK) {
            return true;
        }
        LOGE("AAudioStream_requestStart failed: %s", AAudio_convertResultToText(result));
        return false;
    }

    bool pause() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return false;
        aaudio_result_t result = AAudioStream_requestPause(stream_);
        return (result == AAUDIO_OK);
    }

    bool flush() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return false;
        aaudio_result_t result = AAudioStream_requestFlush(stream_);
        return (result == AAUDIO_OK);
    }

    bool stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return false;
        aaudio_result_t result = AAudioStream_requestStop(stream_);
        return (result == AAUDIO_OK);
    }

    void close() {
        std::lock_guard<std::mutex> lock(mutex_);
        closeInternal();
    }

    int32_t write(const float* buffer, int32_t numFrames, int64_t timeoutNs) {
        if (!stream_ || !buffer || numFrames <= 0) return 0;
        aaudio_result_t result = AAudioStream_write(stream_, buffer, numFrames, timeoutNs);
        if (result < 0) {
            LOGW("AAudioStream_write error: %s", AAudio_convertResultToText(result));
            return result;
        }
        return result;
    }

    bool isExclusive() const { return isExclusive_; }
    int32_t getSampleRate() const { return sampleRate_; }
    int32_t getChannelCount() const { return channelCount_; }
    int32_t getFramesPerBurst() const { return framesPerBurst_; }

    int32_t getXRunCount() const {
        if (!stream_) return 0;
        return AAudioStream_getXRunCount(stream_);
    }

private:
    AAudioEngine() = default;
    ~AAudioEngine() { closeInternal(); }

    void closeInternal() {
        if (stream_) {
            AAudioStream_close(stream_);
            stream_ = nullptr;
        }
        isExclusive_ = false;
    }

    std::mutex mutex_;
    AAudioStream* stream_ = nullptr;
    int32_t sampleRate_ = 48000;
    int32_t channelCount_ = 2;
    int32_t framesPerBurst_ = 256;
    bool isExclusive_ = false;
};

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_music_vibe_playback_audio_AudioNativeBridge_nativeInit(
        JNIEnv* /* env */, jobject /* thiz */,
        jint sampleRate, jint channelCount, jint framesPerBurst) {
    return AAudioEngine::getInstance().init(sampleRate, channelCount, framesPerBurst) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_vibe_playback_audio_AudioNativeBridge_nativeStart(
        JNIEnv* /* env */, jobject /* thiz */) {
    return AAudioEngine::getInstance().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_vibe_playback_audio_AudioNativeBridge_nativePause(
        JNIEnv* /* env */, jobject /* thiz */) {
    return AAudioEngine::getInstance().pause() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_vibe_playback_audio_AudioNativeBridge_nativeFlush(
        JNIEnv* /* env */, jobject /* thiz */) {
    return AAudioEngine::getInstance().flush() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_vibe_playback_audio_AudioNativeBridge_nativeStop(
        JNIEnv* /* env */, jobject /* thiz */) {
    return AAudioEngine::getInstance().stop() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_music_vibe_playback_audio_AudioNativeBridge_nativeClose(
        JNIEnv* /* env */, jobject /* thiz */) {
    AAudioEngine::getInstance().close();
}

JNIEXPORT jint JNICALL
Java_com_music_vibe_playback_audio_AudioNativeBridge_nativeWrite(
        JNIEnv* env, jobject /* thiz */,
        jfloatArray data, jint offset, jint numFrames, jlong timeoutNs) {
    if (!data || numFrames <= 0) return 0;
    jfloat* rawElements = env->GetFloatArrayElements(data, nullptr);
    if (!rawElements) return 0;

    int32_t written = AAudioEngine::getInstance().write(rawElements + offset, numFrames, timeoutNs);
    env->ReleaseFloatArrayElements(data, rawElements, JNI_ABORT);
    return written;
}

JNIEXPORT jboolean JNICALL
Java_com_music_vibe_playback_audio_AudioNativeBridge_nativeIsExclusive(
        JNIEnv* /* env */, jobject /* thiz */) {
    return AAudioEngine::getInstance().isExclusive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_music_vibe_playback_audio_AudioNativeBridge_nativeGetSampleRate(
        JNIEnv* /* env */, jobject /* thiz */) {
    return AAudioEngine::getInstance().getSampleRate();
}

JNIEXPORT jint JNICALL
Java_com_music_vibe_playback_audio_AudioNativeBridge_nativeGetFramesPerBurst(
        JNIEnv* /* env */, jobject /* thiz */) {
    return AAudioEngine::getInstance().getFramesPerBurst();
}

JNIEXPORT jint JNICALL
Java_com_music_vibe_playback_audio_AudioNativeBridge_nativeGetXRunCount(
        JNIEnv* /* env */, jobject /* thiz */) {
    return AAudioEngine::getInstance().getXRunCount();
}

} // extern "C"
