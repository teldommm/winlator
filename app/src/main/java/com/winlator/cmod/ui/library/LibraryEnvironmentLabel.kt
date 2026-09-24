package com.winlator.cmod.ui.library

import android.content.Context
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.WineInfo

private fun environmentText(
    context: Context,
    contents: ContentsManager,
    shortcutPath: String,
    fallback: String,
    shortcuts: Map<String, Shortcut>
): String = runCatching {
    val shortcut = shortcuts[shortcutPath] ?: return@runCatching fallback
    val info = WineInfo.fromIdentifier(context, contents, shortcut.container.getWineVersion())
    var version = info.fullVersion()
    if (version.endsWith(".0")) version = version.dropLast(2)
    val runtime = (if (info.type.equals("proton", true)) "Proton " else "Wine ") + version + " " + info.getArch()
    "$runtime · Vulkan"
}.getOrDefault(fallback)

// Runtime labels ("Proton 9.0 arm64ec · Vulkan") for Library tiles. Disk work (ContentsManager
// sync + WineInfo per container) — call off the main thread; LibraryScreenController does it on
// its loader before publishing items. Falls back to the container name.
object LibraryEnvironmentLabels {
    @JvmStatic
    fun resolve(context: Context, shortcuts: List<Shortcut>): Map<String, String> = runCatching {
        val contents = ContentsManager(context).apply { syncContents() }
        val byPath = shortcuts.associateBy { it.file.path }
        shortcuts.associate { shortcut ->
            val path = shortcut.file.path
            path to environmentText(context, contents, path, shortcut.container?.name.orEmpty(), byPath)
        }
    }.getOrDefault(emptyMap())
}
