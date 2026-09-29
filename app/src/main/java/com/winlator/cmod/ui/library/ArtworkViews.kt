package com.winlator.cmod.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

// A picture the user chose that is wide enough to be a background on its own.
private const val WIDE_ASPECT = 1.3f
private val wideCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

// Header-only read (no decode); cached per file version. Call off the main thread.
internal fun isWideImage(path: String?): Boolean {
    val key = LibraryImageCache.keyFor(path) ?: return false
    wideCache[key]?.let { return it }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val wide = bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth >= bounds.outHeight * WIDE_ASPECT
    wideCache[key] = wide
    return wide
}

// Full-bleed background, by where the picture came from:
//  - a banner (downloaded, or the user's own background): sharp, slowly breathing;
//  - the user's own cover: shown as picked. A wide one is the background itself (sharp); a portrait
//    one is not blown up into a strip, the backdrop falls back to the soft wash (the sharp cover is
//    shown by the card / detail page in front of it);
//  - a downloaded portrait cover or the offline placeholder: the soft colour wash made from it
//    (see SoftArt), drifting a little more.
// Draws nothing when the game has no artwork at all.
@Composable
internal fun ArtBackdrop(bannerPath: String?, coverPath: String?, coverIsUser: Boolean, modifier: Modifier = Modifier) {
    val wideKey = remember(coverPath) { LibraryImageCache.keyFor(coverPath) }
    val wide by produceState(
        coverIsUser && wideKey != null && wideCache[wideKey] == true,
        coverPath,
        coverIsUser
    ) {
        value = coverIsUser && withContext(Dispatchers.IO) { isWideImage(coverPath) }
    }
    val sharpPath = bannerPath ?: if (wide) coverPath else null

    if (sharpPath != null) {
        val key = remember(sharpPath) { LibraryImageCache.keyFor(sharpPath) }
        val sharp by produceState<Bitmap?>(LibraryImageCache.peek(key), key) {
            value = withContext(Dispatchers.IO) { LibraryImageCache.load(sharpPath) }
        }
        val art = sharp
        if (art != null) {
            Box(modifier.clipToBounds()) {
                Image(art.asImageBitmap(), null, Modifier.fillMaxSize().kenBurns(), contentScale = ContentScale.Crop)
            }
        }
    } else if (coverPath != null) {
        val key = remember(coverPath) { LibraryImageCache.keyFor(coverPath) }
        val soft by produceState<Bitmap?>(SoftArt.peek(key), key) {
            value = withContext(Dispatchers.IO) { SoftArt.load(coverPath) }
        }
        val art = soft
        if (art != null) {
            Box(modifier.clipToBounds()) {
                Image(
                    art.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize().kenBurns(amplitude = 0.12f, periodMs = 26_000, pan = 0.9f),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}
