package com.winlator.cmod.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

// Decoded Library artwork (covers, banners, game icons), keyed by path + last-modified time so a
// replaced file (new custom icon, re-downloaded cover) is never served stale.
//
// Why: tiles decoded their image asynchronously and showed the shortcut's original exe icon
// until the decode finished — so a game with a custom icon flashed the original one for a few
// frames on every app start and every time it scrolled/filtered back into view (All ↔
// Favorites). With the cache, a tile that has been decoded before starts with the right bitmap,
// and LibraryScreenController pre-decodes the (small) game icons on its loader thread before
// publishing, so even the first frame is correct.
object LibraryImageCache {
    private val cache = object : LruCache<String, Bitmap>(
        // KB; 1/8 of the app heap.
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    // Stat only (cheap); null if the file doesn't exist.
    @JvmStatic
    fun keyFor(path: String?): String? {
        if (path == null) return null
        val file = File(path)
        if (!file.isFile) return null
        return path + "|" + file.lastModified()
    }

    @JvmStatic
    fun peek(key: String?): Bitmap? = key?.let { cache.get(it) }

    // Decodes (off the main thread!) and caches; returns the cached bitmap when present.
    @JvmStatic
    fun load(path: String?): Bitmap? {
        val key = keyFor(path) ?: return null
        cache.get(key)?.let { return it }
        val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }
}
