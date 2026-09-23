package com.winlator.cmod.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.widget.WinlatorHUD
import kotlin.math.roundToInt

// What the panel needs to render one frame. hudScalePercent/hudAlphaPercent must be the
// actually-persisted HUD scale/alpha (see WinlatorHUD.getSavedScalePercent/getSavedAlphaPercent)
// — they used to be seeded at 0 regardless of the saved value, causing the sliders to always
// open at 0%. Fixed at the call site in XServerDisplayActivity.setupSidebarHudControls().
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
    fun attach(sidebar: IngameSidebarController, panelId: Int, state: HudPanelState, callbacks: HudPanelCallbacks) {
        sidebar.setPanel(panelId) { HudSidebarPanel(state, callbacks) }
    }
}

private val HUD_STYLE_LABELS = listOf("Classic", "Modern")

@Composable
private fun HudSidebarPanel(state: HudPanelState, callbacks: HudPanelCallbacks) {
    var hudOn by remember { mutableStateOf(state.hudOn) }
    var isModern by remember { mutableStateOf(state.isModernStyle) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        SidebarPanelTitle("HUD")

        SidebarToggleRow(
            label = "Enable HUD",
            checked = hudOn,
            onCheckedChange = {
                hudOn = it
                callbacks.onHudMasterToggled(it, isModern)
            }
        )

        if (hudOn) {
            SidebarGap()
            SidebarCard {
                SidebarDropdownField(
                    caption = "Style",
                    options = HUD_STYLE_LABELS,
                    selectedIndex = if (isModern) 1 else 0,
                    onSelect = { index ->
                        val modern = index == 1
                        isModern = modern
                        callbacks.onStyleChanged(modern)
                    }
                )
            }
        }

        // Metrics and size/opacity only apply to the Modern HUD, and only while the HUD is
        // on. Previously gated on isModern alone, so they stayed visible under a disabled
        // HUD while the Style picker right above them was already hidden.
        if (hudOn && isModern) {
            SidebarGap()
            SidebarCard {
                SidebarSectionTitle("HUD Metrics")
                Spacer(Modifier.height(4.dp))
                HudMetricsGrid()
            }

            SidebarGap()
            SidebarCard {
                var scale by remember { mutableStateOf(state.hudScalePercent.toFloat()) }
                SidebarSlider(
                    label = "HUD Size",
                    valueText = "${scale.roundToInt()}%",
                    value = scale,
                    onValueChange = {
                        scale = it
                        callbacks.onHudScale(it.toInt())
                    },
                    valueRange = 0f..100f
                )
                Spacer(Modifier.height(6.dp))
                var alpha by remember { mutableStateOf(state.hudAlphaPercent.toFloat()) }
                SidebarSlider(
                    label = "HUD Opacity",
                    valueText = "${alpha.roundToInt()}%",
                    value = alpha,
                    onValueChange = {
                        alpha = it
                        callbacks.onHudAlpha(it.toInt())
                    },
                    valueRange = 0f..100f
                )
            }
        }

        SidebarGap()
        SidebarActionRow(label = "Reset HUD", onClick = callbacks::onResetHud)

        if (state.showLogsRow) {
            SidebarGap()
            SidebarActionRow(label = "Show Logs", onClick = callbacks::onShowLogs)
        }
    }
}

// Fully self-contained: reads/writes WinlatorHUD's own static prefs directly — no Java
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
        Text(
            text = label,
            style = SidebarText.small(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DualCellRow(context: Context) {
    val prefs = remember { context.getSharedPreferences(WinlatorHUD.PREFS, Context.MODE_PRIVATE) }
    var checked by remember { mutableStateOf(prefs.getBoolean(WinlatorHUD.KEY_DUAL_CELL, false)) }
    SidebarInlineToggle(
        label = "Dual-cell correction",
        checked = checked,
        onCheckedChange = {
            checked = it
            prefs.edit().putBoolean(WinlatorHUD.KEY_DUAL_CELL, it).apply()
        }
    )
}
