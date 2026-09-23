package com.winlator.cmod.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import com.winlator.cmod.MainActivity
import com.winlator.cmod.ui.applyAppFullscreen
import java.util.concurrent.atomic.AtomicInteger

@Immutable
data class LibraryItem(
    val id: String,
    val shortcutPath: String,
    val name: String,
    val containerName: String,
    val coverPath: String?,
    val bannerPath: String?,
    val iconPath: String?,
    val fallbackIcon: Bitmap?,
    val favorite: Boolean,
    val lastRunAt: Long
)

@Stable
interface LibraryCallbacks {
    fun onOpen(shortcutPath: String)
    fun onRun(shortcutPath: String)
    fun onGridViewChanged(gridView: Boolean)
    fun onAction(shortcutPath: String, action: String)
    fun onArtworkNeeded(shortcutPath: String, kind: String)
    fun onSearchQueryChanged(query: String)
    fun onOpenFileManager()
}

class LibraryComposeController internal constructor(
    private val context: Context,
    internal val items: MutableState<List<LibraryItem>>,
    internal val grid: MutableState<Boolean>,
    internal val query: MutableState<String>,
    internal val selectedShortcutPath: MutableState<String?>
) {
    private val statePreferences = context.getSharedPreferences("library_compose_state", Context.MODE_PRIVATE)
    private val metadataGeneration = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setItems(value: List<LibraryItem>) {
        val snapshot = value.toList()
        items.value = snapshot
        val generation = metadataGeneration.incrementAndGet()
        Thread {
            val resolved = resolveLibraryEnvironmentLabels(context, snapshot)
            mainHandler.post {
                if (metadataGeneration.get() == generation) items.value = resolved
            }
        }.start()
    }

    fun setGridView(value: Boolean) { grid.value = value }
    fun setSearchQuery(value: String?) { query.value = value?.trim().orEmpty() }

    fun setSelectedShortcutPath(value: String?) {
        selectedShortcutPath.value = value
        statePreferences.edit().apply {
            if (value.isNullOrEmpty()) remove("selected_shortcut_path")
            else putString("selected_shortcut_path", value)
        }.apply()
    }
}

object LibraryComposeHost {
    const val ACTION_SETTINGS = "settings"
    const val ACTION_ICON = "icon"
    const val ACTION_CLONE = "clone"
    const val ACTION_HOME = "home"
    const val ACTION_EXPORT = "export"
    const val ACTION_REMOVE = "remove"
    const val ACTION_FAVORITE = "favorite"

    // Library UI state. The screen itself is LibraryContent(), called from LibraryRoute inside
    // MainShell — there is no ComposeView for the Library anymore.
    @JvmStatic
    fun createController(context: Context, initialGridView: Boolean): LibraryComposeController {
        applyAppFullscreen(context as? MainActivity)
        return LibraryComposeController(
            context.applicationContext,
            mutableStateOf(emptyList()),
            mutableStateOf(initialGridView),
            mutableStateOf(""),
            mutableStateOf(
                context.getSharedPreferences("library_compose_state", Context.MODE_PRIVATE)
                    .getString("selected_shortcut_path", null)
            )
        )
    }
}

@Composable
internal fun LibraryContent(controller: LibraryComposeController, callbacks: LibraryCallbacks) {
    LibraryRootWithoutEmptyDescription(
        controller.items.value,
        controller.grid.value,
        controller.query.value,
        controller.selectedShortcutPath,
        callbacks
    )
}

internal enum class LibraryFilter { All, Favorites, Recent }
