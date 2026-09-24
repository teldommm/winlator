package com.winlator.cmod.ui.inputcontrols

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
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.preference.PreferenceManager
import com.winlator.cmod.ControlsEditorActivity
import com.winlator.cmod.ExternalControllerBindingsActivity
import com.winlator.cmod.R
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.HttpUtils
import com.winlator.cmod.inputcontrols.ControlsProfile
import com.winlator.cmod.inputcontrols.ExternalController
import com.winlator.cmod.inputcontrols.InputControlsManager
import com.winlator.cmod.ui.ThemedAlertHost
import com.winlator.cmod.ui.ThemedLoadingOverlayHost
import com.winlator.cmod.ui.theme.findActivity
import com.winlator.cmod.widget.InputControlsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

// Input Controls as a plain composable tab of MainShell — the first screen moved off Fragments.
// Everything InputControlsFragment did lives in InputControlsScreenState below, with the same
// dialogs, toasts, activities and download flow. What changed besides the host:
//
//  - Profiles are (re)loaded off the main thread into a fresh InputControlsManager that is then
//    swapped in on the main thread. The fragment built a new manager (and re-read every
//    profile JSON) synchronously on every tab switch, right when the transition started.
//  - The file picker uses the Activity Result API instead of startActivityFromFragment +
//    onActivityResult with MainActivity.OPEN_FILE_REQUEST_CODE.
//  - Refresh points are explicit: whenever the activity (re)starts — e.g. back from
//    ControlsEditorActivity / ExternalControllerBindingsActivity, same as the fragment's
//    onStart — and whenever MainShell re-shows the tab (shownSerial).

private const val INPUT_CONTROLS_URL =
    "https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/%s"

@Composable
fun InputControlsRoute(initialProfileId: Int, shownSerial: Int) {
    val activity = LocalContext.current.findActivity() as AppCompatActivity
    val scope = rememberCoroutineScope()
    val state = remember { InputControlsScreenState(activity, initialProfileId, scope) }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.importProfileFrom(uri)
    }
    SideEffect { state.launchProfilePicker = { openDocument.launch(arrayOf("*/*")) } }

    LifecycleStartEffect(state) {
        state.reload()
        onStopOrDispose { }
    }
    LaunchedEffect(shownSerial) {
        if (shownSerial > 0) state.reload()
    }

    val model = state.model
    if (model != null) {
        InputControlsScreen(model, state)
    } else {
        // First load is in flight (a few ms of disk IO off the main thread).
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}

