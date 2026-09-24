package com.winlator.cmod.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
}

object GraphicsSidebarPanelHost {
    @JvmStatic
    fun attach(
        sidebar: IngameSidebarController,
        panelId: Int,
        state: GraphicsPanelState,
        callbacks: GraphicsPanelCallbacks
    ) {
        sidebar.setPanel(panelId) { GraphicsSidebarPanel(state, callbacks) }
    }
}

private val UPSCALER_LABELS = listOf("SGSR", "FSR", "Lanczos 2", "Color Boost")
private val POSTFX_LABELS = listOf("None", "DLS", "CRT", "HDR", "Natural")
private val FRAMEGEN_LABELS = listOf("Off", "LSFG 2x", "LSFG 3x", "LSFG 4x")
private val RESHADE_EFFECTS = listOf(
    "Off", "Game Clarity", "Cinematic", "Vivid", "Competitive", "Adaptive Sharpen",
    "Filmic", "Arcade", "Retro CRT", "Upscale Sharp", "Pixel Clean", "Anime Edge"
)

// FPS Limit slider: position 0 = Off, positions 1..23 = 5..115 FPS in 5 FPS steps, position 24
// ("Custom") shows a numeric field for any other value (e.g. 40, 72, 144). 0 is what
// VulkanXServerView / PresentExtension treat as "no limit".
private const val FPS_STEP = 5
private const val FPS_SLIDER_MAX_FPS = 120
private const val FPS_CUSTOM_POSITION = FPS_SLIDER_MAX_FPS / FPS_STEP
// Same ceiling VulkanXServerView.setFpsLimit() clamps to.
private const val FPS_CUSTOM_MAX = 1000

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
        SidebarPanelTitle("Rendering")

        FpsLimiterCard(
            initialFps = state.fpsLimit,
            onFpsChanged = callbacks::onFpsLimitChanged
        )

        SidebarGap()
        SidebarCard {
            SidebarInlineToggle(
                label = "Super Resolution",
                checked = fsrEnabled,
                onCheckedChange = {
                    fsrEnabled = it
                    if (it && reshadeIndex > 0) {
                        reshadeIndex = 0
                        callbacks.onReshadeEffectChanged(0)
                    }
                    callbacks.onFsrToggled(it)
                }
            )
            if (fsrEnabled) {
                Spacer(Modifier.height(8.dp))
                SidebarDropdownField(
                    caption = "Upscaler Mode",
                    options = UPSCALER_LABELS,
                    selectedIndex = upscalerIndex,
                    onSelect = {
                        upscalerIndex = it
                        callbacks.onUpscalerModeChanged(it)
                    }
                )
            }
            if (sharpnessVisible) {
                Spacer(Modifier.height(12.dp))
                var sharpnessDraft by remember(sharpnessVisible) { mutableStateOf(sharpness.toFloat()) }
                SidebarSlider(
                    label = "Sharpness",
                    valueText = "$sharpness%",
                    value = sharpnessDraft,
                    onValueChange = {
                        sharpnessDraft = it
                        sharpness = it.toInt()
                        callbacks.onSharpnessChanged(it.toInt())
                    },
                    onValueChangeFinished = { callbacks.onSharpnessCommitted(sharpnessDraft.toInt()) },
                    valueRange = 0f..100f
                )
            }
            Spacer(Modifier.height(if (sharpnessVisible) 4.dp else 12.dp))
            SidebarDropdownField(
                caption = "Post Effect",
                options = POSTFX_LABELS,
                selectedIndex = postFxIndex,
                onSelect = {
                    postFxIndex = it
                    if (reshadeIndex > 0) {
                        reshadeIndex = 0
                        callbacks.onReshadeEffectChanged(0)
                    }
                    callbacks.onPostFxModeChanged(it)
                }
            )
        }

        SidebarGap()
        SidebarCard {
            SidebarSectionTitle("ReShade")
            Spacer(Modifier.height(10.dp))
            SidebarDropdownField(
                caption = "Effect",
                options = RESHADE_EFFECTS,
                selectedIndex = reshadeIndex,
                onSelect = { index ->
                    reshadeIndex = index
                    // Picking a real effect (index > 0) force-disables Super Resolution and
                    // resets Post Effect to None, since all three drive the same renderer
                    // filter/post-fx state and only one can be active at a time.
                    if (index > 0) {
                        if (fsrEnabled) {
                            fsrEnabled = false
                            callbacks.onFsrToggled(false)
                        }
                        if (postFxIndex != 0) {
                            postFxIndex = 0
                            callbacks.onPostFxModeChanged(0)
                        }
                    }
                    callbacks.onReshadeEffectChanged(index)
                }
            )
            if (reshadeIndex > 0) {
                Spacer(Modifier.height(12.dp))
                SidebarSlider(
                    label = "Strength",
                    valueText = "$reshadeStrength%",
                    value = reshadeStrength.toFloat(),
                    onValueChange = {
                        reshadeStrength = it.toInt()
                        callbacks.onReshadeStrengthChanged(it.toInt())
                    },
                    valueRange = 0f..100f
                )
            }
        }

        if (state.frameGenAvailable) {
            SidebarGap()
            SidebarCard {
                SidebarSectionTitle("Frame Generation")
                Spacer(Modifier.height(10.dp))
                SidebarDropdownField(
                    caption = null,
                    options = FRAMEGEN_LABELS,
                    selectedIndex = frameGenIndex,
                    onSelect = {
                        frameGenIndex = it
                        callbacks.onFrameGenChanged(it)
                    }
                )
                if (frameGenIndex > 0) {
                    Spacer(Modifier.height(12.dp))
                    var flowScale by remember { mutableStateOf(state.frameGenFlowScale) }
                    SidebarSlider(
                        label = "Flow Scale",
                        valueText = "${(flowScale * 100).roundToInt()}%",
                        value = flowScale,
                        onValueChange = {
                            flowScale = it
                            callbacks.onFrameGenFlowScaleChanged(it)
                        },
                        valueRange = 0.25f..1.0f
                    )
                }
            }
        }

        // Settings on this panel are saved as they change (per shortcut, or to the container when
        // launched without one) — there's no separate "Save Preset" step.
    }
}

