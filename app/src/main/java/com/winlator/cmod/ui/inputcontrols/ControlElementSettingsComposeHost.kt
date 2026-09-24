package com.winlator.cmod.ui.inputcontrols

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.accentSwitchColors
import com.winlator.cmod.ui.theme.controlAccentColor
import kotlin.math.roundToInt

enum class ControlElementIconTint { NONE, INVERT, BLUE }

data class ControlElementIcon(
    val key: String,
    val bitmap: Bitmap,
    val selected: Boolean,
    val tint: ControlElementIconTint,
    val dimmed: Boolean = false,
    val longPressable: Boolean = false
)

data class ControlElementBindingRow(
    val label: String,
    val sourceTypeIndex: Int,
    val options: List<String>,
    val selectedOptionIndex: Int
)

data class ControlElementSettingsModel(
    val typeIndex: Int,
    val typeOptions: List<String>,
    val showShape: Boolean,
    val shapeIndex: Int,
    val shapeOptions: List<String>,
    val showRange: Boolean,
    val rangeIndex: Int,
    val rangeOptions: List<String>,
    // Orientation and Columns only mean anything for RANGE_BUTTON (bounding box / drawing /
    // JSON all check the type); Columns calls setBindingCount(), which wipes every binding, so
    // showing it for a Button/D-Pad/Stick/Trackpad silently destroyed their bindings.
    val showOrientation: Boolean,
    val verticalOrientation: Boolean,
    val showColumns: Boolean,
    val columns: Int,
    val columnsMin: Int,
    val columnsMax: Int,
    val scalePercent: Int,
    val opacityPercent: Int,
    val colorOptions: List<Int>,
    val selectedColor: Int,
    val showToggleSwitch: Boolean,
    val toggleSwitch: Boolean,
    // mouseMoveMode is only read in the BUTTON touch paths.
    val showMouseMoveMode: Boolean,
    val mouseMoveMode: Boolean,
    val showTextAndIcon: Boolean,
    val text: String,
    val icons: List<ControlElementIcon>,
    val hasCustomIcon: Boolean,
    val bindings: List<ControlElementBindingRow>
)

private val BINDING_SOURCE_TYPES = listOf("Keyboard", "Mouse", "Gamepad")