internal class InputControlsScreenState(
    private val activity: AppCompatActivity,
    initialProfileId: Int,
    private val scope: CoroutineScope
) : InputControlsCallbacks {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
    private var manager: InputControlsManager? = null
    private var currentProfile: ControlsProfile? = null
    private var visibleControllers: List<ExternalController> = emptyList()

    // Profile to select on the first load (from MainActivity's edit_input_controls intent).
    private var pendingProfileId = initialProfileId
    private var reloadJob: Job? = null

    var model by mutableStateOf<InputControlsModel?>(null)
        private set

    // Set by InputControlsRoute each composition (the launcher belongs to the composition).
    var launchProfilePicker: (() -> Unit)? = null

    // ---------- Loading ----------

    fun reload() {
        val keepProfileId = currentProfile?.id ?: pendingProfileId
        reloadJob?.cancel()
        reloadJob = scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                InputControlsManager(activity.applicationContext).also { it.getProfiles() }
            }
            manager = fresh
            currentProfile = if (keepProfileId > 0) fresh.getProfile(keepProfileId) else null
            pendingProfileId = 0
            rebuild()
        }
    }

    private fun rebuild() {
        val manager = manager ?: return
        val profileItems = manager.getProfiles().map { InputProfileItem(it.id, it.name) }

        visibleControllers = collectVisibleControllers()
        val controllerItems = visibleControllers.mapIndexed { index, controller ->
            InputControllerItem(
                index,
                controller.name,
                controller.controllerBindingCount,
                controller.isConnected
            )
        }

        val opacity = (preferences.getFloat(
            "overlay_opacity",
            InputControlsView.DEFAULT_OVERLAY_OPACITY
        ) * 100f).roundToInt()

        model = InputControlsModel(
            profileItems,
            currentProfile?.id ?: 0,
            opacity,
            controllerItems
        )
    }

    private fun collectVisibleControllers(): List<ExternalController> {
        val controllers = currentProfile?.loadControllers() ?: ArrayList()
        for (connected in ExternalController.getControllers()) {
            if (!controllers.contains(connected)) controllers.add(connected)
        }
        return controllers
    }

    // ---------- InputControlsCallbacks ----------

    override fun onProfileSelected(profileId: Int) {
        val manager = manager ?: return
        currentProfile = if (profileId > 0) manager.getProfile(profileId) else null
        rebuild()
    }

    override fun onOpacityChanged(percent: Int) {
        val snapped = ((percent / 5.0f).roundToInt() * 5).coerceIn(0, 100)
        preferences.edit().putFloat("overlay_opacity", snapped / 100.0f).apply()
        rebuild()
    }

    override fun onAddProfile() {
        ThemedAlertHost.prompt(activity, "Profile Name", "", "Add") { name ->
            val trimmed = name.trim()
            val manager = manager
            if (trimmed.isEmpty() || manager == null) return@prompt
            currentProfile = manager.createProfile(trimmed)
            rebuild()
        }
    }

    override fun onEditProfile() {
        val profile = requireProfile() ?: return
        ThemedAlertHost.prompt(activity, "Profile Name", profile.name, "Save") { name ->
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@prompt
            profile.name = trimmed
            profile.save()
            rebuild()
        }
    }

    override fun onDuplicateProfile() {
        val profile = requireProfile() ?: return
        ThemedAlertHost.confirm(
            activity,
            "Duplicate Profile?",
            "Do you want to duplicate this profile?",
            "Duplicate",
            {
                val manager = manager ?: return@confirm
                currentProfile = manager.duplicateProfile(profile)
                rebuild()
            }
        )
    }

    override fun onRemoveProfile() {
        val profile = requireProfile() ?: return
        ThemedAlertHost.confirm(
            activity,
            "Remove Profile?",
            "Do you want to remove this profile?",
            "Remove",
            {
                val manager = manager ?: return@confirm
                manager.removeProfile(profile)
                currentProfile = null
                rebuild()
            },
            true
        )
    }

    override fun onImportProfile() {
        ThemedAlertHost.actions(
            activity,
            "Import Profile",
            listOf(
                ThemedAlertHost.ActionItem("Open Local Profile", { launchProfilePicker?.invoke() }),
                ThemedAlertHost.ActionItem("Download Profiles", { downloadProfileList() })
            )
        )
    }

    override fun onExportProfile() {
        val profile = requireProfile() ?: return
        val exportedFile = manager?.exportProfile(profile) ?: return
        Toast.makeText(activity, "Profile exported to " + exportedFile.path, Toast.LENGTH_LONG).show()
    }

    override fun onOpenEditor() {
        val profile = requireProfile() ?: return
        val intent = Intent(activity, ControlsEditorActivity::class.java)
        intent.putExtra("profile_id", profile.id)
        activity.startActivity(intent)
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(R.anim.shared_axis_enter, R.anim.shared_axis_exit)
    }

    override fun onOpenController(index: Int) {
        val profile = requireProfile() ?: return
        val controller = visibleControllers.getOrNull(index) ?: return
        val intent = Intent(activity, ExternalControllerBindingsActivity::class.java)
        intent.putExtra("profile_id", profile.id)
        intent.putExtra("controller_id", controller.id)
        activity.startActivity(intent)
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(R.anim.shared_axis_enter, R.anim.shared_axis_exit)
    }

    override fun onRemoveController(index: Int) {
        val profile = currentProfile ?: return
        val controller = visibleControllers.getOrNull(index) ?: return
        ThemedAlertHost.confirm(
            activity,
            "Remove Controller?",
            "Do you want to remove this controller?",
            "Remove",
            {
                profile.removeController(controller)
                profile.save()
                rebuild()
            },
            true
        )
    }

    // ---------- Import / download ----------

    fun importProfileFrom(uri: Uri) {
        val manager = manager ?: return
        try {
            currentProfile = manager.importProfile(JSONObject(FileUtils.readString(activity, uri)))
            rebuild()
        } catch (e: Exception) {
            Toast.makeText(activity, "Unable to import profile", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadProfileList() {
        val loadingOverlay = ThemedLoadingOverlayHost.show(activity, activity.getString(R.string.loading))
        HttpUtils.download(String.format(INPUT_CONTROLS_URL, "index.txt")) { content ->
            activity.runOnUiThread {
                ThemedLoadingOverlayHost.dismiss(loadingOverlay)
                if (content == null) {
                    Toast.makeText(activity, "Unable to load profile list", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val items = content.split("\n")
                ThemedAlertHost.multiChoice(activity, "Import Profile", items, "Download", { positions ->
                    if (positions.isNotEmpty()) {
                        ThemedAlertHost.confirm(
                            activity,
                            "Download Profiles?",
                            "Do you want to download the selected profiles?",
                            "Download",
                            { downloadSelectedProfiles(items, positions) }
                        )
                    }
                })
            }
        }
    }

    private fun downloadSelectedProfiles(items: List<String>, positions: List<Int>) {
        val manager = manager ?: return
        val downloadOverlay = ThemedLoadingOverlayHost.show(activity, activity.getString(R.string.downloading_file))
        currentProfile = null
        val processed = AtomicInteger()
        for (position in positions) {
            HttpUtils.download(String.format(INPUT_CONTROLS_URL, items[position])) { content ->
                try {
                    if (content != null) manager.importProfile(JSONObject(content))
                } catch (ignored: JSONException) {
                }
                if (processed.incrementAndGet() == positions.size) {
                    activity.runOnUiThread {
                        ThemedLoadingOverlayHost.dismiss(downloadOverlay)
                        rebuild()
                    }
                }
            }
        }
    }

    private fun requireProfile(): ControlsProfile? {
        val profile = currentProfile
        if (profile == null) Toast.makeText(activity, "No profile selected", Toast.LENGTH_SHORT).show()
        return profile
    }
}
