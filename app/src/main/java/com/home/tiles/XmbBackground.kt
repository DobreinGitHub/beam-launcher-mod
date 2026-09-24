package com.home.tiles

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import java.util.Calendar
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import kotlin.random.Random
import kotlin.math.sin
import kotlin.math.cos
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import android.media.AudioManager

/** PS3 XMB month colours, January to December. */
val XmbColors = listOf(
    Color(0xFFB8BCC4), Color(0xFFD4AF37), Color(0xFF6DB33F), Color(0xFFE58FB5),
    Color(0xFF2E9E57), Color(0xFFB04FB8), Color(0xFF26AFBF), Color(0xFF3C86DA),
    Color(0xFF6E55CC), Color(0xFFD08A2C), Color(0xFF8B5A2E), Color(0xFFC8363C),
)

fun xmbColor(): Color {
    val chosen = LauncherSettings.xmbColor
    return XmbColors[if (chosen in XmbColors.indices) chosen else Calendar.getInstance().get(Calendar.MONTH)]
}

/** Top-to-bottom gradient in the month colour, lighter in the light theme like the PS3's daytime XMB. */
fun xmbStops(dark: Boolean): List<Color> {
    val c = xmbColor()
    return if (dark) listOf(lerp(c, Color.Black, 0.35f), lerp(c, Color.Black, 0.8f))
    else listOf(lerp(c, Color.White, 0.55f), lerp(c, Color.White, 0.15f))
}

/**
 * The XMB backdrop: month gradient, translucent waves with bright crests, drifting sparkles.
 * Waves are an AGSL shader (Android 13+), so the GPU does the per-pixel work; older systems get
 * the plain gradient. Animation only runs while the launcher is resumed.
 */
@Composable
fun XmbBackground() {
    val stops = xmbStops(LauncherSettings.dark)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(stops)),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) XmbWaves() else XmbWavesLite()
    }
}

/** True while the launcher is resumed; the backdrop never animates behind another app. */
@Composable
private fun rememberResumed(): Boolean {
    var resumed by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed
}

private class Sparkle(val x: Float, val y: Float, val phase: Float, val speed: Float)

/**
 * Pre-Android 13 (the projector): the same waves without shaders. The soft bands are drawn in
 * software into a small 320x180 bitmap and stretched to full screen (the upscale blur is exactly
 * the soft look wanted, and tessellating thick strokes at 1080p every frame was the costly part);
 * crest lines and sparkles stay sharp at full resolution. ~20 fps, paused while music plays,
 * since the projector's CPU is also decoding the audio.
 */
@Composable
private fun XmbWavesLite() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var time by remember { mutableFloatStateOf(0f) }
    val bands = remember { android.graphics.Bitmap.createBitmap(BAND_W, BAND_H, android.graphics.Bitmap.Config.ARGB_8888) }
    val bandCanvas = remember { android.graphics.Canvas(bands) }
    val bandImage = remember { bands.asImageBitmap() }
    val bandPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = android.graphics.Color.WHITE
        }
    }
    val bandPath = remember { android.graphics.Path() }
    val crests = remember { List(3) { Path() } }
    val sparkles = remember {
        val rnd = Random(7)
        List(26) { Sparkle(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat() * 6.28f, 0.6f + rnd.nextFloat()) }
    }
    val animate = LauncherSettings.bgAnimation && rememberResumed()
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val start = withFrameNanos { it } - (time * 1e9f).toLong()
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (now - last >= LITE_FRAME_NANOS && !audio.isMusicActive) {
                    last = now
                    time = (now - start) / 1e9f
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawBehind {
                val t = time
                val w = size.width
                val h = size.height
                // Soft bands, low resolution.
                bands.eraseColor(android.graphics.Color.TRANSPARENT)
                for (i in 0 until 3) {
                    bandPath.reset()
                    for (s in 0..BAND_STEPS) {
                        val u = s / BAND_STEPS.toFloat()
                        val y = crest(u, t, i) * BAND_H
                        if (s == 0) bandPath.moveTo(0f, y) else bandPath.lineTo(u * BAND_W, y)
                    }
                    for ((width, alpha) in BAND_LAYERS) {
                        bandPaint.strokeWidth = BAND_H * width
                        bandPaint.alpha = (alpha * 255).toInt()
                        bandCanvas.drawPath(bandPath, bandPaint)
                    }
                }
                drawImage(
                    bandImage,
                    dstSize = IntSize(w.toInt(), h.toInt()),
                    filterQuality = FilterQuality.Low,
                )
                // Sharp crests and sparkles, full resolution.
                for (i in 0 until 3) {
                    val path = crests[i]
                    path.reset()
                    for (s in 0..BAND_STEPS) {
                        val u = s / BAND_STEPS.toFloat()
                        val y = crest(u, t, i) * h
                        if (s == 0) path.moveTo(0f, y) else path.lineTo(u * w, y)
                    }
                    drawPath(path, Color.White.copy(alpha = 0.5f - 0.12f * i), style = Stroke(width = 1.5.dp.toPx()))
                }
                for (p in sparkles) {
                    val twinkle = 0.5f + 0.5f * sin(t * 1.3f * p.speed + p.phase)
                    val x = (p.x + 0.01f * sin(t * 0.25f + p.phase)) * w
                    val y = (p.y + 0.01f * cos(t * 0.2f + p.phase)) * h
                    drawCircle(Color.White.copy(alpha = 0.55f * twinkle), radius = 2.dp.toPx(), center = Offset(x, y))
                }
            },
    )
}

