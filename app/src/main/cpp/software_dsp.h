#ifndef SOFTWARE_DSP_H
#define SOFTWARE_DSP_H

#include <cmath>
#include <cstdint>
#include <cstring>
#include <algorithm>
#include <mutex>
#include <atomic>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

/**
 * Lightweight In-Stream 5-Band Biquad IIR Equalizer & 32-Band FFT Spectrum Analyzer.
 * Runs 100% in-place on PCM audio data, completely independent of android.media.audiofx.
 */

// Filter types
enum class FilterType {
    LOW_SHELF,
    PEAKING,
    HIGH_SHELF
};

// Coefficients for Direct Form II Transposed Biquad
struct BiquadCoeffs {
    float b0 = 1.0f;
    float b1 = 0.0f;
    float b2 = 0.0f;
    float a1 = 0.0f;
    float a2 = 0.0f;
    bool bypass = true;
};

// Channel state (supports stereo: channel 0 = L, channel 1 = R)
struct BiquadState {
    float s1[2] = {0.0f, 0.0f};
    float s2[2] = {0.0f, 0.0f};

    inline void reset() {
        s1[0] = s1[1] = 0.0f;
        s2[0] = s2[1] = 0.0f;
    }
};

class SoftwareDsp {
public:
    static constexpr int NUM_BANDS = 5;
    static constexpr int VIS_BANDS = 32;
    static constexpr int FFT_SIZE = 512;

    SoftwareDsp(int sampleRate = 44100) : mSampleRate(sampleRate) {
        // Band definitions:
        // Band 0: Low Shelf @ 60 Hz
        // Band 1: Peaking @ 230 Hz (Q = 1.0)
        // Band 2: Peaking @ 910 Hz (Q = 1.0)
        // Band 3: Peaking @ 4000 Hz (Q = 1.0)
        // Band 4: High Shelf @ 14000 Hz
        mFreqs[0] = 60.0f;    mTypes[0] = FilterType::LOW_SHELF;  mQ[0] = 0.707f;
        mFreqs[1] = 230.0f;   mTypes[1] = FilterType::PEAKING;    mQ[1] = 1.0f;
        mFreqs[2] = 910.0f;   mTypes[2] = FilterType::PEAKING;    mQ[2] = 1.0f;
        mFreqs[3] = 4000.0f;  mTypes[3] = FilterType::PEAKING;    mQ[3] = 1.0f;
        mFreqs[4] = 14000.0f; mTypes[4] = FilterType::HIGH_SHELF; mQ[4] = 0.707f;

        for (int i = 0; i < NUM_BANDS; ++i) {
            mGainDb[i] = 0.0f;
            updateCoeffs(i);
            mStates[i].reset();
        }

        // Initialize Hann window for Visualizer FFT
        for (int i = 0; i < FFT_SIZE; ++i) {
            mHann[i] = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(M_PI) * i / (FFT_SIZE - 1)));
        }

        for (int i = 0; i < VIS_BANDS; ++i) {
            mVisualizerBands[i].store(0.0f, std::memory_order_relaxed);
        }
    }

    void setSampleRate(int rate) {
        if (rate > 0 && rate != mSampleRate) {
            std::lock_guard<std::mutex> lk(mLock);
            mSampleRate = rate;
            for (int i = 0; i < NUM_BANDS; ++i) {
                updateCoeffs(i);
                mStates[i].reset();
            }
        }
    }

    void setBandGain(int band, float gainDb) {
        if (band < 0 || band >= NUM_BANDS) return;
        gainDb = std::clamp(gainDb, -15.0f, 15.0f);

        std::lock_guard<std::mutex> lk(mLock);
        mGainDb[band] = gainDb;
        updateCoeffs(band);
    }

    float getBandGain(int band) const {
        if (band < 0 || band >= NUM_BANDS) return 0.0f;
        return mGainDb[band];
    }

    void resetGains() {
        std::lock_guard<std::mutex> lk(mLock);
        for (int i = 0; i < NUM_BANDS; ++i) {
            mGainDb[i] = 0.0f;
            updateCoeffs(i);
            mStates[i].reset();
        }
    }

    /**
     * In-place PCM audio processing and visualizer energy collection.
     * samples: interleaved 16-bit PCM [L0, R0, L1, R1, ...]
     * numFrames: number of stereo frames
     * channels: channel count (usually 2)
     */
    void process(int16_t *samples, int numFrames, int channels) {
        if (!samples || numFrames <= 0) return;

        // 1. In-place Biquad IIR Equalizer
        {
            std::lock_guard<std::mutex> lk(mLock);
            const int chCount = std::min(channels, 2);

            for (int f = 0; f < numFrames; ++f) {
                const int frameIdx = f * channels;

                for (int ch = 0; ch < chCount; ++ch) {
                    float s = static_cast<float>(samples[frameIdx + ch]);

                    // Cascade through the 5 bands
                    for (int b = 0; b < NUM_BANDS; ++b) {
                        const auto &c = mCoeffs[b];
                        if (c.bypass) continue;

                        auto &st = mStates[b];
                        float y = c.b0 * s + st.s1[ch];
                        st.s1[ch] = c.b1 * s - c.a1 * y + st.s2[ch];
                        st.s2[ch] = c.b2 * s - c.a2 * y;
                        s = y;
                    }

                    // Soft saturation / clamping to 16-bit PCM
                    int32_t outSample = static_cast<int32_t>(std::round(s));
                    if (outSample > 32767) outSample = 32767;
                    else if (outSample < -32768) outSample = -32768;
                    samples[frameIdx + ch] = static_cast<int16_t>(outSample);
                }
            }
        }

        // 2. Direct Visualizer Ring-Buffer Feed & Spectrum Analysis
        feedVisualizer(samples, numFrames, channels);
    }

    /**
     * Copies the current 32 visualizer frequency band magnitudes (0.0 .. 1.0) into outBands.
     */
    void getVisualizerBands(float *outBands) {
        if (!outBands) return;
        for (int i = 0; i < VIS_BANDS; ++i) {
            outBands[i] = mVisualizerBands[i].load(std::memory_order_relaxed);
        }
    }

