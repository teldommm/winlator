package com.winlator.cmod.ui.library

import android.content.Context
import android.os.Environment
import com.winlator.cmod.container.SaveProfiles
import com.winlator.cmod.container.Shortcut
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Date
import java.util.Locale

// What the game page shows as stat chips. Playtime and play count are written by
// XServerDisplayActivity into the "playtime_stats" preferences (keyed by the shortcut name); the
// last launch is the shortcut's own lastRunAt; the size is measured off the game's folder.
data class GameStats(
    val playtimeMillis: Long = 0L,
    val playCount: Int = 0,
    val lastPlayedMillis: Long = 0L,
    val sizeText: String? = null,
    val savesSizeText: String? = null
) {
    val hasAny: Boolean
        get() = playtimeMillis > 0L || playCount > 0 || lastPlayedMillis > 0L || sizeText != null || savesSizeText != null
}

object GameStatsLoader {
    // Names of the folders an exe usually sits in below the real game folder
    // (Game/Binaries/Win64/Game.exe): the size is taken from above them.
    private val BINARY_DIRS = setOf("win64", "win32", "x64", "x86", "bin", "bin32", "bin64", "binaries", "shipping", "retail")
    private const val MAX_ENTRIES = 250_000
    private const val MAX_SCAN_MS = 4_000L
    private const val CACHE_MS = 10 * 60 * 1000L

    private class CachedSize(val atMillis: Long, val text: String?)
    private val sizeCache = HashMap<String, CachedSize>()

    // Cheap part (a few preference reads and one small file): safe to call off the main thread.
    fun load(context: Context, shortcut: Shortcut): GameStats {
        val prefs = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE)
        val playtime = prefs.getLong(shortcut.name + "_playtime", 0L)
        val plays = prefs.getInt(shortcut.name + "_play_count", 0)
        // Read the file again: the Shortcut the screen holds was loaded before the last launch.
        val lastRun = runCatching {
            Shortcut(shortcut.container, shortcut.file).getExtra("lastRunAt", "0").toLongOrNull() ?: 0L
        }.getOrDefault(0L)
        return GameStats(playtime, plays, lastRun, null)
    }

    // Walks the game folder: call it off the main thread. Null when the folder can't be told
    // apart from a place that holds more than the game (Downloads, a drive root, ...).
    fun sizeText(context: Context, shortcut: Shortcut): String? {
        val dir = gameFolder(shortcut) ?: return null
        if (isBroadFolder(shortcut, dir)) return null
        val key = dir.path
        val now = System.currentTimeMillis()
        synchronized(sizeCache) {
            sizeCache[key]?.let { if (now - it.atMillis < CACHE_MS) return it.text }
        }
        val (bytes, truncated) = folderSize(dir)
        val text = if (bytes > 0L) formatBinarySize(bytes) + if (truncated) "+" else "" else null
        synchronized(sizeCache) { sizeCache[key] = CachedSize(now, text) }
        return text
    }

    // Size of the game's own save profile (Winlator/saves/<Game>). Null when the shortcut has none
    // (off or shared), or nothing has been saved yet. Call off the main thread.
    fun savesSizeText(context: Context, shortcut: Shortcut): String? {
        if (!SaveProfiles.isOwn(shortcut)) return null
        val dir = SaveProfiles.findProfileDir(shortcut) ?: return null
        val (bytes, truncated) = folderSize(dir)
        return if (bytes > 0L) formatBinarySize(bytes) + if (truncated) "+" else "" else null
    }

    private fun gameFolder(shortcut: Shortcut): File? {
        val exe = LibraryScreenController.resolveExeFile(shortcut) ?: return null
        var dir = exe.parentFile ?: return null
        var up = 0
        while (up < 4 && dir.name.lowercase(Locale.ROOT) in BINARY_DIRS) {
            dir = dir.parentFile ?: break
            up++
        }
        return dir
    }

    private fun isBroadFolder(shortcut: Shortcut, dir: File): Boolean {
        val broad = ArrayList<File>()
        broad.add(Environment.getExternalStorageDirectory())
        broad.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        broad.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
        broad.add(Environment.getRootDirectory())
        shortcut.container?.let { container ->
            container.rootDir?.let { broad.add(File(it, ".wine/drive_c")) }
            for (entry in container.drivesIterator()) {
                if (entry != null && entry.size >= 2 && entry[1] != null) broad.add(File(entry[1]))
            }
        }
        val target = runCatching { dir.canonicalPath }.getOrDefault(dir.path)
        return broad.any { runCatching { it.canonicalPath }.getOrDefault(it.path) == target }
    }

    private fun folderSize(root: File): Pair<Long, Boolean> {
        var total = 0L
        var entries = 0
        val deadline = System.currentTimeMillis() + MAX_SCAN_MS
        val pending = ArrayDeque<File>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (++entries > MAX_ENTRIES || System.currentTimeMillis() > deadline) return total to true
                if (child.isDirectory) {
                    // Don't follow links out of the folder.
                    val real = runCatching { child.canonicalPath }.getOrNull()
                    if (real != null && real == child.absolutePath) pending.add(child)
                } else {
                    total += child.length()
                }
            }
        }
        return total to false
    }
}

internal fun formatBinarySize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.size - 1) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

internal fun formatPlaytime(playtimeMillis: Long): String {
    val totalMinutes = (playtimeMillis / 60000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

internal fun formatLastPlayed(lastPlayedMillis: Long): String {
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val playedYear = Calendar.getInstance().apply { timeInMillis = lastPlayedMillis }.get(Calendar.YEAR)
    val pattern = if (playedYear == thisYear) "MMM d" else "MMM d, yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(lastPlayedMillis))
}
