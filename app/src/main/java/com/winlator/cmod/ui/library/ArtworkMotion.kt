package com.winlator.cmod.ui.library

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager
import com.winlator.cmod.steamgrid.ArtworkRepository
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Slow "breathing" motion for big artwork (game detail header, landscape pager, launch screen).
//
// Rules of the road:
//  - Only large artwork in focus moves, never the tiles of the grid/list.
//  - The screen decides whether motion is allowed by providing LocalArtworkMotion: the Library
//    tab provides "setting on && tab selected && no detail screen on top" (MainShell keeps the
//    other tabs composed at alpha 0, so without that they would keep animating unseen), the game
//    detail provides the setting alone.
//  - The setting lives in Settings > COVER ART ("Animated artwork", default on).

// True where artwork may animate. Default false so a screen that never provides it stays still.
internal val LocalArtworkMotion = staticCompositionLocalOf { false }

// The "Animated artwork" setting as observable state (the Library tab stays composed while the
// user changes it in Settings, so a one-time read would go stale).
@Composable
internal fun rememberArtworkMotionEnabled(): Boolean {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context.applicationContext) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(ArtworkRepository.PREF_ANIMATED_ARTWORK, true)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == ArtworkRepository.PREF_ANIMATED_ARTWORK) {
                enabled = changed.getBoolean(ArtworkRepository.PREF_ANIMATED_ARTWORK, true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

/**
 * Ken Burns: the layer slowly zooms in and out and drifts. One looping clock 0..1 feeds sinusoids,
 * so the motion is smooth at both ends and seamless at the loop point. It is read only inside the
 * graphicsLayer block (draw phase), so a frame never triggers recomposition.
 *
 * The drift is a fraction (pan) of the margin the current zoom leaves, so the picture never slides
 * far enough to show its edge. The caller must clip the parent (clipToBounds / a clipped Surface):
 * the scaled layer overflows its bounds.
 *
 * @param amplitude zoom added at the far end (0.07 = up to 107%).
 * @param periodMs  one full zoom in + out.
 * @param pan       0 = zoom only, 1 = drift by the whole free margin.
 * @param phase     0..1 shift of the loop (2 layers with different phases never move in step).
 * @param strength  0..1 fade of the whole effect; 0 leaves the modifier out (and stops the clock).
 */
@Composable
internal fun Modifier.kenBurns(
    amplitude: Float = 0.07f,
    periodMs: Int = 22_000,
    pan: Float = 0.8f,
    phase: Float = 0f,
    strength: Float = 1f
): Modifier {
    if (!LocalArtworkMotion.current || strength <= 0f) return this
    val transition = rememberInfiniteTransition(label = "artworkMotion")
    val clock = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "artworkMotionClock"
    )
    return this.graphicsLayer {
        val angle = (clock.value + phase) * 2f * PI.toFloat()
        val breathe = (1f - cos(angle)) / 2f
        val zoom = 1f + amplitude * strength * breathe
        val margin = (zoom - 1f) / 2f
        scaleX = zoom
        scaleY = zoom
        translationX = size.width * margin * pan * sin(angle)
        translationY = size.height * margin * pan * 0.75f * sin(angle * 2f)
    }
}

// A very soft version of a picture (colour wash) for backdrops. Made once per file by shrinking to
// 64px and box-blurring the small copy, then drawn stretched with bilinear filtering. Unlike
// Modifier.blur it works on every Android version (minSdk is 28, blur needs 12) and costs
// nothing per frame, which is what lets it move.
internal object SoftArt {
    private const val WIDTH = 64
    private val cache = LruCache<String, Bitmap>(48)

    fun peek(key: String?): Bitmap? = key?.let { cache.get(it) }

    // Off the main thread: decodes through the Library image cache, then softens.
    fun load(path: String?): Bitmap? {
        val key = LibraryImageCache.keyFor(path) ?: return null
        cache.get(key)?.let { return it }
        val full = LibraryImageCache.load(path) ?: return null
        val soft = from(full)
        cache.put(key, soft)
        return soft
    }

    // The source is only read, never recycled.
    fun from(source: Bitmap): Bitmap {
        val width = WIDTH
        val height = (width * source.height.toFloat() / source.width.toFloat()).roundToInt().coerceIn(16, 256)
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        if (small !== source) small.recycle()
        boxBlurArgb(pixels, width, height, radius = 3, passes = 2)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}

// Separable box blur over packed ARGB pixels, edges clamped. Pure (no Android types) so it can be
// tested on the JVM.
internal fun boxBlurArgb(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int) {
    val scratch = IntArray(pixels.size)
    repeat(passes) {
        blurLines(pixels, scratch, width, height, radius, horizontal = true)
        blurLines(scratch, pixels, width, height, radius, horizontal = false)
    }
}

private fun blurLines(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val window = 2 * radius + 1
    for (line in 0 until lines) {
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val p = src[pixelIndex(line, i.coerceIn(0, length - 1), width, horizontal)]
            a += p ushr 24
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        for (i in 0 until length) {
            dst[pixelIndex(line, i, width, horizontal)] =
                ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val incoming = src[pixelIndex(line, (i + radius + 1).coerceAtMost(length - 1), width, horizontal)]
            val outgoing = src[pixelIndex(line, (i - radius).coerceAtLeast(0), width, horizontal)]
            a += (incoming ushr 24) - (outgoing ushr 24)
            r += ((incoming shr 16) and 0xFF) - ((outgoing shr 16) and 0xFF)
            g += ((incoming shr 8) and 0xFF) - ((outgoing shr 8) and 0xFF)
            b += (incoming and 0xFF) - (outgoing and 0xFF)
        }
    }
}

private fun pixelIndex(line: Int, i: Int, width: Int, horizontal: Boolean): Int =
    if (horizontal) line * width + i else i * width + line
