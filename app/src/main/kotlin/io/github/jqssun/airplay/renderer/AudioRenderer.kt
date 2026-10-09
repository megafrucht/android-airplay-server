package io.github.jqssun.airplay.renderer

import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.viewmodel.AudioDebug

// wrapper around native audio engine
class AudioRenderer {

    @Volatile var config = AudioConfig(); private set

    @Volatile var codecLabel = ""; private set

    private var serverHandle = 0L
    private val bandGains = FloatArray(5)

    @Synchronized
    fun attachEngine(server: Long) {
        serverHandle = server
        pushConfig()
        for (i in bandGains.indices) {
            NativeBridge.nativeSetEqualizerBandGain(serverHandle, i, bandGains[i])
        }
    }

    @Synchronized
    fun detachEngine() {
        serverHandle = 0L
        codecLabel = ""
    }

    // open playout (first onAudioFormat, or resume after pause); idempotent natively
    @Synchronized
    fun start() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioStart(serverHandle)
    }

    // releases oboe stream + audio device while idle; engine stays alive, freed on server destroy
    @Synchronized
    fun stop() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioStop(serverHandle)
        codecLabel = ""
    }

    @Synchronized
    fun getSessionId(): Int {
        if (serverHandle == 0L) return 0
        return NativeBridge.nativeServerAudioGetSessionId(serverHandle)
    }

    @Synchronized
    fun setEqualizerBandGain(band: Int, gainDb: Float) {
        if (band in bandGains.indices) {
            bandGains[band] = gainDb
        }
        if (serverHandle != 0L) {
            NativeBridge.nativeSetEqualizerBandGain(serverHandle, band, gainDb)
        }
    }

    @Synchronized
    fun getEqualizerBandGain(band: Int): Float {
        if (serverHandle != 0L) {
            return NativeBridge.nativeGetEqualizerBandGain(serverHandle, band)
        }
        return if (band in bandGains.indices) bandGains[band] else 0f
    }

    @Synchronized
    fun resetEqualizer() {
        for (i in bandGains.indices) {
            bandGains[i] = 0f
        }
        if (serverHandle != 0L) {
            NativeBridge.nativeResetEqualizer(serverHandle)
        }
    }

    @Synchronized
    fun getVisualizerBands(outBands: FloatArray): Boolean {
        if (serverHandle == 0L) return false
        return NativeBridge.nativeGetVisualizerBands(serverHandle, outBands)
    }

    @Synchronized
    fun updateConfig(newConfig: AudioConfig) {
        config = newConfig
        pushConfig()
    }

    private fun pushConfig() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioConfigure(
            serverHandle, config.cushionMs, config.percentilePct, config.oboeBufferFrames,
            config.forceSwAlac, config.realtimePriority, config.lowLatency, config.benchmarkLog)
    }

    private val debugBuf = java.nio.ByteBuffer.allocateDirect(DEBUG_BUF_BYTES)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)

    // field order/width must mirror native packed AudioDebugData exactly
    @Synchronized
    fun audioDebug(): AudioDebug? {
        if (serverHandle == 0L) return null
        if (!NativeBridge.nativeServerAudioDebug(serverHandle, debugBuf)) return null
        val buf = debugBuf
        buf.rewind()
        val backlogMs = buf.short.toInt() and 0xFFFF
        val tunedCushionMs = buf.short.toInt() and 0xFFFF
        val trims = buf.int
        val drops = buf.int
        val silences = buf.int
        val underruns = buf.int
        val meanUs = buf.int
        val maxUs = buf.int
        val held = buf.short.toInt() and 0xFFFF
        val xrun = buf.int
        val decodeErrors = buf.int
        return AudioDebug(backlogMs, tunedCushionMs, trims, drops, silences, underruns, xrun,
                          meanUs, maxUs, held, decodeErrors)
    }

    @Synchronized
    fun setFormat(ct: Int, spf: Int) {
        codecLabel = when (ct) {
            CT_ALAC -> "ALAC"; CT_AAC_LC -> "AAC-LC"; CT_AAC_ELD -> "AAC-ELD"; else -> "?"
        }
        if (serverHandle != 0L) NativeBridge.nativeServerAudioFormat(serverHandle, ct, spf)
    }

    companion object {
        // must be >= native sizeof(AudioDebugData) (38 B)
        private const val DEBUG_BUF_BYTES = 64
        const val CT_ALAC = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8
    }
}
