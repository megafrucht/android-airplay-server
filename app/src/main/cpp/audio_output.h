#ifndef AUDIO_OUTPUT_H
#define AUDIO_OUTPUT_H

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <mutex>

#include "log_sink.h"
#include "software_dsp.h"
#include "timeline_buffer.h"

class AudioOutput;

// https://github.com/google/oboe/wiki/TechNote_HowToAvoidCrashes#avoid-deleting-objects-that-are-used-by-callbacks
/*
 * oboe data/error callbacks. oboe can invoke these even after stop()/close():
 * this object owns refs to everything the callbacks touch: a late callback can never
 * reach freed state. holds only weak_ptr to AudioOutput to break the ownership cycles
 * AudioOutput -> OboeCallbacks -> AudioOutput and
 * AudioOutput -> oboe::AudioStream -> OboeCallbacks -> AudioOutput
 */
class OboeCallbacks : public oboe::AudioStreamDataCallback,
                      public oboe::AudioStreamErrorCallback {
public:
    OboeCallbacks(std::shared_ptr<TimelineBuffer> timeline, std::shared_ptr<LogSink> log,
                  std::shared_ptr<SoftwareDsp> dsp)
        : mTimeline(std::move(timeline)), mLog(std::move(log)), mDsp(std::move(dsp)) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *audioData,
                                          int32_t numFrames) override {
        mTimeline->read(static_cast<int16_t *>(audioData), numFrames);
        if (mDsp) {
            mDsp->process(static_cast<int16_t *>(audioData), numFrames, 2);
        }
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream *, oboe::Result error) override;  // needs AudioOutput

private:
    friend class AudioOutput;

    std::shared_ptr<TimelineBuffer> mTimeline;
    std::shared_ptr<LogSink> mLog;
    std::shared_ptr<SoftwareDsp> mDsp;
    std::weak_ptr<AudioOutput> mOwner;
};

/*
 * low-latency audio output via oboe (AAudio or OpenSL ES). data callback pulls PCM from
 * TimelineBuffer; transparently handles device-loss errors (e.g. BT/headset change)
 */
class AudioOutput {
public:
    static std::shared_ptr<AudioOutput> create(int sampleRate, int channels, int oboeBufferFrames,
                                               bool lowLatency,
                                               std::shared_ptr<TimelineBuffer> timeline,
                                               std::shared_ptr<LogSink> log,
                                               std::shared_ptr<SoftwareDsp> dsp) {
        auto out = std::make_shared<AudioOutput>(sampleRate, channels, oboeBufferFrames,
                                                 lowLatency, std::move(timeline), std::move(log),
                                                 std::move(dsp));
        out->mCallbacks->mOwner = out;
        return out;
    }

    AudioOutput(int sampleRate, int channels, int oboeBufferFrames, bool lowLatency,
                std::shared_ptr<TimelineBuffer> timeline, std::shared_ptr<LogSink> log,
                std::shared_ptr<SoftwareDsp> dsp)
        : mSampleRate(sampleRate), mChannels(channels), mOboeBufferFrames(oboeBufferFrames),
          mLowLatency(lowLatency), mTimeline(std::move(timeline)), mLog(std::move(log)),
          mDsp(std::move(dsp)),
          mCallbacks(std::make_shared<OboeCallbacks>(mTimeline, mLog, mDsp)) {}

    ~AudioOutput() { stop(); }

    bool start() {
        std::lock_guard<std::mutex> lk(mLock);
        mClosing.store(false);
        if (!openLocked()) return false;
        if (mStream->requestStart() != oboe::Result::OK) {
            mStream->close();
            mStream.reset();
            return false;
        }
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lk(mLock);
        mClosing.store(true);
        if (mStream) {
            mStream->stop();
            mStream->close();
            mStream.reset();
        }
    }

    // packed: nests into AudioDebugData with fixed layout
    struct __attribute__((packed)) Debug {
        int32_t xrun;  // cumulative
    };
    Debug debugInfo() {
        std::unique_lock<std::mutex> lk(mLock, std::try_to_lock);
        if (lk.owns_lock() && mStream) {
            auto r = mStream->getXRunCount();
            if (r) mLastXrun = r.value();
        }
        return Debug{mLastXrun};
    }

