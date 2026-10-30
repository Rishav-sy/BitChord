package com.music.bitchord.ui.graphics

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt

/**
 * High-precision, continuous-smoothed 8x8 Bayer dither matrix for silky
 * retro-modern pixel-dissolve transitions.
 *
 * Implements smooth cubic Hermite threshold transitions across 128 quantized
 * micro-steps with 24x24px (3px cell) hardware-accelerated tiled bitmap shaders.
 * Eliminates 1-bit hard stepping, dot popping, and jitter, creating an organic
 * liquid-silk dither dissolve.
 */
object DitherMatrix {
    private val BAYER_8X8 = intArrayOf(
         0, 32,  8, 40,  2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44,  4, 36, 14, 46,  6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
         3, 35, 11, 43,  1, 33,  9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47,  7, 39, 13, 45,  5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    private const val STEPS = 128
    private const val SOFTNESS = 0.16f
    private const val CELL_SIZE = 3
    private const val TILE_SIZE = 8 * CELL_SIZE // 24x24 px

    private val brushes = Array<ShaderBrush?>(STEPS) { null }

    fun getBrush(step: Int): ShaderBrush? {
        val clamped = step.coerceIn(0, STEPS - 1)
        if (clamped <= 0 || clamped >= STEPS - 1) return null
        brushes[clamped]?.let { return it }

        // Progress normalized to 0..1
        val progress = clamped.toFloat() / (STEPS - 1)
        // Soft sweep mapping ensuring 100% opacity at progress=0 and 100% transparent at progress=1
        val sweep = progress * (1f + SOFTNESS) - (SOFTNESS / 2f)

        val bitmap = ImageBitmap(TILE_SIZE, TILE_SIZE)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = false
        }

        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val normalizedThreshold = (BAYER_8X8[y * 8 + x] + 0.5f) / 64f
                val diff = normalizedThreshold - sweep

                val cellAlpha = when {
                    diff >= SOFTNESS / 2f -> 1f
                    diff <= -SOFTNESS / 2f -> 0f
                    else -> {
                        val t = (diff / SOFTNESS + 0.5f).coerceIn(0f, 1f)
                        t * t * (3f - 2f * t) // Smooth cubic Hermite interpolation
                    }
                }

                if (cellAlpha > 0.005f) {
                    paint.color = Color.White.copy(alpha = cellAlpha)
                    canvas.drawRect(
                        Rect(
                            left = (x * CELL_SIZE).toFloat(),
                            top = (y * CELL_SIZE).toFloat(),
                            right = ((x + 1) * CELL_SIZE).toFloat(),
                            bottom = ((y + 1) * CELL_SIZE).toFloat(),
                        ),
                        paint,
                    )
                }
            }
        }

        val shader = ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)
        val brush = ShaderBrush(shader)
        brushes[clamped] = brush
        return brush
    }
}

/**
 * Applies a smooth ordered Bayer dither dissolution mask to the composited content.
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
