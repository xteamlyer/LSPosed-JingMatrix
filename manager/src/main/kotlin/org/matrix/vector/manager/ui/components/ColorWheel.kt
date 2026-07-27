package org.matrix.vector.manager.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.vector.manager.ui.theme.SeedScheme

/** The chroma the rim of the wheel represents. Past this, almost nothing is in gamut anyway. */
private const val MAX_CHROMA = 110f

/** How large the wheel is rendered before being scaled to fit. */
private const val WHEEL_PIXELS = 320

/**
 * The colour wheel, in the space the theme is actually generated in.
 *
 * Angle is hue and distance from the centre is chroma — which is not decoration, it is the same
 * two numbers [SeedScheme] uses to build every role in the scheme. Pick a point and you have
 * literally pointed at the seed, so the wheel shows what it is choosing rather than being an HSV
 * picker whose output has to be translated into something else afterwards.
 *
 * The centre is grey and the rim is as saturated as sRGB permits, so "how colourful do I want this
 * to be" is a single radial gesture. Colours the display cannot show are drawn at the closest thing
 * it can, which is why the rim looks flat in the yellows and green — that is the shape of the sRGB
 * gamut, not a rendering bug.
 */
@Composable
fun ColorWheel(
    hue: Float,
    chroma: Float,
    dark: Boolean,
    onChange: (hue: Float, chroma: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Drawn at the tone the accent will actually sit at, so the wheel is a preview and not just a
    // generic rainbow: switching to dark mode visibly lightens it, the way the accent does.
    val tone = if (dark) 80f else 45f
    var wheel by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(tone) { wheel = withContext(Dispatchers.Default) { renderWheel(tone) } }

    val haptics = LocalHapticFeedback.current

    fun report(position: Offset, canvas: Size) {
        val radius = minOf(canvas.width, canvas.height) / 2f
        if (radius <= 0f) return
        val dx = position.x - canvas.width / 2f
        val dy = position.y - canvas.height / 2f
        val distance = hypot(dx, dy)

        var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (angle < 0f) angle += 360f
        // Past the rim the gesture still counts, pinned to full chroma — running a finger off the
        // edge should not drop the selection back to grey.
        onChange(angle, (distance / radius * MAX_CHROMA).coerceIn(0f, MAX_CHROMA))
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        report(offset, this.size.toSize())
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> report(offset, this.size.toSize()) }
                    ) { change, _ ->
                        report(change.position, this.size.toSize())
                    }
                }
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            val image = wheel ?: return@Canvas
            drawImage(
                image = image,
                dstSize =
                    IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
            )
            drawThumb(hue, chroma, dark)
        }
    }
}

/** The selection marker: a ring showing the chosen colour, not an arrow pointing at it. */
private fun DrawScope.drawThumb(hue: Float, chroma: Float, dark: Boolean) {
    val radius = minOf(size.width, size.height) / 2f
    val angle = Math.toRadians(hue.toDouble())
    val distance = (chroma / MAX_CHROMA).coerceIn(0f, 1f) * radius
    val centre =
        Offset(
            size.width / 2f + (kotlin.math.cos(angle) * distance).toFloat(),
            size.height / 2f + (kotlin.math.sin(angle) * distance).toFloat(),
        )

    val swatch = SeedScheme.wheelColor(hue, chroma, if (dark) 80f else 45f)
    // Two rings, dark under light, so the thumb stays visible over both the pale centre and the
    // saturated rim without needing to know what is behind it.
    drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = 17.dp.toPx(), center = centre)
    drawCircle(color = Color.White, radius = 15.dp.toPx(), center = centre)
    drawCircle(color = swatch, radius = 12.dp.toPx(), center = centre)
}

/**
 * Paints the disc once per tone.
 *
 * Every pixel is an independent LCh conversion, which is why this runs off the main thread and is
 * cached — at 320² that is a hundred thousand conversions, fine once and hopeless per frame.
 */
private fun renderWheel(tone: Float): ImageBitmap {
    val n = WHEEL_PIXELS
    val pixels = IntArray(n * n)
    val centre = n / 2f
    val radius = n / 2f

    for (y in 0 until n) {
        val dy = y + 0.5f - centre
        for (x in 0 until n) {
            val dx = x + 0.5f - centre
            val distance = hypot(dx, dy)
            if (distance > radius) continue // stays transparent, leaving a clean circle

            var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            if (angle < 0f) angle += 360f

            val colour = SeedScheme.wheelColor(angle, distance / radius * MAX_CHROMA, tone)
            // Feather the last pixel of the rim, or the circle reads as jagged on a low-density
            // screen once it is scaled up.
            val edge = ((radius - distance) / 1.5f).coerceIn(0f, 1f)
            pixels[y * n + x] = colour.copy(alpha = edge).toArgb()
        }
    }

    return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun IntSize.toSize(): Size = Size(width.toFloat(), height.toFloat())
