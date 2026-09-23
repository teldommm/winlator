package com.winlator.cmod.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.accentSwitchColors
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.sidebarCardFillColor
import kotlin.math.roundToInt

// ---------- Graphics panel ----------

// Indices match the original Spinners' positions exactly (upscalerModeIndex: SGSR/FSR/
// Lanczos2/ColorBoost, postFxModeIndex: None/DLS/CRT/HDR/Natural, reshadeEffectIndex: the
// 12-entry ReShade list, frameGenMultiplierIndex: Off/2x/3x/4x) so Java's existing mapping
// tables can be reused unchanged.
data class GraphicsPanelState(
    val fpsLimit: Int,
    val fsrEnabled: Boolean,
    val upscalerModeIndex: Int,
    val sharpnessPercent: Int,
    val postFxModeIndex: Int,
    val reshadeEffectIndex: Int,
    val reshadeStrengthPercent: Int,
    val frameGenAvailable: Boolean,
    val frameGenMultiplierIndex: Int,
    val frameGenFlowScale: Float
)

interface GraphicsPanelCallbacks {
    fun onFpsLimitChanged(fps: Int)
    fun onFsrToggled(enabled: Boolean)
    fun onUpscalerModeChanged(index: Int)
    fun onSharpnessChanged(percent: Int)
    fun onSharpnessCommitted(percent: Int)
    fun onPostFxModeChanged(index: Int)
    fun onReshadeEffectChanged(index: Int)
    fun onReshadeStrengthChanged(percent: Int)
    fun onFrameGenChanged(multiplierIndex: Int)
    fun onFrameGenFlowScaleChanged(scale: Float)
    fun onSavePreset()
}

object GraphicsSidebarPanelHost {
    @JvmStatic
    fun attach(
        composeView: ComposeView,
        state: GraphicsPanelState,
        callbacks: GraphicsPanelCallbacks
    ) {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            WinZOverlayTheme {
                GraphicsSidebarPanel(state, callbacks)
            }
        }
    }
}

private val UPSCALER_LABELS = listOf("SGSR", "FSR", "Lanczos 2", "Color Boost")
private val POSTFX_LABELS = listOf("None", "DLS", "CRT", "HDR", "Natural")
private val FRAMEGEN_LABELS = listOf("Off", "LSFG 2x", "LSFG 3x", "LSFG 4x")
private val RESHADE_EFFECTS = listOf(
    "Off", "Game Clarity", "Cinematic", "Vivid", "Competitive", "Adaptive Sharpen",
    "Filmic", "Arcade", "Retro CRT", "Upscale Sharp", "Pixel Clean", "Anime Edge"
)