    int32_t getSessionId() const {
        if (mStream) {
            auto sid = mStream->getSessionId();
            if (sid != oboe::SessionId::None && sid != oboe::SessionId::Allocate) {
                return static_cast<int32_t>(sid);
            }
        }
        return 0;
    }

    std::shared_ptr<SoftwareDsp> getDsp() const { return mDsp; }

private:
    friend class OboeCallbacks;

    // oboe error thread, on device loss: reopen and restart unless tearing down
    void reopenAfterError() {
        std::lock_guard<std::mutex> lk(mLock);
        if (mClosing.load()) return;
        mStream.reset();
        mTimeline->reprime();
        if (openLocked()) mStream->requestStart();
    }

    bool openLocked() {  // caller holds mLock
        // see: https://developer.android.com/games/sdk/oboe/low-latency-audio
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::I16)
            ->setChannelCount(mChannels)
            ->setSampleRate(mSampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setContentType(oboe::ContentType::Music)
            ->setSessionId(oboe::SessionId::Allocate)
            ->setDataCallback(mCallbacks)
            ->setErrorCallback(mCallbacks);
        if (mLowLatency) {
            // game use case may enable extra latency optimizations
            b.setPerformanceMode(oboe::PerformanceMode::LowLatency)->setUsage(oboe::Usage::Game);
        } else {
            // media use case may have better quality and lower power
            b.setPerformanceMode(oboe::PerformanceMode::PowerSaving)->setUsage(oboe::Usage::Media);
        }
        oboe::Result r = b.openStream(mStream);
        if (r != oboe::Result::OK) {
            mLog->error("Failed to open Oboe stream: %s", oboe::convertToText(r));
            return false;
        }
        if (mOboeBufferFrames > 0) {
            mStream->setBufferSizeInFrames(mOboeBufferFrames);
        } else if (mLowLatency) {
            // smallest reasonable buffer in low-latency mode
            mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * 2);
        }

        // log what we actually got: oboe may clamp the buffer, fall back to a
        // higher-latency API, or deny the low-latency path
        const bool aaudio = mStream->getAudioApi() == oboe::AudioApi::AAudio;
        const char *api = aaudio ? "AAudio"
                        : mStream->getAudioApi() == oboe::AudioApi::OpenSLES ? "OpenSLES" : "?";
        const bool lowLatency = mStream->getPerformanceMode() == oboe::PerformanceMode::LowLatency;
        const bool mmap = aaudio && oboe::OboeExtensions::isMMapUsed(mStream.get());
        mLog->info("Oboe out: %s%s, mmap=%s, buffer=%d/%d frames, burst=%d, %d Hz",
                  api, lowLatency ? " (low-latency)" : "", mmap ? "yes" : "no",
                  mStream->getBufferSizeInFrames(), mStream->getBufferCapacityInFrames(),
                  mStream->getFramesPerBurst(), mStream->getSampleRate());
        // adaptive tuner needs final buffer size
        mTimeline->noteOutputBufferFrames(mStream->getBufferSizeInFrames());
        return true;
    }

    const int mSampleRate;
    const int mChannels;

    const int mOboeBufferFrames;
    const bool mLowLatency;

    std::shared_ptr<TimelineBuffer> mTimeline;
    std::shared_ptr<LogSink> mLog;
    std::shared_ptr<SoftwareDsp> mDsp;
    std::atomic<bool> mClosing{false};
    std::shared_ptr<OboeCallbacks> mCallbacks;
    std::shared_ptr<oboe::AudioStream> mStream;
    std::mutex mLock;                    // guards open/close
    int32_t mLastXrun = 0;               // debug-poll thread only
};

inline void OboeCallbacks::onErrorAfterClose(oboe::AudioStream *, oboe::Result error) {
    // device disconnect (e.g. BT/headset change): oboe already closed the stream, ask
    // AudioOutput to reopen
    mLog->error("Oboe stream error: %s - reopening", oboe::convertToText(error));
    if (auto owner = mOwner.lock()) owner->reopenAfterError();
}

#endif  // AUDIO_OUTPUT_H
