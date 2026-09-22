package com.winlator.cmod.ui

import android.content.Context
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.accentSwitchColors
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.sidebarBorderColor
import com.winlator.cmod.ui.theme.sidebarCardColor
import com.winlator.cmod.widget.WinlatorHUD

// What the panel needs to render one frame. hudScalePercent/hudAlphaPercent are seeded at
// 0 on purpose — the original sidebar's SBHudScale/SBHudAlpha never called setValue() either,
// so they always opened at 0 regardless of the HUD's actual persisted scale/alpha. Replicated
// as-is rather than "fixed", since that's a separate, pre-existing rough edge.
data class HudPanelState(
    val hudOn: Boolean,
    val isModernStyle: Boolean,
    val hudScalePercent: Int,
    val hudAlphaPercent: Int,
    val showLogsRow: Boolean
)

interface HudPanelCallbacks {
    fun onHudMasterToggled(enabled: Boolean, styleIsModern: Boolean)
    fun onStyleChanged(isModern: Boolean)
    fun onHudScale(percent: Int)
    fun onHudAlpha(percent: Int)
    fun onResetHud()
    fun onShowLogs()
}

object HudSidebarPanelHost {
    @JvmStatic
    fun attach(composeView: ComposeView, state: HudPanelState, callbacks: HudPanelCallbacks) {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            WinZOverlayTheme {
                HudSidebarPanel(state, callbacks)
            }
        }
    }
}

@Composable
private fun HudSidebarPanel(state: HudPanelState, callbacks: HudPanelCallbacks) {
    var hudOn by remember { mutableStateOf(state.hudOn) }
    var isModern by remember { mutableStateOf(state.isModernStyle) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "HUD",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(18.dp))

        PanelCard {
            InlineToggleRow(
                label = "Enable HUD",
                checked = hudOn,
                onCheckedChange = {
                    hudOn = it
                    callbacks.onHudMasterToggled(it, isModern)
                }
            )
        }

        if (hudOn) {
            Spacer(Modifier.height(10.dp))
            PanelCard {
                StyleRow(
                    isModern = isModern,
                    onSelect = { modern ->
                        isModern = modern
                        callbacks.onStyleChanged(modern)
                    }
                )
            }
        }

        if (isModern) {
            Spacer(Modifier.height(10.dp))
            PanelCard {
                Text(
                    text = "HUD Metrics",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = controlAccentColor()
                )
                Spacer(Modifier.height(4.dp))
                HudMetricsGrid()
            }
        }

        Spacer(Modifier.height(10.dp))
        PanelCard {
            Text(
                text = "HUD Size",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = controlAccentColor()
            )
            var scale by remember { mutableStateOf(state.hudScalePercent.toFloat()) }
            Slider(
                value = scale,
                onValueChange = {
                    scale = it
                    callbacks.onHudScale(it.toInt())
                },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = controlAccentColor(), activeTrackColor = controlAccentColor())
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "HUD Opacity",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = controlAccentColor()
            )
            var alpha by remember { mutableStateOf(state.hudAlphaPercent.toFloat()) }
            Slider(
                value = alpha,
                onValueChange = {
                    alpha = it
                    callbacks.onHudAlpha(it.toInt())
                },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = controlAccentColor(), activeTrackColor = controlAccentColor())
            )
        }

        Spacer(Modifier.height(12.dp))
        PanelActionRow(label = "Reset HUD", onClick = callbacks::onResetHud)

        if (state.showLogsRow) {
            Spacer(Modifier.height(12.dp))
            PanelActionRow(label = "Show Logs", onClick = callbacks::onShowLogs)
        }
    }
}

@Composable
private fun StyleRow(isModern: Boolean, onSelect: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Style",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isModern) "Modern" else "Classic",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                DropdownMenuItem(text = { Text("Classic") }, onClick = { expanded = false; onSelect(false) })
                DropdownMenuItem(text = { Text("Modern") }, onClick = { expanded = false; onSelect(true) })
            }
        }
    }
}

// Fully self-contained: reads/writes WinlatorHUD's own static prefs directly, exactly like
// the dynamically-added checkboxes SidebarCleanupView used to inject here — no Java
// round-trip needed since WinlatorHUD's rendering already reacts to the same prefs key
// through its own SharedPreferences.OnSharedPreferenceChangeListener.
@Composable
private fun HudMetricsGrid() {
    val context = LocalContext.current
    MetricCheckboxRow(context, "FPS", WinlatorHUD.SHOW_FPS, "Renderer", WinlatorHUD.SHOW_RENDERER)
    MetricCheckboxRow(context, "GPU Usage", WinlatorHUD.SHOW_GPU_USAGE, "GPU Name", WinlatorHUD.SHOW_GPU_NAME)
    MetricCheckboxRow(context, "CPU Usage", WinlatorHUD.SHOW_CPU_USAGE, "CPU Temp", WinlatorHUD.SHOW_CPU_TEMP)
    MetricCheckboxRow(context, "RAM", WinlatorHUD.SHOW_RAM, "Power", WinlatorHUD.SHOW_POWER)
    MetricCheckboxRow(context, "Battery Temp", WinlatorHUD.SHOW_BATTERY_TEMP, "Charge State", WinlatorHUD.SHOW_CHARGE_STATE)
    Spacer(Modifier.height(6.dp))
    DualCellRow(context)
}

@Composable
private fun MetricCheckboxRow(context: Context, leftLabel: String, leftBit: Int, rightLabel: String, rightBit: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetricCheckbox(context, leftLabel, leftBit, Modifier.weight(1f))
        MetricCheckbox(context, rightLabel, rightBit, Modifier.weight(1f))
    }
}

@Composable
private fun MetricCheckbox(context: Context, label: String, bit: Int, modifier: Modifier) {
    var checked by remember { mutableStateOf(WinlatorHUD.isOptionEnabled(context, bit)) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = {
                checked = it
                WinlatorHUD.setOptionPreference(context, bit, it)
            },
            colors = CheckboxDefaults.colors(checkedColor = controlAccentColor())
        )
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DualCellRow(context: Context) {
    val prefs = remember { context.getSharedPreferences(WinlatorHUD.PREFS, Context.MODE_PRIVATE) }
    var checked by remember { mutableStateOf(prefs.getBoolean(WinlatorHUD.KEY_DUAL_CELL, false)) }
    InlineToggleRow(
        label = "Dual-cell correction",
        checked = checked,
        onCheckedChange = {
            checked = it
            prefs.edit().putBoolean(WinlatorHUD.KEY_DUAL_CELL, it).apply()
        }
    )
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
            .background(sidebarCardColor())
            .border(BorderStroke(1.dp, sidebarBorderColor()), shape)
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
            .background(sidebarCardColor())
            .border(BorderStroke(1.dp, sidebarBorderColor()), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content
    )
}