@Composable
private fun GraphicsSidebarPanel(
    state: GraphicsPanelState,
    callbacks: GraphicsPanelCallbacks
) {
    var fsrEnabled by remember { mutableStateOf(state.fsrEnabled) }
    var upscalerIndex by remember { mutableStateOf(state.upscalerModeIndex) }
    var sharpness by remember { mutableStateOf(state.sharpnessPercent) }
    var postFxIndex by remember { mutableStateOf(state.postFxModeIndex) }
    var reshadeIndex by remember { mutableStateOf(state.reshadeEffectIndex) }
    var reshadeStrength by remember { mutableStateOf(state.reshadeStrengthPercent) }
    var frameGenIndex by remember { mutableStateOf(state.frameGenMultiplierIndex) }

    // Same visibility rule as the original updateSharpnessVis(): shown while FSR is on OR
    // the Post Effect picker is set to DLS (index 1) — Sharpness feeds whichever is active.
    val sharpnessVisible = fsrEnabled || postFxIndex == 1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "Rendering",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))

        FpsLimiterCard(
            initialFps = state.fpsLimit,
            onFpsChanged = callbacks::onFpsLimitChanged
        )

        Spacer(Modifier.height(14.dp))
        PanelCard {
            InlineToggleRow(
                label = "Super Resolution",
                checked = fsrEnabled,
                onCheckedChange = {
                    fsrEnabled = it
                    callbacks.onFsrToggled(it)
                }
            )
            if (fsrEnabled) {
                Spacer(Modifier.height(10.dp))
                DropdownRow(
                    label = "Upscaler Mode",
                    options = UPSCALER_LABELS,
                    selectedIndex = upscalerIndex,
                    onSelect = {
                        upscalerIndex = it
                        callbacks.onUpscalerModeChanged(it)
                    }
                )
            }
            if (sharpnessVisible) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Sharpness",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = controlAccentColor()
                    )
                    Text(
                        text = "$sharpness%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = controlAccentColor()
                    )
                }
                var sharpnessDraft by remember(sharpnessVisible) { mutableStateOf(sharpness.toFloat()) }
                Slider(
                    value = sharpnessDraft,
                    onValueChange = {
                        sharpnessDraft = it
                        sharpness = it.toInt()
                        callbacks.onSharpnessChanged(it.toInt())
                    },
                    onValueChangeFinished = { callbacks.onSharpnessCommitted(sharpnessDraft.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = controlAccentColor(), activeTrackColor = controlAccentColor())
                )
            }
            Spacer(Modifier.height(10.dp))
            DropdownRow(
                label = "Post Effect",
                options = POSTFX_LABELS,
                selectedIndex = postFxIndex,
                onSelect = {
                    postFxIndex = it
                    callbacks.onPostFxModeChanged(it)
                }
            )
        }

        Spacer(Modifier.height(14.dp))
        PanelCard {
            Text(
                text = "ReShade",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Effect",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            DropdownRow(
                label = null,
                options = RESHADE_EFFECTS,
                selectedIndex = reshadeIndex,
                onSelect = { index ->
                    reshadeIndex = index
                    // Mirrors ReshadeSidebarPanelView.applyEffect(): picking a real effect
                    // (index > 0) force-disables Super Resolution, since both ultimately
                    // drive the same renderer filter/post-fx state and the original never
                    // let them run together from this side.
                    if (index > 0 && fsrEnabled) {
                        fsrEnabled = false
                        callbacks.onFsrToggled(false)
                    }
                    callbacks.onReshadeEffectChanged(index)
                }
            )
            if (reshadeIndex > 0) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Strength",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = controlAccentColor()
                    )
                    Text(
                        text = "$reshadeStrength%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = controlAccentColor()
                    )
                }
                Slider(
                    value = reshadeStrength.toFloat(),
                    onValueChange = {
                        reshadeStrength = it.toInt()
                        callbacks.onReshadeStrengthChanged(it.toInt())
                    },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = controlAccentColor(), activeTrackColor = controlAccentColor())
                )
            }
        }

        if (state.frameGenAvailable) {
            Spacer(Modifier.height(14.dp))
            PanelCard {
                Text(
                    text = "Frame Generation",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                DropdownRow(
                    label = null,
                    options = FRAMEGEN_LABELS,
                    selectedIndex = frameGenIndex,
                    onSelect = {
                        frameGenIndex = it
                        callbacks.onFrameGenChanged(it)
                    }
                )
                if (frameGenIndex > 0) {
                    Spacer(Modifier.height(10.dp))
                    var flowScale by remember { mutableStateOf(state.frameGenFlowScale) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Flow Scale",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = controlAccentColor()
                        )
                        Text(
                            text = "${(flowScale * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = controlAccentColor()
                        )
                    }
                    Slider(
                        value = flowScale,
                        onValueChange = {
                            flowScale = it
                            callbacks.onFrameGenFlowScaleChanged(it)
                        },
                        valueRange = 0.25f..1.0f,
                        colors = SliderDefaults.colors(thumbColor = controlAccentColor(), activeTrackColor = controlAccentColor())
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        PanelActionRow(label = "Save Preset", onClick = callbacks::onSavePreset)
    }
}

private const val FPS_LIMIT_MIN = 30
private const val FPS_LIMIT_MAX = 120

@Composable
private fun FpsLimiterCard(initialFps: Int, onFpsChanged: (Int) -> Unit) {
    var fps by remember { mutableStateOf(initialFps.coerceIn(FPS_LIMIT_MIN, FPS_LIMIT_MAX)) }

    PanelCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "FPS Limit",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$fps FPS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = controlAccentColor()
            )
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = fps.toFloat(),
            onValueChange = { fps = it.roundToInt() },
            onValueChangeFinished = { onFpsChanged(fps) },
            valueRange = FPS_LIMIT_MIN.toFloat()..FPS_LIMIT_MAX.toFloat(),
            colors = SliderDefaults.colors(thumbColor = controlAccentColor(), activeTrackColor = controlAccentColor())
        )
    }
}

@Composable
private fun DropdownRow(label: String?, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().height(42.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (label != null) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(modifier = if (label == null) Modifier.fillMaxWidth() else Modifier) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = options.getOrElse(selectedIndex) { options.first() },
                    modifier = if (label == null) Modifier.weight(1f) else Modifier,
                    style = MaterialTheme.typography.bodyMedium,
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
                options.forEachIndexed { index, optionLabel ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            expanded = false
                            onSelect(index)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
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
            .height(48.dp)
            .clip(shape)
            .background(sidebarCardFillColor())
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = controlAccentColor()
        )
    }
}

@Composable
private fun PanelCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
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
