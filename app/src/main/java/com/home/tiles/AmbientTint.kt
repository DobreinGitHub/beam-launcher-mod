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
import android.graphics.Canvas
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
 * Google TV style ambient light: a faint wash of [AmbientTint.target] over the background,
 * strongest behind the apps row and fading out downwards, so the channel rows keep a neutral
 * background. Slower than the tile's own light, so scrolling through the row drifts rather
 * than flashes. Drawn in the draw phase only: it costs nothing while the colour stands still.
 */
@Composable
fun AmbientWash() {
    val target = AmbientTint.target.value?.takeIf { LauncherSettings.ambientTint }
    // Fading out keeps the last colour rather than blending towards grey on the way.
    val last = remember { arrayOf(Color.Transparent) }
    if (target != null) last[0] = target
    val color by animateColorAsState(last[0], tween(500), label = "ambient")
    val strength by animateFloatAsState(if (target != null) 1f else 0f, tween(500), label = "ambient")
    val mask = remember { washMask() }
    Box(
        Modifier.fillMaxSize().drawBehind {
            if (strength <= 0f) return@drawBehind
            // The shape is a small picture made once, stretched over the screen and coloured as
            // it is drawn: a full-screen gradient worked out per pixel every frame was too much
            // for the projector's GPU (frames took twice as long).
            drawImage(
                mask,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                alpha = strength * if (LauncherSettings.dark) 0.14f else 0.10f,
                colorFilter = ColorFilter.tint(color, BlendMode.SrcIn),
                filterQuality = FilterQuality.Low,
            )
        },
    )
}

/**
 * The wash's shape, at a tenth of the screen's size (smooth enough to stretch): a soft oval,
 * strongest a third of the way across and down, reaching the screen's sides but not its bottom.
 */
private fun washMask(): ImageBitmap {
    val w = 192
    val h = 108
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
    Canvas(bitmap).apply {
        // A circle flattened into a wide oval.
        scale(1f, 0.6f, cx, cy)
        drawCircle(cx, cy, radius, paint)
    }
    return bitmap.asImageBitmap()
}