/** Crest height of wave [i] at horizontal position [u] (0..1), as a fraction of the height. Same as the shader. */
private fun crest(u: Float, t: Float, i: Int): Float {
    val fi = i.toFloat()
    return 0.56f + 0.045f * fi +
        0.075f * sin(u * (2.4f + 0.7f * fi) + t * (0.16f + 0.05f * fi) + fi * 1.9f) +
        0.03f * sin(u * (6.5f + fi) - t * (0.22f + 0.04f * fi))
}

private const val BAND_W = 320
private const val BAND_H = 180
private const val BAND_STEPS = 40
// Just under 50 ms so a 60 Hz display ticks every third frame (20 fps) instead of every fourth.
private const val LITE_FRAME_NANOS = 45_000_000L
private val BAND_LAYERS = listOf(0.16f to 0.05f, 0.09f to 0.06f, 0.045f to 0.07f)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun XmbWaves() {
    val shader = remember { RuntimeShader(WAVES_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    var time by remember { mutableFloatStateOf(0f) }
    val animate = LauncherSettings.bgAnimation && rememberResumed()
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val start = withFrameNanos { it } - (time * 1e9f).toLong()
        var last = 0L
        while (true) {
            // Only the draw phase reads `time`, so a tick redraws without recomposing. The waves move
            // slowly, so ~30 fps is indistinguishable from 60 and halves the work.
            withFrameNanos { now ->
                if (now - last >= FRAME_NANOS) {
                    last = now
                    time = (now - start) / 1e9f
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Own layer: a tick re-renders only the backdrop, not the tiles drawn above it.
            .graphicsLayer()
            .drawBehind {
                shader.setFloatUniform("res", size.width, size.height)
                shader.setFloatUniform("t", time)
                drawRect(brush)
            },
    )
}

private const val FRAME_NANOS = 33_000_000L

// Waves: each is a soft translucent band plus a thin bright crest line, drifting at its own pace.
// Sparkles: one candidate dot per grid cell, most cells empty, each twinkling on its own phase.
private const val WAVES_AGSL = """
uniform float2 res;
uniform float t;

half4 main(float2 p) {
    float2 uv = p / res;
    float a = 0.0;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float crest = 0.56 + 0.045 * fi
            + 0.075 * sin(uv.x * (2.4 + 0.7 * fi) + t * (0.16 + 0.05 * fi) + fi * 1.9)
            + 0.03 * sin(uv.x * (6.5 + fi) - t * (0.22 + 0.04 * fi));
        float d = uv.y - crest;
        a += exp(-(d * d) / (0.0030 + 0.0015 * fi)) * 0.10;
        a += exp(-(d * d) / 0.000018) * (0.42 - 0.1 * fi);
    }
    float cell = 70.0;
    float2 g = floor(p / cell);
    float h = fract(sin(dot(g, float2(12.9898, 78.233))) * 43758.5453);
    float2 c = (g + 0.5 + 0.35 * float2(sin(t * 0.25 + h * 6.28), cos(t * 0.2 + h * 9.0))) * cell;
    float twinkle = 0.5 + 0.5 * sin(t * 1.3 + h * 40.0);
    a += exp(-length(p - c) * 0.45) * step(0.9, h) * twinkle * 0.8;
    a = clamp(a, 0.0, 0.85);
    return half4(a, a, a, a);
}
"""
