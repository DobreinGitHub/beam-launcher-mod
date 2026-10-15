package com.home.tiles

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * The colour the home screen's background leans towards: the selected app's, set by the apps
 * row, or null when nothing there is selected (or it has no colour of its own).
 */
object AmbientTint {
    val target = mutableStateOf<Color?>(null)

    /** [color] when it is a real colour; greys and whites (black-and-white logos) give no tint. */
    fun of(color: Color?): Color? {
        if (color == null) return null
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        return color.takeIf { max - min > 0.15f }
    }
}

/**
 * The home screen's background, with Google TV style ambient light: a faint wash of
 * [AmbientTint.target], strongest behind the apps row and fading out downwards, so the channel
 * rows keep a neutral background. Slower than the tile's own light, so scrolling through the row
 * drifts rather than flashes.
 *
 * The background and the wash are painted together into one small picture, stretched over the
 * screen in a single pass (both are soft gradients, smooth when stretched). Two full-screen
 * passes, the background and then the wash blended over it, cost the projector's GPU some 3 ms
 * of every frame. The picture is repainted only while the colour changes (in the draw phase,
 * like the rest); the animated XMB background has its own small picture, with the wash over it.
 */
@Composable
fun Backdrop() {
    val target = AmbientTint.target.value?.takeIf { LauncherSettings.ambientTint }
    // Fading out keeps the last colour rather than blending towards grey on the way.
    val last = remember { arrayOf(Color.Transparent) }
    if (target != null) last[0] = target
    val color by animateColorAsState(last[0], tween(500), label = "ambient")
    val strength by animateFloatAsState(if (target != null) 1f else 0f, tween(500), label = "ambient")
    val mask = remember { washMask() }
    if (Colors.isXmb) {
        // XMB paints its gradient and waves together (see XmbWavesLite); the wash goes over them.
        XmbBackground()
        Box(Modifier.fillMaxSize().drawBehind { drawWash(mask, color, strength) })
        return
    }
    val picture = remember { ImageBitmap(WASH_W, WASH_H) }
    val canvas = remember { Canvas(picture) }
    val painter = remember { CanvasDrawScope() }
    Box(
        Modifier.fillMaxSize().drawBehind {
            val brush = Colors.backgroundBrush()
            painter.draw(this, layoutDirection, canvas, Size(WASH_W.toFloat(), WASH_H.toFloat())) {
                drawRect(brush)
                drawWash(mask, color, strength)
            }
            drawImage(
                picture,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.Low,
            )
        },
    )
}

/** The wash over whatever is drawn so far, stretched over this scope's size. */
private fun DrawScope.drawWash(mask: ImageBitmap, color: Color, strength: Float) {
    if (strength <= 0f) return
    // The shape is a small picture made once, coloured as it is drawn: a gradient worked out per
    // pixel every frame was too much for the projector's GPU.
    drawImage(
        mask,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        alpha = strength * if (LauncherSettings.dark) 0.14f else 0.10f,
        colorFilter = ColorFilter.tint(color, BlendMode.SrcIn),
        filterQuality = FilterQuality.Low,
    )
}

// The wash's picture, a tenth of the screen's size; the background is painted at the same size.
private const val WASH_W = 192
private const val WASH_H = 108

/**
 * The wash's shape, at a tenth of the screen's size (smooth enough to stretch): a soft oval,
 * strongest a third of the way across and down, reaching the screen's sides but not its bottom.
 */
private fun washMask(): ImageBitmap {
    val w = WASH_W
    val h = WASH_H
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cx = w * 0.35f
    val cy = h * 0.32f
    val radius = w * 0.7f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt(), 0x26FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 0.8f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    android.graphics.Canvas(bitmap).apply {
        // A circle flattened into a wide oval.
        scale(1f, 0.6f, cx, cy)
        drawCircle(cx, cy, radius, paint)
    }
    return bitmap.asImageBitmap()
}
