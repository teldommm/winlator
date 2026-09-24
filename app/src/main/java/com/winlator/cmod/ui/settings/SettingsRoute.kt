package com.winlator.cmod.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.preference.PreferenceManager
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.box64.Box64EditPresetDialog
import com.winlator.cmod.box64.Box64Preset
import com.winlator.cmod.box64.Box64PresetManager
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.AppDefaults
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.LosslessDll
import com.winlator.cmod.core.PreloaderDialog
import com.winlator.cmod.fexcore.FEXCoreEditPresetDialog
import com.winlator.cmod.fexcore.FEXCorePreset
import com.winlator.cmod.fexcore.FEXCorePresetManager
import com.winlator.cmod.midi.MidiManager
import com.winlator.cmod.ui.ThemedAlertHost
import com.winlator.cmod.ui.theme.findActivity
import com.winlator.cmod.xenvironment.ImageFsInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.FileNotFoundException
import kotlin.math.roundToInt

// Settings as a plain composable tab of MainShell (replaces SettingsFragment). Behaviour is the
// same; the differences are structural:
//  - File/folder pickers use two Activity Result launchers (document, document tree) with a
//    pending action, instead of six request codes funnelled through onActivityResult.
//  - The model is built off the main thread when the screen (re)appears; user-initiated changes
//    still rebuild synchronously so switches/sliders respond in the same frame.
//  - Refresh on resume matches the fragment's onResume (back from Components/Containers
//    activities, from a game, …); MainShell's re-show also refreshes.
//  - The static constants/helpers that other code used from SettingsFragment moved to
//    core/AppDefaults.java.