// Same hosting technique as ThemedAlertHost/PresetEditorComposeDialog (ComposeView added to the
// activity's content root, WinZOverlayTheme + single scrim + ThemedDialogSurface), combined with
// InputControlsComposeHost's external-refresh trick (a MutableState stashed on the view's tag) --
// every field here applies live as the person edits it, so the Activity rebuilds the model after
// each callback and pushes it back in with update(), the same way InputControlsFragment does.
object ControlElementSettingsComposeHost {
    @JvmStatic
    fun create(context: Context, model: ControlElementSettingsModel, callbacks: ControlElementSettingsCallbacks): ComposeView {
        val modelState = mutableStateOf(model)
        lateinit var composeView: ComposeView
        composeView = ComposeView(context).apply {
            tag = modelState
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    val visibleState = remember { MutableTransitionState(false) }
                    LaunchedEffect(Unit) { visibleState.targetState = true }
                    LaunchedEffect(visibleState.currentState) {
                        if (!visibleState.currentState && !visibleState.targetState) {
                            (composeView.parent as? ViewGroup)?.removeView(composeView)
                        }
                    }
                    val dismiss: () -> Unit = {
                        visibleState.targetState = false
                        callbacks.onDone()
                    }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = fadeIn(tween(180)),
                        exit = fadeOut(tween(150))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { dismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedVisibility(
                                visibleState = visibleState,
                                enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200)),
                                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.9f, animationSpec = tween(150))
                            ) {
                                ThemedDialogSurface(
                                    modifier = Modifier
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                                        .heightIn(max = 600.dp)
                                ) {
                                    ControlElementSettingsScreen(modelState.value, callbacks, dismiss)
                                }
                            }
                        }
                    }
                }
            }
        }
        return composeView
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun update(view: View?, model: ControlElementSettingsModel) {
        (view?.tag as? MutableState<ControlElementSettingsModel>)?.value = model
    }

    @JvmStatic
    fun show(context: Context, view: View) {
        if (view.parent != null) return
        val root = (context as android.app.Activity).findViewById<ViewGroup>(android.R.id.content)
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

@Composable
private fun ColumnScope.ControlElementSettingsScreen(
    model: ControlElementSettingsModel,
    cb: ControlElementSettingsCallbacks,
    dismiss: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Element Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = dismiss,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) { Text("Done") }
    }
    Spacer(Modifier.height(14.dp))
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
    ) {
        EnumSelector("Type", model.typeOptions, model.typeIndex, cb::onTypeChanged)
        if (model.showShape) {
            Spacer(Modifier.height(10.dp))
            EnumSelector("Shape", model.shapeOptions, model.shapeIndex, cb::onShapeChanged)
        }
        if (model.showRange) {
            Spacer(Modifier.height(10.dp))
            EnumSelector("Range", model.rangeOptions, model.rangeIndex, cb::onRangeChanged)
        }

        if (model.showOrientation) {
            Spacer(Modifier.height(14.dp))
            Text("Orientation", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentButton("Horizontal", !model.verticalOrientation, Modifier.weight(1f)) { cb.onOrientationChanged(false) }
                SegmentButton("Vertical", model.verticalOrientation, Modifier.weight(1f)) { cb.onOrientationChanged(true) }
            }
        }

        if (model.showColumns) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Columns", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Stepper(model.columns, model.columnsMin, model.columnsMax, cb::onColumnsChanged)
            }
        }

        Spacer(Modifier.height(14.dp))
        PercentSliderCard("Scale", model.scalePercent, SCALE_RANGE, cb::onScaleChanged)
        Spacer(Modifier.height(10.dp))
        PercentSliderCard("Opacity", model.opacityPercent, 0f..100f, cb::onOpacityChanged)

        Spacer(Modifier.height(14.dp))
        Text("Scheme Color", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        ColorSwatchGrid(model.colorOptions, model.selectedColor, cb::onColorSelected)

        if (model.showToggleSwitch) {
            Spacer(Modifier.height(14.dp))
            SettingSwitchRow("Toggle Switch", model.toggleSwitch, cb::onToggleSwitchChanged)
        }
        if (model.showMouseMoveMode) {
            Spacer(Modifier.height(10.dp))
            SettingSwitchRow("Relative Mouse Move", model.mouseMoveMode, cb::onMouseMoveModeChanged)
        }

        if (model.showTextAndIcon) {
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = model.text,
                onValueChange = cb::onTextChanged,
                singleLine = true,
                label = { Text("Custom Text") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Icon", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (model.hasCustomIcon) {
                    OutlinedButton(onClick = cb::onRemoveIcon) { Text("Remove") }
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(onClick = cb::onBrowseIcon) { Text("Browse\u2026") }
            }
            Spacer(Modifier.height(8.dp))
            IconGrid(model.icons, cb)
        }

        if (model.bindings.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Bindings", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            model.bindings.forEachIndexed { index, binding ->
                Spacer(Modifier.height(8.dp))
                BindingRow(binding, onSourceTypeChanged = { cb.onBindingSourceTypeChanged(index, it) }, onValueChanged = { cb.onBindingValueChanged(index, it) })
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun EnumSelector(label: String, options: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth()) {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        options.getOrElse(selectedIndex) { "" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 200.dp, max = 380.dp).heightIn(max = 360.dp),
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                val accent = controlAccentColor()
                options.forEachIndexed { index, option ->
                    val isSelected = index == selectedIndex
                    DropdownMenuItem(
                        text = {
                            Text(
                                option,
                                color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        trailingIcon = { if (isSelected) Icon(Icons.Outlined.Check, null, tint = accent) },
                        onClick = {
                            expanded = false
                            onSelected(index)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) { Text(label) }
    }
}

@Composable
private fun Stepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (value > min) onChange(value - 1) }, enabled = value > min) {
            Text("\u2212", style = MaterialTheme.typography.titleLarge)
        }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { if (value < max) onChange(value + 1) }, enabled = value < max) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

// Scale was 0..100%: it couldn't enlarge anything, 0% collapsed the element, and just
// touching it clamped bundled profiles' 1.2 / 1.25 scales down to 1.0.
private val SCALE_RANGE = 50f..200f

// Same look and behaviour as Overlay Opacity on the Input screen (InputControlsComposeHost's
// OpacityCard) minus the card frame: title, value above the track on the right, continuous slider, and the
// value is applied once on release instead of on every tick.
@Composable
private fun PercentSliderCard(
    title: String,
    percent: Int,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Int) -> Unit
) {
    var live by remember(percent) { mutableStateOf(percent.toFloat().coerceIn(range)) }
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("${live.roundToInt()}%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = {
                val value = live.roundToInt()
                if (value != percent) onChange(value)
            },
            modifier = Modifier.fillMaxWidth(),
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = controlAccentColor(), activeTrackColor = controlAccentColor())
        )
    }
}

