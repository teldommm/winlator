package com.winlator.cmod.ui.library

import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.winlator.cmod.ui.ThemedAlertHost
import com.winlator.cmod.ui.toast.WinToast
import com.winlator.cmod.ui.shortcut.ShortcutSettingsComposeDialog
import com.winlator.cmod.ui.theme.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.winlator.cmod.steamgrid.ArtworkRepository

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
    // Both are passed on: the screen picks the banner, or blurs/frames the cover when that is all
    // there is (a portrait cover stretched over the header looked wrong).
    // The user's own background (banners/NAME.user.png) wins over the downloaded banner.
    val bannerPath = remember(shortcut) {
        val banners = File(Environment.getExternalStorageDirectory(), "Winlator/banners")
        File(banners, "$baseName.user.png").takeIf { it.isFile && it.length() > 0 }?.path
            ?: File(banners, "$baseName.png").takeIf { it.exists() }?.path
    }
    // The user's own cover (covers/NAME.user.png), else the downloaded one, else the offline
    // placeholder built from the icon (covers/NAME.gen.png).
    val userCoverPath = remember(shortcut) {
        File(File(Environment.getExternalStorageDirectory(), "Winlator/covers"), "$baseName.user.png")
            .takeIf { it.isFile && it.length() > 0 }?.path
    }
    val coverPath = remember(shortcut) {
        val covers = File(Environment.getExternalStorageDirectory(), "Winlator/covers")
        userCoverPath
            ?: File(covers, "$baseName.png").takeIf { it.exists() }?.path
            ?: File(covers, "$baseName.gen.png").takeIf { it.isFile && it.length() > 0 }?.path
    }
    // Same label as the Library tile ("Proton 9.0 arm64ec"), which the Library has
    // normally already resolved — so the subtitle is final from the first frame. Refreshed in the
    // background in case the container's runtime changed since.
    val subtitle by produceState(
        LibraryEnvironmentLabels.cached(shortcut.file.path) ?: shortcut.container.name,
        shortcut
    ) {
        value = withContext(Dispatchers.IO) { LibraryEnvironmentLabels.resolveOne(activity, shortcut) }
    }

    val callbacks = remember(shortcut) {
        object : GameDetailCallbacks {
            override fun onBack() = onClose()

            override fun onPlay() {
                val intent = Intent(activity, XServerDisplayActivity::class.java)
                intent.putExtra("container_id", shortcut.container.id)
                intent.putExtra("shortcut_path", shortcut.file.path)
                intent.putExtra("shortcut_name", shortcut.name)
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

            override fun onSaves() {
                GameSavesComposeDialog.show(activity, shortcut)
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
                            ArtworkRepository.deleteArtworkIfUnused(activity, shortcut.file)
                            onLibraryChanged()
                            onClose()
                            WinToast.show(activity, "Shortcut removed", Toast.LENGTH_SHORT)
                        } else {
                            WinToast.show(activity, "Couldn't remove shortcut", Toast.LENGTH_LONG)
                        }
                    },
                    true
                )
            }
        }
    }

    val motionEnabled = rememberArtworkMotionEnabled()
    CompositionLocalProvider(LocalArtworkMotion provides motionEnabled) {
        GameDetailScreen(
            shortcut.name,
            subtitle,
            bannerPath,
            coverPath,
            userCoverPath != null,
            shortcut.icon,
            "1" == shortcut.getExtra("favorite", "0"),
            callbacks
        )
    }
}