private:
    void updateCoeffs(int band) {
        float gain = mGainDb[band];
        auto &c = mCoeffs[band];

        if (std::abs(gain) < 0.05f) {
            c.bypass = true;
            c.b0 = 1.0f; c.b1 = 0.0f; c.b2 = 0.0f;
            c.a1 = 0.0f; c.a2 = 0.0f;
            return;
        }

        c.bypass = false;
        float f0 = mFreqs[band];
        float Q = mQ[band];
        float Fs = static_cast<float>(mSampleRate);

        float A = std::pow(10.0f, gain / 40.0f);
        float w0 = 2.0f * static_cast<float>(M_PI) * f0 / Fs;
        float cosW0 = std::cos(w0);
        float sinW0 = std::sin(w0);

        float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
        float a0 = 1.0f, a1 = 0.0f, a2 = 0.0f;

        switch (mTypes[band]) {
            case FilterType::PEAKING: {
                float alpha = sinW0 / (2.0f * Q);
                b0 = 1.0f + alpha * A;
                b1 = -2.0f * cosW0;
                b2 = 1.0f - alpha * A;
                a0 = 1.0f + alpha / A;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha / A;
                break;
            }
            case FilterType::LOW_SHELF: {
                float alpha = sinW0 / 2.0f * std::sqrt(2.0f);
                float twoSqrtAAlpha = 2.0f * std::sqrt(A) * alpha;
                b0 = A * ((A + 1.0f) - (A - 1.0f) * cosW0 + twoSqrtAAlpha);
                b1 = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * cosW0);
                b2 = A * ((A + 1.0f) - (A - 1.0f) * cosW0 - twoSqrtAAlpha);
                a0 = (A + 1.0f) + (A - 1.0f) * cosW0 + twoSqrtAAlpha;
                a1 = -2.0f * ((A - 1.0f) + (A + 1.0f) * cosW0);
                a2 = (A + 1.0f) + (A - 1.0f) * cosW0 - twoSqrtAAlpha;
                break;
            }
            case FilterType::HIGH_SHELF: {
                float alpha = sinW0 / 2.0f * std::sqrt(2.0f);
                float twoSqrtAAlpha = 2.0f * std::sqrt(A) * alpha;
                b0 = A * ((A + 1.0f) + (A - 1.0f) * cosW0 + twoSqrtAAlpha);
                b1 = -2.0f * A * ((A - 1.0f) + (A + 1.0f) * cosW0);
                b2 = A * ((A + 1.0f) + (A - 1.0f) * cosW0 - twoSqrtAAlpha);
                a0 = (A + 1.0f) - (A - 1.0f) * cosW0 + twoSqrtAAlpha;
                a1 = 2.0f * ((A - 1.0f) - (A + 1.0f) * cosW0);
                a2 = (A + 1.0f) - (A - 1.0f) * cosW0 - twoSqrtAAlpha;
                break;
            }
        }

        // Normalize coefficients by a0
        c.b0 = b0 / a0;
        c.b1 = b1 / a0;
        c.b2 = b2 / a0;
        c.a1 = a1 / a0;
        c.a2 = a2 / a0;
    }

    void feedVisualizer(const int16_t *samples, int numFrames, int channels) {
        for (int f = 0; f < numFrames; ++f) {
            // Mono mix of left and right channel
            float mono = (channels >= 2) ?
                (samples[f * channels] + samples[f * channels + 1]) * 0.5f :
                static_cast<float>(samples[f * channels]);

            mVisBuffer[mVisBufferWritePos++] = mono;

            if (mVisBufferWritePos >= FFT_SIZE) {
                mVisBufferWritePos = 0;
                computeFftSpectrum();
            }
        }
    }

    // In-place Radix-2 Cooley-Tukey FFT (512-point)
    void computeFftSpectrum() {
        float real[FFT_SIZE];
        float imag[FFT_SIZE];

        // Apply Hann window and copy
        for (int i = 0; i < FFT_SIZE; ++i) {
            real[i] = mVisBuffer[i] * mHann[i];
            imag[i] = 0.0f;
        }

        // Bit-reversal permutation
        int j = 0;
        for (int i = 0; i < FFT_SIZE - 1; ++i) {
            if (i < j) {
                std::swap(real[i], real[j]);
                std::swap(imag[i], imag[j]);
            }
            int k = FFT_SIZE >> 1;
            while (k <= j) {
                j -= k;
                k >>= 1;
            }
            j += k;
        }

        // Cooley-Tukey Radix-2 calculation
        for (int len = 2; len <= FFT_SIZE; len <<= 1) {
            float ang = -2.0f * static_cast<float>(M_PI) / len;
            float wlen_cos = std::cos(ang);
            float wlen_sin = std::sin(ang);

            for (int i = 0; i < FFT_SIZE; i += len) {
                float w_cos = 1.0f;
                float w_sin = 0.0f;

                for (int half = 0; half < len / 2; ++half) {
                    int u = i + half;
                    int v = i + half + len / 2;

                    float tr = w_cos * real[v] - w_sin * imag[v];
                    float ti = w_cos * imag[v] + w_sin * real[v];

                    real[v] = real[u] - tr;
                    imag[v] = imag[u] - ti;
                    real[u] += tr;
                    imag[u] += ti;

                    float next_w_cos = w_cos * wlen_cos - w_sin * wlen_sin;
                    float next_w_sin = w_cos * wlen_sin + w_sin * wlen_cos;
                    w_cos = next_w_cos;
                    w_sin = next_w_sin;
                }
            }
        }

        // Group into 32 logarithmic frequency bands (20 Hz - 16 kHz)
        const int halfFft = FFT_SIZE / 2; // 256 bins
        float bandMagnitudes[VIS_BANDS] = {0.0f};

        for (int b = 0; b < VIS_BANDS; ++b) {
            float normLow = std::pow(static_cast<float>(b) / VIS_BANDS, 1.8f);
            float normHigh = std::pow(static_cast<float>(b + 1) / VIS_BANDS, 1.8f);

            int startBin = std::clamp(static_cast<int>(1 + normLow * 160), 1, 160);
            int endBin = std::clamp(static_cast<int>(startBin + 1 + (normHigh - normLow) * 160), startBin + 1, 161);

            float sum = 0.0f;
            int count = 0;
            for (int bin = startBin; bin < endBin; ++bin) {
                float mag = std::hypot(real[bin], imag[bin]);
                sum += mag;
                count++;
            }

            float avg = (count > 0) ? (sum / count) : 0.0f;
            // Pre-emphasis for human ear curve + log dB compression
            float trebleBoost = 1.0f + (static_cast<float>(b) / VIS_BANDS) * 2.5f;
            float db = (avg > 0.0f) ? 20.0f * std::log10(avg + 1.0f) : 0.0f;
            float norm = std::clamp((db * trebleBoost) / 110.0f, 0.0f, 1.0f);

            bandMagnitudes[b] = norm;
        }

        // Smooth write to atomic visualizer bands
        for (int b = 0; b < VIS_BANDS; ++b) {
            float prev = mVisualizerBands[b].load(std::memory_order_relaxed);
            // Fast attack, smooth decay
            float next = (bandMagnitudes[b] > prev) ?
                prev + (bandMagnitudes[b] - prev) * 0.7f :
                prev * 0.82f;
            mVisualizerBands[b].store(next, std::memory_order_relaxed);
        }
    }

    std::mutex mLock;
    int mSampleRate;
    float mFreqs[NUM_BANDS];
    FilterType mTypes[NUM_BANDS];
    float mQ[NUM_BANDS];
    float mGainDb[NUM_BANDS];
    BiquadCoeffs mCoeffs[NUM_BANDS];
    BiquadState mStates[NUM_BANDS];

    // Visualizer state
    float mHann[FFT_SIZE];
    float mVisBuffer[FFT_SIZE] = {0.0f};
    int mVisBufferWritePos = 0;
    std::atomic<float> mVisualizerBands[VIS_BANDS];
};

#endif // SOFTWARE_DSP_H
