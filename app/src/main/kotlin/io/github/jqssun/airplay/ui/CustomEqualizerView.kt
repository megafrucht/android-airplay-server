package io.github.jqssun.airplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * High-performance 32-band reactive frequency equalizer (Visualizer) view for Fire TV Stick.
 *
 * 100% independent of android.media.audiofx.Visualizer.
 * Receives spectrum energy directly from the in-stream Software-DSP engine.
 *
 * Features:
 * - 32 frequency bars with logarithmic perceptual grouping (bass, mid, treble)
 * - Pure pitch-black background (#000000)
 * - Vibrant neon Green-to-Cyan gradient (#00FF88 to #00E5FF)
 * - Smooth physics decay with Peak-Hold caps
 * - Throttled to 60 FPS to conserve Fire TV Stick CPU
 */
open class CustomEqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Direct Software-DSP spectrum band data provider
    var bandDataProvider: ((FloatArray) -> Boolean)? = null

    // 32 reactive frequency bars
    private val numBars = 32
    private val polledBands = FloatArray(numBars)
    private val barTargets = FloatArray(numBars)
    private val barHeights = FloatArray(numBars)
    private val peakHeights = FloatArray(numBars)
    private val peakHoldFrames = IntArray(numBars)
    private val peakVelocities = FloatArray(numBars)

    // Physics parameters
    private val fallOffSpeed = 0.032f
    private val attackSpeed = 0.65f
    private val peakHoldDuration = 12 // ~200ms at 60fps
    private val peakGravity = 0.0035f

    // Paints
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00FFFF") // Neon cyan peak cap
    }
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.BLACK // Pure #000000
    }

    private val barRect = RectF()
    private val peakRect = RectF()
    private var barGradient: LinearGradient? = null
    private var lastViewWidth = 0
    private var lastViewHeight = 0

    private var isPlayingAudio = false
    private var idlePhase = 0f

    init {
        setWillNotDraw(false)
    }

    /**
     * Stubs for backward compatibility.
     * With in-stream Software-DSP, audioSessionId is not required.
     */
    fun link(audioSessionId: Int) {
        // No-op for in-stream Software-DSP
    }

    fun unlink() {
        isPlayingAudio = false
        for (i in 0 until numBars) {
            barTargets[i] = 0f
        }
    }

    /**
     * Manually feeds 32 band levels (0.0 .. 1.0).
     */
    fun setBands(bands: FloatArray) {
        val count = minOf(numBars, bands.size)
        var total = 0f
        for (i in 0 until count) {
            val v = bands[i].coerceIn(0f, 1f)
            barTargets[i] = v
            total += v
        }
        isPlayingAudio = total > 0.05f
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != lastViewWidth || h != lastViewHeight)) {
            lastViewWidth = w
            lastViewHeight = h
            // Gradient from bottom (#00FF88 Spring Green) to top (#00E5FF Electric Cyan)
            barGradient = LinearGradient(
                0f, h.toFloat(),
                0f, 0f,
                intArrayOf(
                    Color.parseColor("#00FF88"), // Bright spring neon green
                    Color.parseColor("#00E5FF")  // Vibrant electric cyan
                ),
                floatArrayOf(0.0f, 1.0f),
                Shader.TileMode.CLAMP
            )
            barPaint.shader = barGradient
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        if (width <= 0f || height <= 0f) return

        // 1. Draw pitch black background
        canvas.drawRect(0f, 0f, width, height, bgPaint)

        // 2. Poll in-stream Software-DSP frequency spectrum
        bandDataProvider?.let { provider ->
            if (provider(polledBands)) {
                var energy = 0f
                for (i in 0 until numBars) {
                    val v = polledBands[i].coerceIn(0f, 1f)
                    barTargets[i] = v
                    energy += v
                }
                isPlayingAudio = energy > 0.03f
            }
        }

        // 3. Update physics & animation
        val isIdle = !isPlayingAudio
        if (isIdle) {
            idlePhase += 0.04f
        }

        val paddingHorizontal = width * 0.04f
        val availableWidth = width - 2 * paddingHorizontal
        val barGap = (availableWidth / numBars) * 0.25f
        val barWidth = (availableWidth - (numBars - 1) * barGap) / numBars
        val cornerRadius = barWidth * 0.35f
        val peakCapHeight = max(4f, barWidth * 0.3f)

        for (i in 0 until numBars) {
            val target = if (isIdle) {
                // Gentle ambient sine wave pulse when no audio is streaming
                val wave = 0.05f + 0.03f * kotlin.math.sin(idlePhase + i * 0.3f).toFloat()
                max(0.01f, wave)
            } else {
                barTargets[i]
            }

            // Attack physics (fast jump to peak)
            if (target > barHeights[i]) {
                barHeights[i] += (target - barHeights[i]) * attackSpeed
                if (target > peakHeights[i]) {
                    peakHeights[i] = target
                    peakHoldFrames[i] = peakHoldDuration
                    peakVelocities[i] = 0f
                }
            } else {
                // Smooth decay
                barHeights[i] -= fallOffSpeed
                if (barHeights[i] < 0f) barHeights[i] = 0f

                // Peak-hold fall-off physics
                if (peakHoldFrames[i] > 0) {
                    peakHoldFrames[i]--
                } else {
                    peakVelocities[i] += peakGravity
                    peakHeights[i] -= peakVelocities[i]
                    if (peakHeights[i] < barHeights[i]) {
                        peakHeights[i] = barHeights[i]
                    }
                }
            }

            val currentHeightNorm = barHeights[i].coerceIn(0f, 1f)
            val currentPeakNorm = peakHeights[i].coerceIn(0f, 1f)

            val left = paddingHorizontal + i * (barWidth + barGap)
            val right = left + barWidth
            val maxBarHeight = height * 0.90f
            val barTop = height - (currentHeightNorm * maxBarHeight)

            // Draw frequency bar (Neon Green to Cyan)
            if (currentHeightNorm > 0.01f) {
                barRect.set(left, barTop, right, height)
                canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)
            }

            // Draw Peak-Hold cap
            if (currentPeakNorm > 0.02f) {
                val peakTop = height - (currentPeakNorm * maxBarHeight) - peakCapHeight
                val peakBottom = peakTop + peakCapHeight
                peakRect.set(left, peakTop, right, peakBottom)
                canvas.drawRoundRect(peakRect, cornerRadius / 2, cornerRadius / 2, peakPaint)
            }
        }

        // Loop animation at 60 FPS while view is visible
        if (isAttachedToWindow) {
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unlink()
    }

    companion object {
        private const val TAG = "CustomEqualizerView"
    }
}
