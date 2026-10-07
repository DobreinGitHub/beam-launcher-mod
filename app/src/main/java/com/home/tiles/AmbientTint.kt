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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale

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
    Box(
        Modifier.fillMaxSize().drawBehind {
            if (strength <= 0f) return@drawBehind
            val alpha = strength * if (LauncherSettings.dark) 0.14f else 0.10f
            val center = Offset(size.width * 0.35f, size.height * 0.32f)
            val radius = size.width * 0.7f
            // A circle flattened into a wide oval: reaches the screen's sides, not its bottom.
            scale(scaleX = 1f, scaleY = 0.6f, pivot = center) {
                drawCircle(
                    Brush.radialGradient(
                        0f to color.copy(alpha = alpha),
                        0.45f to color.copy(alpha = alpha * 0.6f),
                        0.8f to color.copy(alpha = alpha * 0.15f),
                        1f to Color.Transparent,
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
        },
    )
}