@Composable
fun SettingsRoute(shownSerial: Int) {
    val activity = LocalContext.current.findActivity() as AppCompatActivity
    val scope = rememberCoroutineScope()
    val state = remember { SettingsScreenState(activity, scope) }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        state.onDocumentPicked(uri)
    }
    val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        state.onTreePicked(uri)
    }
    SideEffect {
        state.launchDocumentPicker = { openDocument.launch(arrayOf("*/*")) }
        state.launchTreePicker = { openTree.launch(null) }
    }

    LifecycleResumeEffect(state) {
        state.reload()
        onPauseOrDispose { }
    }
    LaunchedEffect(shownSerial) {
        if (shownSerial > 0) state.reload()
    }

    val model = state.model
    if (model != null) {
        SettingsScreen(model, state)
    } else {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

internal class SettingsScreenState(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope
) : SettingsCallbacks {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
    private var reloadJob: Job? = null

    var model by mutableStateOf<SettingsModel?>(null)
        private set

    // Launchers belong to the composition; SettingsRoute sets these on every composition.
    var launchDocumentPicker: (() -> Unit)? = null
    var launchTreePicker: (() -> Unit)? = null
    private var pendingDocumentAction: ((Uri) -> Unit)? = null
    private var pendingTreePreferenceKey: String? = null

    // ---------- Model ----------

    fun reload() {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            model = withContext(Dispatchers.IO) { buildModel() }
        }
    }

    private fun rebuild() {
        reloadJob?.cancel()
        model = buildModel()
    }

    private fun buildModel(): SettingsModel {
        val box64Choices = Box64PresetManager.getPresets("box64", activity).map { SettingChoice(it.id, it.name) }
        val fexChoices = FEXCorePresetManager.getPresets(activity).map { SettingChoice(it.id, it.name) }

        val soundFontChoices = ArrayList<SettingChoice>()
        soundFontChoices.add(SettingChoice(MidiManager.DEFAULT_SF2_FILE, MidiManager.DEFAULT_SF2_FILE))
        MidiManager.getSoundFontDir(activity).listFiles()
            ?.filter { it.isFile }
            ?.sortedWith { left, right -> left.name.compareTo(right.name, ignoreCase = true) }
            ?.forEach { soundFontChoices.add(SettingChoice(it.name, it.name)) }

        val wineDebugOptions = ArrayList<String>()
        try {
            val channels = JSONArray(FileUtils.readString(activity, "wine_debug_channels.json"))
            for (i in 0 until channels.length()) {
                val channel = channels.optString(i, "").trim()
                if (channel.isNotEmpty()) wineDebugOptions.add(channel)
            }
        } catch (ignored: JSONException) {
        }
        if (wineDebugOptions.isEmpty()) {
            wineDebugOptions.addAll(AppDefaults.DEFAULT_WINE_DEBUG_CHANNELS.split(","))
        }

        return SettingsModel(
            box64Choices,
            preferences.getString("box64_preset", Box64Preset.COMPATIBILITY) ?: Box64Preset.COMPATIBILITY,
            fexChoices,
            preferences.getString("fexcore_preset", FEXCorePreset.COMPATIBILITY) ?: FEXCorePreset.COMPATIBILITY,
            soundFontChoices,
            LosslessDll.isGlobalDllAvailable(activity),
            resolveStoredPath("winlator_path_uri", AppDefaults.DEFAULT_WINLATOR_PATH),
            resolveStoredPath("shortcuts_export_path_uri", AppDefaults.DEFAULT_SHORTCUT_EXPORT_PATH),
            (preferences.getFloat("cursor_speed", 1.0f) * 100.0f).roundToInt(),
            preferences.getBoolean("cursor_lock", true),
            preferences.getBoolean("xinput_toggle", false),
            preferences.getBoolean("use_dri3", true),
            preferences.getBoolean("high_refresh_rate_mode", false),
            preferences.getBoolean("enable_file_provider", true),
            preferences.getBoolean("open_with_android_browser", false),
            preferences.getBoolean("share_android_clipboard", false),
            preferences.getBoolean("pause_resume_wine", true),
            preferences.getBoolean("remove_loading_bar_when_booting_games", false),
            preferences.getBoolean("enable_wine_debug", false),
            preferences.getString("wine_debug_channels", AppDefaults.DEFAULT_WINE_DEBUG_CHANNELS)
                ?: AppDefaults.DEFAULT_WINE_DEBUG_CHANNELS,
            preferences.getBoolean("enable_box64_logs", false),
            preferences.getBoolean("enable_custom_api_key", false),
            preferences.getString("custom_api_key", "") ?: "",
            preferences.getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES)
                ?: ContentsManager.REMOTE_PROFILES,
            wineDebugOptions
        )
    }

    private fun resolveStoredPath(key: String, fallback: String): String {
        val stored = preferences.getString(key, null) ?: return fallback
        return FileUtils.getFilePathFromUri(activity, Uri.parse(stored)) ?: stored
    }

    // ---------- Pickers ----------

    private fun pickDocument(action: (Uri) -> Unit) {
        pendingDocumentAction = action
        launchDocumentPicker?.invoke()
    }

    private fun pickTreeFor(preferenceKey: String) {
        pendingTreePreferenceKey = preferenceKey
        launchTreePicker?.invoke()
    }

    fun onDocumentPicked(uri: Uri?) {
        val action = pendingDocumentAction
        pendingDocumentAction = null
        if (uri != null && action != null) action(uri)
    }

    fun onTreePicked(uri: Uri?) {
        val key = pendingTreePreferenceKey
        pendingTreePreferenceKey = null
        if (uri == null || key == null) return
        preferences.edit().putString(key, uri.toString()).apply()
        try {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Toast.makeText(activity, "Unable to take persistable permissions: " + e.message, Toast.LENGTH_SHORT).show()
        }
        rebuild()
    }

    // ---------- Detail screens (MainShell detail stack) ----------

    override fun onOpenComponents() {
        (activity as? MainActivity)?.openComponentManager()
    }

    override fun onOpenContainers() {
        (activity as? MainActivity)?.openContainersSettings()
    }

    // ---------- SettingsCallbacks ----------

    override fun onBox64PresetSelected(id: String) {
        preferences.edit().putString("box64_preset", id).apply()
        rebuild()
    }

    override fun onFexPresetSelected(id: String) {
        preferences.edit().putString("fexcore_preset", id).apply()
        rebuild()
    }

    override fun onInstallSoundFont() {
        pickDocument { uri ->
            try {
                val dialog = PreloaderDialog(activity)
                dialog.showOnUiThread(R.string.installing_content)
                MidiManager.installSF2File(activity, uri, object : MidiManager.OnSoundFontInstalledCallback {
                    override fun onSuccess() {
                        dialog.closeOnUiThread()
                        activity.runOnUiThread {
                            ThemedAlertHost.info(activity, "SoundFont Installed", "Soundfont installed successfully!")
                            rebuild()
                        }
                    }

                    override fun onFailed(reason: Int) {
                        dialog.closeOnUiThread()
                        val message = when (reason) {
                            MidiManager.ERROR_BADFORMAT -> "Bad SoundFont format."
                            MidiManager.ERROR_EXIST -> "Soundfont you want to install already exists."
                            else -> "Soundfont installation failed."
                        }
                        activity.runOnUiThread {
                            ThemedAlertHost.info(activity, "SoundFont Install Failed", message)
                        }
                    }
                })
            } catch (e: Exception) {
                Toast.makeText(activity, "Unable to install soundfont", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRemoveSoundFont(name: String) {
        if (MidiManager.DEFAULT_SF2_FILE == name) {
            Toast.makeText(activity, R.string.cannot_remove_default_sound_font, Toast.LENGTH_SHORT).show()
            return
        }
        ThemedAlertHost.confirm(
            activity,
            "Remove SoundFont?",
            "Do you want to remove this soundfont?",
            "Remove",
            {
                if (MidiManager.removeSF2File(activity, name)) {
                    Toast.makeText(activity, R.string.sound_font_removed_success, Toast.LENGTH_SHORT).show()
                    rebuild()
                } else {
                    Toast.makeText(activity, R.string.sound_font_removed_failed, Toast.LENGTH_SHORT).show()
                }
            },
            true
        )
    }

    override fun onImportLosslessDll() {
        pickDocument { uri ->
            if (LosslessDll.importGlobalLosslessDll(activity, uri)) {
                Toast.makeText(activity, "Lossless.dll imported", Toast.LENGTH_SHORT).show()
                rebuild()
            } else {
                Toast.makeText(activity, "Unable to import Lossless.dll", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onChooseWinlatorPath() = pickTreeFor("winlator_path_uri")

    override fun onChooseShortcutPath() = pickTreeFor("shortcuts_export_path_uri")

    override fun onBooleanChanged(key: String, value: Boolean) {
        val editor = preferences.edit().putBoolean(key, value)
        if ("enable_custom_api_key" == key && !value) editor.remove("custom_api_key")
        editor.apply()
        if ("enable_file_provider" == key) {
            Toast.makeText(activity, "This option will take effect at the next startup.", Toast.LENGTH_SHORT).show()
        }
        rebuild()
    }

    override fun onCursorSpeedChanged(percent: Int) {
        val clamped = percent.coerceIn(10, 200)
        preferences.edit().putFloat("cursor_speed", clamped / 100.0f).apply()
        rebuild()
    }

    override fun onCustomApiKeyChanged(value: String) {
        preferences.edit().putString("custom_api_key", value.trim()).apply()
        rebuild()
    }

    override fun onContentsUrlChanged(value: String) {
        val normalized = value.trim().ifEmpty { ContentsManager.REMOTE_PROFILES }
        preferences.edit().putString("downloadable_contents_url", normalized).apply()
        rebuild()
    }

    override fun onWineDebugChannelsChanged(value: String) {
        val normalized = value.trim().replace(" ", "").ifEmpty { AppDefaults.DEFAULT_WINE_DEBUG_CHANNELS }
        preferences.edit().putString("wine_debug_channels", normalized).apply()
        rebuild()
    }

    override fun onReinstallImageFs() {
        val mainActivity = activity as? MainActivity ?: return
        ImageFsInstaller.installFromAssets(mainActivity, null)
    }

    override fun onPresetAction(kind: String, id: String, action: String) {
        val box64 = "box64" == kind
        when (action) {
            "add", "edit" -> {
                val presetId = if (action == "edit") id else null
                if (box64) {
                    val dialog = Box64EditPresetDialog(activity, "box64", presetId)
                    dialog.setOnConfirmCallback { rebuild() }
                    dialog.show()
                } else {
                    val dialog = FEXCoreEditPresetDialog(activity, presetId)
                    dialog.setOnConfirmCallback { rebuild() }
                    dialog.show()
                }
            }
            "duplicate" -> ThemedAlertHost.confirm(
                activity,
                "Clone Preset",
                "Do you want to duplicate this preset?",
                "Clone",
                {
                    if (box64) Box64PresetManager.duplicatePreset("box64", activity, id)
                    else FEXCorePresetManager.duplicatePreset(activity, id)
                    rebuild()
                }
            )
            "remove" -> {
                val custom = if (box64) id.startsWith(Box64Preset.CUSTOM) else id.startsWith(FEXCorePreset.CUSTOM)
                if (!custom) {
                    Toast.makeText(activity, "You cannot remove this preset", Toast.LENGTH_SHORT).show()
                    return
                }
                ThemedAlertHost.confirm(
                    activity,
                    "Remove Preset",
                    "Do you want to remove this preset?",
                    "Remove",
                    {
                        if (box64) {
                            Box64PresetManager.removePreset("box64", activity, id)
                            if (id == preferences.getString("box64_preset", "")) {
                                preferences.edit().putString("box64_preset", Box64Preset.COMPATIBILITY).apply()
                            }
                        } else {
                            FEXCorePresetManager.removePreset(activity, id)
                            if (id == preferences.getString("fexcore_preset", "")) {
                                preferences.edit().putString("fexcore_preset", FEXCorePreset.COMPATIBILITY).apply()
                            }
                        }
                        rebuild()
                        Toast.makeText(activity, "Preset removed", Toast.LENGTH_SHORT).show()
                    },
                    true
                )
            }
            "import" -> pickDocument { uri ->
                try {
                    val stream = activity.contentResolver.openInputStream(uri)
                    if (box64) Box64PresetManager.importPreset("box64", activity, stream)
                    else FEXCorePresetManager.importPreset(activity, stream)
                    rebuild()
                } catch (ignored: FileNotFoundException) {
                }
            }
            "export" -> {
                val exportable = if (box64) id.startsWith(Box64Preset.CUSTOM) else id.startsWith(FEXCorePreset.CUSTOM)
                if (!exportable) {
                    Toast.makeText(activity, "Cannot export this preset", Toast.LENGTH_SHORT).show()
                    return
                }
                if (box64) Box64PresetManager.exportPreset("box64", activity, id)
                else FEXCorePresetManager.exportPreset(activity, id)
            }
        }
    }
}
