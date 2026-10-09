package io.github.jqssun.airplay.audio

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.jqssun.airplay.renderer.AudioRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Controller for 100% In-Stream Software-DSP Equalizer.
 * Completely independent of fragile android.media.audiofx.
 * Configured for 5 frequency bands:
 * - Band 0: 60 Hz (Low Shelf)
 * - Band 1: 230 Hz (Peaking)
 * - Band 2: 910 Hz (Peaking)
 * - Band 3: 4000 Hz (Peaking)
 * - Band 4: 14000 Hz (High Shelf)
 * Adjustable range: -15 dB to +15 dB (-1500 mB to +1500 mB).
 */
class AirPlayEqualizerManager(private val context: Context? = null) {

    private val prefs: SharedPreferences? by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var audioRenderer: AudioRenderer? = null

    val minLevelMb: Short = -1500
    val maxLevelMb: Short = 1500

    val minGainDb: Float = -15.0f
    val maxGainDb: Float = 15.0f

    // 5 target frequency bands
    private val defaultBandNames = listOf("60 Hz", "230 Hz", "910 Hz", "4 kHz", "14 kHz")
    private val defaultFrequenciesHz = listOf(60, 230, 910, 4000, 14000)

    private val _bandNames = MutableStateFlow(defaultBandNames)
    val bandNames: StateFlow<List<String>> = _bandNames.asStateFlow()

    private val _bandFrequencies = MutableStateFlow(defaultFrequenciesHz)
    val bandFrequencies: StateFlow<List<Int>> = _bandFrequencies.asStateFlow()

    private val _bandLevels = MutableStateFlow<List<Short>>(listOf(0, 0, 0, 0, 0))
    val bandLevels: StateFlow<List<Short>> = _bandLevels.asStateFlow()

    // Software DSP is always available
    private val _isAvailable = MutableStateFlow(true)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _currentSessionId = MutableStateFlow(0)
    val currentSessionId: StateFlow<Int> = _currentSessionId.asStateFlow()

    init {
        // Load initial saved band levels (stored in mB: -1500 to +1500)
        val saved = (0 until 5).map { b ->
            prefs?.getInt(KEY_BAND_PREFIX + b, 0)?.toShort()?.coerceIn(minLevelMb, maxLevelMb) ?: 0.toShort()
        }
        _bandLevels.value = saved
    }

    /**
     * Attaches the active AudioRenderer to push equalizer gains directly to native DSP.
     */
    @Synchronized
    fun attachRenderer(renderer: AudioRenderer?) {
        audioRenderer = renderer
        if (renderer != null) {
            // Push all current levels to native DSP
            val current = _bandLevels.value
            for (i in 0 until minOf(5, current.size)) {
                val gainDb = current[i] / 100.0f
                renderer.setEqualizerBandGain(i, gainDb)
            }
        }
    }

    /**
     * Backward-compatible initialization with audio session id.
     * Software DSP is 100% in-process and doesn't require OS session binding.
     */
    @Synchronized
    fun init(audioSessionId: Int = 0) {
        _currentSessionId.value = audioSessionId
        _isAvailable.value = true
        // Re-apply gains to audioRenderer if attached
        audioRenderer?.let { renderer ->
            val current = _bandLevels.value
            for (i in 0 until minOf(5, current.size)) {
                val gainDb = current[i] / 100.0f
                renderer.setEqualizerBandGain(i, gainDb)
            }
        }
        Log.i(TAG, "Software-DSP 5-band equalizer active (session $audioSessionId)")
    }

    /**
     * Sets the gain level of the given band in millibels (-1500 to +1500 mB).
     */
    @Synchronized
    fun setBandLevel(band: Short, levelMb: Short) {
        val clamped = levelMb.coerceIn(minLevelMb, maxLevelMb)
        val idx = band.toInt()
        val curList = _bandLevels.value.toMutableList()

        if (idx in curList.indices) {
            curList[idx] = clamped
            _bandLevels.value = curList
        }

        prefs?.edit()?.putInt(KEY_BAND_PREFIX + idx, clamped.toInt())?.apply()

        val gainDb = clamped / 100.0f
        audioRenderer?.setEqualizerBandGain(idx, gainDb)
        Log.d(TAG, "Equalizer band $band (${_bandNames.value.getOrNull(idx)}) set to $clamped mB ($gainDb dB)")
    }

    /**
     * Sets the gain level directly in dB (-15.0f to +15.0f).
     */
    @Synchronized
    fun setBandGain(bandIndex: Int, gainDb: Float) {
        val clampedDb = gainDb.coerceIn(minGainDb, maxGainDb)
        val levelMb = (clampedDb * 100.0f).roundToInt().toShort()
        setBandLevel(bandIndex.toShort(), levelMb)
    }

    /**
     * Adjusts the band by dB step (e.g. +1 dB or -1 dB).
     */
    @Synchronized
    fun adjustBandLevelDb(band: Short, deltaDb: Int) {
        val current = getBandLevel(band)
        val target = (current + deltaDb * 100).toShort()
        setBandLevel(band, target)
    }

    /**
     * Sets the band level directly in decibels (e.g. -15 dB to +15 dB).
     */
    fun setBandLevelDb(band: Short, levelDb: Int) {
        setBandLevel(band, (levelDb * 100).toShort())
    }

    /**
     * Returns the current level of a band in millibels (-1500 to +1500).
     */
    fun getBandLevel(band: Short): Short {
        return _bandLevels.value.getOrElse(band.toInt()) { 0 }
    }

    /**
     * Returns the current level of a band in decibels (-15 to +15).
     */
    fun getBandLevelDb(band: Short): Int {
        return (getBandLevel(band) / 100)
    }

    /**
     * Resets all bands to 0 dB flat.
     */
    @Synchronized
    fun resetFlat() {
        for (i in 0 until 5) {
            setBandLevel(i.toShort(), 0)
        }
        audioRenderer?.resetEqualizer()
    }

    /**
     * Releases or unbinds resources (clean no-op for software DSP).
     */
    @Synchronized
    fun release() {
        Log.d(TAG, "Equalizer released")
    }

    companion object {
        private const val TAG = "AirPlayEQManager"
        private const val PREFS_NAME = "airplay_equalizer_prefs"
        private const val KEY_BAND_PREFIX = "eq_band_"
    }
}
