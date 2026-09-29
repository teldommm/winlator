package com.winlator.cmod.ui.library

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Shared pieces for showing game artwork where a cover or banner may be missing.
//
// Why: the tiles used to fall back to `cover ?: banner ?: icon` with ContentScale.Crop, so a
// 256px exe icon was blown up to fill a whole portrait tile, and a portrait cover was stretched
// across a landscape screen (a ~4x zoom of one horizontal strip). Now a missing cover shows the
// icon at its own size on a plain tile, and a cover-only backdrop is blurred instead of enlarged.

// A portrait cover used as a wide backdrop: blurred where the platform can (Android 12+), otherwise
// just dimmed so the enlarged pixels don't read as a sharp, stretched picture (minSdk is 28).
internal fun Modifier.softenBackdrop(): Modifier =
    if (Build.VERSION.SDK_INT >= 31) this.blur(28.dp, BlurredEdgeTreatment.Unbounded).alpha(.75f)
    else this.alpha(.30f)

// The game icon at a fixed size, centred on a plain tile. For "there is no cover" states.
@Composable
internal fun IconTile(iconPath: String?, fallback: Bitmap?, modifier: Modifier = Modifier, iconSize: Dp = 72.dp) {
    val key = remember(iconPath) { LibraryImageCache.keyFor(iconPath) }
    val bitmap by produceState(LibraryImageCache.peek(key) ?: fallback, key) {
        value = withContext(Dispatchers.IO) { LibraryImageCache.load(iconPath) } ?: fallback
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val icon = bitmap
        if (icon != null) {
            Image(
                icon.asImageBitmap(),
                null,
                Modifier.size(iconSize).clip(WinZShapes.Small),
                contentScale = ContentScale.Fit
            )
        }
    }
}

// Full-bleed background from the banner; with only a portrait cover it is blurred (and softened),
// so a stretched cover reads as a colour wash instead of a pixelated slice. Draws nothing when
// the game has no artwork at all.
@Composable
internal fun ArtBackdrop(bannerPath: String?, coverPath: String?, modifier: Modifier = Modifier) {
    val path = bannerPath ?: coverPath
    val key = remember(path) { LibraryImageCache.keyFor(path) }
    val bitmap by produceState<Bitmap?>(LibraryImageCache.peek(key), key) {
        value = withContext(Dispatchers.IO) { LibraryImageCache.load(path) }
    }
    val art = bitmap
    if (art != null) {
        Box(modifier.clipToBounds()) {
            val fill = Modifier.fillMaxSize()
            Image(
                art.asImageBitmap(),
                null,
                if (bannerPath == null) fill.softenBackdrop() else fill,
                contentScale = ContentScale.Crop
            )
        }
    }
}
