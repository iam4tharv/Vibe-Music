#ifndef VIBEMUSIC_AAUDIO_ENGINE_H
#define VIBEMUSIC_AAUDIO_ENGINE_H

#include <aaudio/AAudio.h>
#include <cstdint>
#include <atomic>

namespace vibe {

class AAudioEngine {
public:
    AAudioEngine();
    ~AAudioEngine();

    bool open(int32_t sampleRate, int32_t channelCount, int32_t framesPerBurst);
    bool start();
    bool pause();
    bool flush();
    bool stop();
    void close();

    int32_t write(const float* buffer, int32_t numFrames, int64_t timeoutNanoseconds);

    int32_t getSampleRate() const { return sampleRate_; }
    int32_t getChannelCount() const { return channelCount_; }
    bool isExclusive() const { return isExclusive_; }
    int32_t getFramesPerBurst() const { return framesPerBurst_; }
    int32_t getBufferSizeInFrames() const;
    int32_t getXRunCount() const;
    bool isRunning() const { return isRunning_.load(); }

private:
    AAudioStream* stream_ = nullptr;
    int32_t sampleRate_ = 48000;
    int32_t channelCount_ = 2;
    int32_t framesPerBurst_ = 256;
    bool isExclusive_ = false;
    std::atomic<bool> isRunning_{false};
};

} // namespace vibe

#endif // VIBEMUSIC_AAUDIO_ENGINE_H
