package com.winlator.cmod.ui.library

import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.ui.ThemedAlertHost
import com.winlator.cmod.ui.shortcut.ShortcutSettingsComposeDialog
import com.winlator.cmod.ui.theme.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Game detail as a MainShell detail entry (replaces GameDetailFragment). Same actions; the
// runtime subtitle (which syncs ContentsManager — disk work) is now resolved off the main thread
// instead of blocking the screen's creation.
@Composable
fun GameDetailRoute(shortcutPath: String, onClose: () -> Unit, onLibraryChanged: () -> Unit) {
    val activity = LocalContext.current.findActivity() as AppCompatActivity
    val shortcut = remember(shortcutPath) {
        ContainerManager(activity).loadShortcuts().firstOrNull { it?.file?.path == shortcutPath }
    }
    if (shortcut == null) {
        // Shortcut vanished (deleted elsewhere) — nothing to show.
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val baseName = remember(shortcut) { FileUtils.getBasename(shortcut.file.path) }
    val artworkPath = remember(shortcut) {
        val banner = File(Environment.getExternalStorageDirectory(), "Winlator/banners/$baseName.png")
        val cover = File(Environment.getExternalStorageDirectory(), "Winlator/covers/$baseName.png")
        when {
            banner.exists() -> banner.path
            cover.exists() -> cover.path
            else -> null
        }
    }
    val subtitle by produceState(shortcut.container.wineVersion + "  •  Vulkan", shortcut) {
        value = withContext(Dispatchers.IO) { environmentSubtitle(activity, shortcut) }
    }

    val callbacks = remember(shortcut) {
        object : GameDetailCallbacks {
            override fun onBack() = onClose()

            override fun onPlay() {
                val intent = Intent(activity, XServerDisplayActivity::class.java)
                intent.putExtra("container_id", shortcut.container.id)
                intent.putExtra("shortcut_path", shortcut.file.path)
                intent.putExtra("shortcut_name", shortcut.name)
                intent.putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"))
                activity.startActivity(intent)
            }

            override fun onConfigure() {
                // Edits made here (rename, copy to container) must show up in the Library tab.
                ShortcutSettingsComposeDialog.show(activity, shortcut) { onLibraryChanged() }
            }

            override fun onArguments() {
                activity.startActivity(
                    Intent(activity, XServerDisplayActivity::class.java).putExtra("container_id", shortcut.container.id)
                )
            }

            override fun onGameFolder() {
                Toast.makeText(activity, shortcut.container.desktopDir.path, Toast.LENGTH_LONG).show()
            }

            override fun onFavorite(favorite: Boolean) {
                shortcut.putExtra("favorite", if (favorite) "1" else "0")
                shortcut.saveData()
                onLibraryChanged()
            }

            override fun onRemove() {
                ThemedAlertHost.confirm(
                    activity,
                    "Remove shortcut?",
                    "Do you want to remove this shortcut?",
                    "Remove",
                    {
                        if (shortcut.file.delete()) {
                            onLibraryChanged()
                            onClose()
                        }
                    },
                    true
                )
            }
        }
    }

    GameDetailScreen(
        shortcut.name,
        subtitle,
        artworkPath,
        shortcut.icon,
        "1" == shortcut.getExtra("favorite", "0"),
        callbacks
    )
}

private fun environmentSubtitle(activity: AppCompatActivity, shortcut: Shortcut): String {
    var runtime = shortcut.container.wineVersion
    try {
        val contents = ContentsManager(activity)
        contents.syncContents()
        val info = WineInfo.fromIdentifier(activity, contents, runtime)
        var version = info.fullVersion()
        if (version.endsWith(".0")) version = version.substring(0, version.length - 2)
        runtime = (if ("proton".equals(info.type, ignoreCase = true)) "Proton " else "Wine ") + version + " " + info.arch
    } catch (ignored: Exception) {
    }
    return "$runtime  •  Vulkan"
}
