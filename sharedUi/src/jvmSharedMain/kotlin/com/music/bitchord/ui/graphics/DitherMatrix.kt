package com.music.bitchord.ui.graphics

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Damascus Wave procedural transition mask.
 *
 * Implements a continuous-smoothed, periodic domain-warped topological
 * potential field. Eliminates discrete pixel grid dots and OLED Moiré
 * strobing, producing an organic, silky Damascus steel wave ribbon dissolution.
 *
 * Uses 128 quantized micro-steps rendered through hardware-accelerated tiled
 * ARGB bitmap shaders with smooth cubic Hermite edge antialiasing.
 */
object DitherMatrix {
    private const val STEPS = 128
    private const val SOFTNESS = 0.18f
    private const val TILE_SIZE = 96

    // Precomputed normalized periodic Damascus potential field (0f..1f)
    private val DAMASCUS_FIELD: FloatArray by lazy {
        val field = FloatArray(TILE_SIZE * TILE_SIZE)
        val twoPi = (2.0 * Math.PI).toFloat()
        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE
        val raw = FloatArray(TILE_SIZE * TILE_SIZE)

        for (y in 0 until TILE_SIZE) {
            val py = twoPi * y / TILE_SIZE
            for (x in 0 until TILE_SIZE) {
                val px = twoPi * x / TILE_SIZE
                val qx = px + 0.8f * sin(2f * py + 0.4f)
                val qy = py + 0.8f * cos(2f * px + 0.2f)
                val rx = qx + 0.6f * sin(3f * qy + px)
                val ry = qy + 0.6f * cos(3f * qx + py)
                val s = 0.5f * sin(2f * rx + 3f * ry) +
                        0.3f * cos(4f * rx - 2f * ry) +
                        0.2f * sin(5f * ry + rx)
                val idx = y * TILE_SIZE + x
                raw[idx] = s
                if (s < minVal) minVal = s
                if (s > maxVal) maxVal = s
            }
        }

        val range = (maxVal - minVal).coerceAtLeast(1e-5f)
        for (i in raw.indices) {
            field[i] = (raw[i] - minVal) / range
        }
        field
    }

    private val brushes = Array<ShaderBrush?>(STEPS) { null }

    fun getBrush(step: Int): ShaderBrush? {
        val clamped = step.coerceIn(0, STEPS - 1)
        if (clamped <= 0 || clamped >= STEPS - 1) return null
        brushes[clamped]?.let { return it }

        val progress = clamped.toFloat() / (STEPS - 1)
        val sweep = progress * (1f + SOFTNESS) - (SOFTNESS / 2f)

        val pixels = IntArray(TILE_SIZE * TILE_SIZE)
        val field = DAMASCUS_FIELD

        for (i in pixels.indices) {
            val diff = field[i] - sweep
            val cellAlpha = when {
                diff >= SOFTNESS / 2f -> 1f
                diff <= -SOFTNESS / 2f -> 0f
                else -> {
                    val t = (diff / SOFTNESS + 0.5f).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t)
                }
            }
            val a = (cellAlpha * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or 0x00FFFFFF
        }

        val bitmap = imageBitmapOf(pixels, TILE_SIZE, TILE_SIZE)
        val shader = ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)
        val brush = ShaderBrush(shader)
        brushes[clamped] = brush
        return brush
    }
}

/**
 * Applies a silky Damascus wave dissolution mask to the composited content.
 * Accepts a continuous floating-point [progressProvider] (0.0f..1.0f) and maps it
 * dynamically at draw time to avoid recomposition during animation.
 */
fun Modifier.ditherDissolve(progressProvider: () -> Float): Modifier =
    this.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            val progress = progressProvider().coerceIn(0f, 1f)
            if (progress >= 1f) return@drawWithContent
            drawContent()
            if (progress > 0f) {
                val step = (progress * 127f).roundToInt().coerceIn(0, 127)
                DitherMatrix.getBrush(step)?.let { brush ->
                    drawRect(brush = brush, blendMode = BlendMode.DstIn)
                }
            }
        }

