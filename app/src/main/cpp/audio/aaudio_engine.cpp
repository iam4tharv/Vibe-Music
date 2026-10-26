#include "aaudio_engine.h"
#include <android/log.h>
#include <algorithm>

#define TAG "AAudioEngineNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace vibe {

AAudioEngine::AAudioEngine() = default;

AAudioEngine::~AAudioEngine() {
    close();
}

bool AAudioEngine::open(int32_t sampleRate, int32_t channelCount, int32_t framesPerBurst) {
    close();

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

    // Attempt Exclusive MMAP mode first to bypass AudioFlinger
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    if (framesPerBurst_ > 0) {
        AAudioStreamBuilder_setBufferCapacityInFrames(builder, framesPerBurst_ * 4);
    }

    result = AAudioStreamBuilder_openStream(builder, &stream_);
    if (result != AAUDIO_OK) {
        LOGW("Exclusive AAudioStream open failed: %s; falling back to SHARED low-latency mode",
             AAudio_convertResultToText(result));

        // Fallback to shared low-latency mode
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        result = AAudioStreamBuilder_openStream(builder, &stream_);
        if (result != AAUDIO_OK) {
            LOGE("Shared AAudioStream open also failed: %s", AAudio_convertResultToText(result));
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

    LOGI("AAudioStream opened successfully: %d Hz, %d ch, burst=%d, mode=%s",
         sampleRate_, channelCount_, framesPerBurst_,
         isExclusive_ ? "EXCLUSIVE (MMAP Bypass)" : "SHARED");

    return true;
}

bool AAudioEngine::start() {
    if (!stream_) return false;
    aaudio_stream_state_t state = AAudioStream_getState(stream_);
    if (state == AAUDIO_STREAM_STATE_STARTED) {
        isRunning_.store(true);
        return true;
    }
    aaudio_result_t result = AAudioStream_requestStart(stream_);
    if (result == AAUDIO_OK) {
        isRunning_.store(true);
        return true;
    }
    LOGE("AAudioStream_requestStart failed: %s", AAudio_convertResultToText(result));
    return false;
}

bool AAudioEngine::pause() {
    if (!stream_) return false;
    isRunning_.store(false);
    aaudio_result_t result = AAudioStream_requestPause(stream_);
    return (result == AAUDIO_OK);
}

bool AAudioEngine::flush() {
    if (!stream_) return false;
    aaudio_result_t result = AAudioStream_requestFlush(stream_);
    return (result == AAUDIO_OK);
}

bool AAudioEngine::stop() {
    if (!stream_) return false;
    isRunning_.store(false);
    aaudio_result_t result = AAudioStream_requestStop(stream_);
    return (result == AAUDIO_OK);
}

void AAudioEngine::close() {
    isRunning_.store(false);
    if (stream_) {
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

int32_t AAudioEngine::write(const float* buffer, int32_t numFrames, int64_t timeoutNanoseconds) {
    if (!stream_ || !buffer || numFrames <= 0) return 0;
    aaudio_result_t result = AAudioStream_write(stream_, buffer, numFrames, timeoutNanoseconds);
    if (result < 0) {
        LOGW("AAudioStream_write returned error: %s", AAudio_convertResultToText(result));
        return result;
    }
    return result;
}

int32_t AAudioEngine::getBufferSizeInFrames() const {
    if (!stream_) return 0;
    return AAudioStream_getBufferSizeInFrames(stream_);
}

int32_t AAudioEngine::getXRunCount() const {
    if (!stream_) return 0;
    return AAudioStream_getXRunCount(stream_);
}

} // namespace vibe
