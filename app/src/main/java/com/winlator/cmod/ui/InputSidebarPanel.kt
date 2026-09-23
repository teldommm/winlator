package com.winlator.cmod.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.controlAccentColor
import kotlin.math.roundToInt

// One entry in the controls-profile dropdown. Plain data holder so this file doesn't need
// to depend on com.winlator.cmod.inputcontrols.ControlsProfile — Java builds this list from it.
data class InputProfileOption(val id: Int, val name: String)

// Everything the panel needs to render one frame. Java rebuilds this fresh (from
// SharedPreferences / inputControlsView / the activity's own fields) and calls attach()
// again whenever the underlying state can change outside the panel itself (e.g. after
// returning from the profile editor) — same "just re-render" approach as the Screen panel.
data class InputPanelState(
    val profiles: List<InputProfileOption>,
    val selectedProfileId: Int, // -1 = disabled
    val showTouchscreenControls: Boolean,
    val touchscreenTimeout: Boolean,
    val touchscreenHaptics: Boolean,
    val controlsOpacityPercent: Int,
    val relativeMouse: Boolean,
    val disableMouse: Boolean
)

interface InputPanelCallbacks {
    // Fired whenever the profile picker or any of the three touchscreen switches changes —
    // always carries the full current snapshot of all four, mirroring the old
    // applySidebarInputControls(), which re-read all four every time any one of them changed.
    fun onControlsSettingsChanged(
        profileId: Int,
        showTouchscreenControls: Boolean,
        touchscreenTimeout: Boolean,
        touchscreenHaptics: Boolean
    )

    fun onEditProfiles(selectedProfileId: Int)
    fun onControlsOpacity(percent: Int)
    fun onShowKeyboard()
    fun onVibration()
    fun onRelativeMouse(enabled: Boolean)
    fun onDisableMouse(enabled: Boolean)
}

object InputSidebarPanelHost {
    @JvmStatic
    // Called again whenever the underlying state changes outside the panel; each call
    // re-registers with a new generation, so the panel recomposes from scratch with the
    // new snapshot (same effect the old repeated setContent() had).
    fun attach(sidebar: IngameSidebarController, panelId: Int, state: InputPanelState, callbacks: InputPanelCallbacks) {
        sidebar.setPanel(panelId) { InputSidebarPanel(state, callbacks) }
    }
}

@Composable
private fun InputSidebarPanel(state: InputPanelState, callbacks: InputPanelCallbacks) {
    var profileId by remember { mutableStateOf(state.selectedProfileId) }
    var showControls by remember { mutableStateOf(state.showTouchscreenControls) }
    var timeout by remember { mutableStateOf(state.touchscreenTimeout) }
    var haptics by remember { mutableStateOf(state.touchscreenHaptics) }
    var relativeMouse by remember { mutableStateOf(state.relativeMouse) }
    var disableMouse by remember { mutableStateOf(state.disableMouse) }

    fun pushControlsSettings() {
        callbacks.onControlsSettingsChanged(profileId, showControls, timeout, haptics)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        SidebarPanelTitle("Controls")

        SidebarCard {
            ProfileRow(
                profiles = state.profiles,
                selectedProfileId = profileId,
                onSelect = { newId ->
                    profileId = newId
                    pushControlsSettings()
                },
                onEditClick = { callbacks.onEditProfiles(profileId) }
            )
            Spacer(Modifier.height(6.dp))
            SidebarInlineToggle(
                label = stringResource(R.string.show_touchscreen_controls),
                checked = showControls,
                onCheckedChange = {
                    showControls = it
                    pushControlsSettings()
                }
            )
            SidebarInlineToggle(
                label = stringResource(R.string.enable_touchscreen_timeout),
                checked = timeout,
                onCheckedChange = {
                    timeout = it
                    pushControlsSettings()
                }
            )
            SidebarInlineToggle(
                label = stringResource(R.string.enable_touchscreen_haptics),
                checked = haptics,
                onCheckedChange = {
                    haptics = it
                    pushControlsSettings()
                }
            )
        }

        SidebarGap()
        SidebarCard {
            var opacityDraft by remember {
                mutableStateOf(state.controlsOpacityPercent.coerceIn(10, 100).toFloat())
            }
            SidebarSlider(
                label = "Controls Opacity",
                valueText = "${opacityDraft.roundToInt()}%",
                value = opacityDraft,
                onValueChange = {
                    opacityDraft = it
                    callbacks.onControlsOpacity(it.toInt())
                },
                valueRange = 10f..100f
            )
        }

        SidebarGap()
        SidebarActionRow(label = "Show Keyboard", onClick = callbacks::onShowKeyboard)
        SidebarGap()
        SidebarActionRow(label = "Vibration", onClick = callbacks::onVibration)
        SidebarGap()
        SidebarToggleRow(
            label = "Relative Mouse",
            checked = relativeMouse,
            onCheckedChange = {
                relativeMouse = it
                callbacks.onRelativeMouse(it)
            }
        )
        SidebarGap()
        SidebarToggleRow(
            label = "Disable Mouse",
            checked = disableMouse,
            onCheckedChange = {
                disableMouse = it
                callbacks.onDisableMouse(it)
            }
        )
    }
}

@Composable
private fun ProfileRow(
    profiles: List<InputProfileOption>,
    selectedProfileId: Int,
    onSelect: (Int) -> Unit,
    onEditClick: () -> Unit
) {
    val disabledLabel = "-- ${stringResource(R.string.disabled)} --"
    // Index 0 is always "Disabled" (id -1); profiles follow in order. An id that no longer
    // exists (profile deleted in the editor) falls back to Disabled, as before.
    val options = listOf(disabledLabel) + profiles.map { it.name }
    val selectedIndex = if (selectedProfileId < 0) 0
        else profiles.indexOfFirst { it.id == selectedProfileId }.let { if (it < 0) 0 else it + 1 }

    SidebarCaption("Controls Profile")
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SidebarDropdownField(
            caption = null,
            options = options,
            selectedIndex = selectedIndex,
            onSelect = { index -> onSelect(if (index == 0) -1 else profiles[index - 1].id) },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onEditClick) {
            Icon(
                painter = painterResource(id = R.drawable.icon_settings),
                contentDescription = "Edit profiles",
                tint = controlAccentColor()
            )
        }
    }
}