@Composable
private fun ColorSwatchGrid(colors: List<Int>, selectedColor: Int, onSelected: (Int) -> Unit) {
    val perRow = 8
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { color ->
                    val isSelected = color == selectedColor
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (color != 0) Color(color) else Color.White)
                            .then(
                                if (isSelected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier
                            )
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSelected(color) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = accentSwitchColors())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconGrid(icons: List<ControlElementIcon>, cb: ControlElementSettingsCallbacks) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(44.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(icons, key = { it.key }) { icon ->
            val colorFilter = when (icon.tint) {
                ControlElementIconTint.NONE -> null
                ControlElementIconTint.BLUE -> ColorFilter.tint(Color(0xff2184ff))
                ControlElementIconTint.INVERT -> ColorFilter.colorMatrix(
                    ColorMatrix(
                        floatArrayOf(
                            -1f, 0f, 0f, 0f, 255f,
                            0f, -1f, 0f, 0f, 255f,
                            0f, 0f, -1f, 0f, 255f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .then(
                        if (icon.selected) Modifier.border(2.dp, controlAccentColor(), RoundedCornerShape(8.dp)) else Modifier
                    )
                    .combinedClickable(
                        onClick = { cb.onIconSelected(icon.key) },
                        onLongClick = if (icon.longPressable) ({ cb.onIconLongPress(icon.key) }) else null
                    )
                    .alpha(if (icon.dimmed) 0.75f else 1f),
                contentAlignment = Alignment.Center
            ) {
                Image(icon.bitmap.asImageBitmap(), null, modifier = Modifier.size(28.dp), colorFilter = colorFilter)
            }
        }
    }
}

@Composable
private fun BindingRow(binding: ControlElementBindingRow, onSourceTypeChanged: (Int) -> Unit, onValueChanged: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(binding.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(0.4f)) {
                CompactDropdown(BINDING_SOURCE_TYPES, binding.sourceTypeIndex, onSourceTypeChanged)
            }
            Box(Modifier.weight(0.6f)) {
                CompactDropdown(binding.options, binding.selectedOptionIndex, onValueChanged)
            }
        }
    }
}

// Separate small dialog for the toolbar's scheme-color picker (BTSchemeColor), which sets the
// profile-wide default color rather than one element's own color -- kept apart from
// ControlElementSettingsComposeHost above since it's a different target and has its own trigger,
// but reuses the same ColorSwatchGrid and hosting technique.
fun interface SchemeColorSelectedListener {
    fun onColorSelected(color: Int)
}

object SchemeColorComposeDialog {
    @JvmStatic
    fun show(context: Context, colors: List<Int>, selectedColor: Int, listener: SchemeColorSelectedListener) {
        val root = (context as android.app.Activity).findViewById<ViewGroup>(android.R.id.content)
        lateinit var composeView: ComposeView
        composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    val visibleState = remember { MutableTransitionState(false) }
                    LaunchedEffect(Unit) { visibleState.targetState = true }
                    LaunchedEffect(visibleState.currentState) {
                        if (!visibleState.currentState && !visibleState.targetState) {
                            (composeView.parent as? ViewGroup)?.removeView(composeView)
                        }
                    }
                    val dismiss: () -> Unit = { visibleState.targetState = false }
                    AnimatedVisibility(visibleState = visibleState, enter = fadeIn(tween(180)), exit = fadeOut(tween(150))) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { dismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedVisibility(
                                visibleState = visibleState,
                                enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200)),
                                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.9f, animationSpec = tween(150))
                            ) {
                                ThemedDialogSurface(
                                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                                ) {
                                    var current by remember { mutableStateOf(selectedColor) }
                                    Text("Scheme Color", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Applies to every control that doesn't have its own custom color",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    ColorSwatchGrid(colors, current) { color ->
                                        current = color
                                        listener.onColorSelected(color)
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    Row(Modifier.align(Alignment.End)) {
                                        Button(
                                            onClick = dismiss,
                                            colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
                                        ) { Text("Done") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        root.addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

@Composable
private fun CompactDropdown(options: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    options.getOrElse(selectedIndex) { "" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 160.dp, max = 320.dp).heightIn(max = 360.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val accent = controlAccentColor()
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                DropdownMenuItem(
                    text = {
                        Text(
                            option,
                            color = if (isSelected) accent else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    trailingIcon = { if (isSelected) Icon(Icons.Outlined.Check, null, tint = accent) },
                    onClick = {
                        expanded = false
                        onSelected(index)
                    }
                )
            }
        }
    }
}