@Composable
private fun FpsLimiterCard(initialFps: Int, onFpsChanged: (Int) -> Unit) {
    val initialPosition = when {
        initialFps <= 0 -> 0
        initialFps >= FPS_SLIDER_MAX_FPS || initialFps % FPS_STEP != 0 -> FPS_CUSTOM_POSITION
        else -> initialFps / FPS_STEP
    }
    var position by remember { mutableStateOf(initialPosition) }
    var customText by remember {
        mutableStateOf(if (initialPosition == FPS_CUSTOM_POSITION && initialFps > 0) initialFps.toString() else "")
    }
    // Last value handed to onFpsChanged, so the same limit isn't re-saved (shortcut.saveData()
    // writes to disk) on every slider release or keystroke that doesn't change it.
    var applied by remember { mutableStateOf(initialFps.coerceAtLeast(0)) }
    val focusManager = LocalFocusManager.current

    fun customValue(): Int? = customText.toIntOrNull()?.takeIf { it > 0 }?.coerceAtMost(FPS_CUSTOM_MAX)

    // null = nothing valid to apply yet (Custom selected with an empty field): keep the
    // current limit instead of silently jumping to some default.
    fun chosenLimit(): Int? = when {
        position <= 0 -> 0
        position < FPS_CUSTOM_POSITION -> position * FPS_STEP
        else -> customValue()
    }

    fun apply() {
        val limit = chosenLimit() ?: return
        if (limit != applied) {
            applied = limit
            onFpsChanged(limit)
        }
    }

    SidebarCard {
        SidebarSlider(
            label = "FPS Limit",
            valueText = when {
                position <= 0 -> "Off"
                position >= FPS_CUSTOM_POSITION -> customValue()?.let { "Custom · $it FPS" } ?: "Custom"
                else -> "${position * FPS_STEP} FPS"
            },
            value = position.toFloat(),
            onValueChange = {
                position = it.roundToInt().coerceIn(0, FPS_CUSTOM_POSITION)
                // Pre-fill Custom with the limit that was active, so dragging into it doesn't
                // leave an empty field.
                if (position >= FPS_CUSTOM_POSITION && customText.isEmpty() && applied > 0) {
                    customText = applied.toString()
                }
            },
            onValueChangeFinished = { apply() },
            valueRange = 0f..FPS_CUSTOM_POSITION.toFloat()
        )
        if (position >= FPS_CUSTOM_POSITION) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = customText,
                onValueChange = { text ->
                    customText = text.filter(Char::isDigit).take(4)
                    apply()
                },
                singleLine = true,
                placeholder = { Text("Enter custom FPS") },
                suffix = { Text("FPS") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    // Normalize what's shown to what was applied (e.g. 5000 -> 1000).
                    customValue()?.let { customText = it.toString() }
                    apply()
                    focusManager.clearFocus()
                }),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
