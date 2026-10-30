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

/**
 * Ordered 8x8 Bayer dither matrix for retro-modern pixel-dissolve transitions.
 *
 * Provides hardware-accelerated tiled bitmap shaders for each of the 64 quantization
 * steps. Each step produces an 8x8 matrix (scaled to 32x32px for crisp pixel aesthetics)
 * that is repeated infinitely across the compositing layer.
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

    private val brushes = Array<ShaderBrush?>(65) { null }

    fun getBrush(step: Int): ShaderBrush? {
        val clamped = step.coerceIn(0, 64)
        if (clamped <= 0 || clamped >= 64) return null
        brushes[clamped]?.let { return it }

        val cellSize = 4
        val side = 8 * cellSize
        val bitmap = ImageBitmap(side, side)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.White
            isAntiAlias = false
        }
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val threshold = BAYER_8X8[y * 8 + x]
                if (threshold >= clamped) {
                    canvas.drawRect(
                        Rect(
                            left = (x * cellSize).toFloat(),
                            top = (y * cellSize).toFloat(),
                            right = ((x + 1) * cellSize).toFloat(),
                            bottom = ((y + 1) * cellSize).toFloat(),
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
 * Applies an ordered Bayer dither dissolution mask to the composited content.
 * Reads [stepProvider] at draw time to avoid recomposition during animation.
 */
fun Modifier.ditherDissolve(stepProvider: () -> Int): Modifier =
    this.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            val step = stepProvider().coerceIn(0, 64)
            if (step >= 64) return@drawWithContent
            drawContent()
            if (step > 0) {
                DitherMatrix.getBrush(step)?.let { brush ->
                    drawRect(brush = brush, blendMode = BlendMode.DstIn)
                }
            }
        }
