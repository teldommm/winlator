package com.winlator.cmod.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.accentSwitchColors
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.sidebarCardFillColor

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
    fun attach(composeView: ComposeView, state: InputPanelState, callbacks: InputPanelCallbacks) {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            WinZOverlayTheme {
                InputSidebarPanel(state, callbacks)
            }
        }
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
        Text(
            text = "Controls",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(18.dp))

        PanelCard {
            ProfileRow(
                profiles = state.profiles,
                selectedProfileId = profileId,
                onSelect = { newId ->
                    profileId = newId
                    pushControlsSettings()
                },
                onEditClick = { callbacks.onEditProfiles(profileId) }
            )
            Spacer(Modifier.height(8.dp))
            InlineToggleRow(
                label = stringResource(R.string.show_touchscreen_controls),
                checked = showControls,
                onCheckedChange = {
                    showControls = it
                    pushControlsSettings()
                }
            )
            InlineToggleRow(
                label = stringResource(R.string.enable_touchscreen_timeout),
                checked = timeout,
                onCheckedChange = {
                    timeout = it
                    pushControlsSettings()
                }
            )
            InlineToggleRow(
                label = stringResource(R.string.enable_touchscreen_haptics),
                checked = haptics,
                onCheckedChange = {
                    haptics = it
                    pushControlsSettings()
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        PanelCard {
            Text(
                text = "Touch Controls Opacity",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = controlAccentColor()
            )
            var opacityDraft by remember {
                mutableStateOf(state.controlsOpacityPercent.coerceIn(10, 100).toFloat())
            }
            Slider(
                value = opacityDraft,
                onValueChange = {
                    opacityDraft = it
                    callbacks.onControlsOpacity(it.toInt())
                },
                valueRange = 10f..100f,
                colors = SliderDefaults.colors(thumbColor = controlAccentColor(), activeTrackColor = controlAccentColor())
            )
        }

        Spacer(Modifier.height(10.dp))
        PanelActionRow(label = "Show Keyboard", onClick = callbacks::onShowKeyboard)
        Spacer(Modifier.height(10.dp))
        PanelActionRow(label = "Vibration", onClick = callbacks::onVibration)
        Spacer(Modifier.height(18.dp))

        PanelCard {
            InlineToggleRow(
                label = "Relative Mouse",
                checked = relativeMouse,
                onCheckedChange = {
                    relativeMouse = it
                    callbacks.onRelativeMouse(it)
                }
            )
        }
        Spacer(Modifier.height(10.dp))
        PanelCard {
            InlineToggleRow(
                label = "Disable Mouse",
                checked = disableMouse,
                onCheckedChange = {
                    disableMouse = it
                    callbacks.onDisableMouse(it)
                }
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profiles: List<InputProfileOption>,
    selectedProfileId: Int,
    onSelect: (Int) -> Unit,
    onEditClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val disabledLabel = "-- ${stringResource(R.string.disabled)} --"
    val selectedName = if (selectedProfileId < 0) {
        disabledLabel
    } else {
        profiles.firstOrNull { it.id == selectedProfileId }?.name ?: disabledLabel
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                DropdownMenuItem(
                    text = { Text(disabledLabel) },
                    onClick = {
                        expanded = false
                        onSelect(-1)
                    }
                )
                profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.name) },
                        onClick = {
                            expanded = false
                            onSelect(profile.id)
                        }
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        IconButton(onClick = onEditClick) {
            Icon(
                painter = painterResource(id = R.drawable.icon_settings),
                contentDescription = null,
                tint = controlAccentColor()
            )
        }
    }
}

@Composable
private fun InlineToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = accentSwitchColors())
    }
}

@Composable
private fun PanelActionRow(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(sidebarCardFillColor())
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(sidebarCardFillColor())
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content
    )
}
