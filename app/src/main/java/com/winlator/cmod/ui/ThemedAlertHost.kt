package com.winlator.cmod.ui

import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.controlAccentColor
import java.util.function.Consumer

// Native bridge so plain Java call sites (component/driver/runtime delete confirmations,
// "in use" warnings, etc.) get the exact same card look and button styling as every
// Compose-triggered dialog, instead of the stock (unthemed) android.app.AlertDialog.Builder.
//
// This is added directly into the Activity's own content view hierarchy (android.R.id.content)
// rather than a separate android.app.Dialog window: that hierarchy is already attached to the
// same window Compose already runs in elsewhere in this app (via fragments), so the ComposeView
// finds its lifecycle/viewmodel/saved-state owners for free — no extra dependency needed.
object ThemedAlertHost {
    @JvmStatic
    fun confirm(activity: AppCompatActivity, title: String, message: String, confirmLabel: String, onConfirm: Runnable) {
        showOverlay(activity) { dismiss ->
            DialogBody(title, message) {
                OutlinedButton(
                    onClick = dismiss,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) { Text("Cancel") }
                Button(
                    onClick = { dismiss(); onConfirm.run() },
                    colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
                ) { Text(confirmLabel) }
            }
        }
    }

    @JvmStatic
    fun info(activity: AppCompatActivity, title: String, message: String) {
        showOverlay(activity) { dismiss ->
            DialogBody(title, message) {
                Button(
                    onClick = dismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
                ) { Text("OK") }
            }
        }
    }

    // Single-line text input, same card/button styling as confirm(). onConfirm receives the
    // field's current text; the field starts pre-filled with initialValue (e.g. for Rename).
    // confirmLabel has no default (it would sit before the required onConfirm parameter, which
    // @JvmOverloads cannot generate a shorter overload for) — Java call sites always pass it.
    @JvmStatic
    fun prompt(
        activity: AppCompatActivity,
        title: String,
        initialValue: String,
        confirmLabel: String,
        onConfirm: Consumer<String>
    ) {
        showOverlay(activity) { dismiss ->
            var value by remember { mutableStateOf(initialValue) }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = dismiss,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) { Text("Cancel") }
                Button(
                    onClick = { dismiss(); onConfirm.accept(value) },
                    colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
                ) { Text(confirmLabel) }
            }
        }
    }

    // One option among several, e.g. Replace/Rename for a paste conflict. destructive tints the
    // row's label with the theme's error color (e.g. an overwrite that can't be undone).
    // destructive is last (default) so @JvmOverloads can offer a 2-arg overload from Java.
    class Option @JvmOverloads constructor(
        val label: String,
        val onClick: Runnable,
        val destructive: Boolean = false
    )

    // Message plus a stack of full-width option rows, with a separate Cancel button below —
    // for choices that aren't a plain yes/no (e.g. "File Conflict": Replace / Rename / Cancel).
    @JvmStatic
    @JvmOverloads
    fun choice(
        activity: AppCompatActivity,
        title: String,
        message: String,
        options: List<Option>,
        cancelLabel: String = "Cancel"
    ) {
        showOverlay(activity) { dismiss ->
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    Surface(
                        onClick = { dismiss(); option.onClick.run() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (option.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    ) {
                        Box(Modifier.padding(vertical = 12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(option.label, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.align(Alignment.End)) {
                OutlinedButton(
                    onClick = dismiss,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) { Text(cancelLabel) }
            }
        }
    }

    // One row in an actions() menu — the themed replacement for a native PopupMenu item.
    // iconRes is a drawable resource id (0 for none), same convention as the rest of this app's
    // Java call sites (R.drawable.icon_open, android.R.drawable.ic_menu_agenda, etc). The two
    // defaulted params are last so @JvmOverloads can offer 2/3/4-arg overloads from Java.
    class ActionItem @JvmOverloads constructor(
        val label: String,
        val onClick: Runnable,
        @DrawableRes val iconRes: Int = 0,
        val destructive: Boolean = false
    )

    // Translucent card listing tappable rows — same surface/border as every other dialog here,
    // used where the app previously showed a plain android.widget.PopupMenu (e.g. per-file
    // Run/Copy/Cut/Rename/Delete actions in the file manager). title has no default (it precedes
    // the required items list) — Java call sites pass null explicitly when there's no title.
    @JvmStatic
    fun actions(activity: AppCompatActivity, title: String?, items: List<ActionItem>) {
        showOverlay(activity) { dismiss ->
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
            }
            Column {
                items.forEachIndexed { index, item ->
                    Surface(
                        onClick = { dismiss(); item.onClick.run() },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Transparent,
                        contentColor = if (item.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (item.iconRes != 0) {
                                Icon(
                                    painterResource(id = item.iconRes),
                                    null,
                                    tint = if (item.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 14.dp)
                                )
                            }
                            Text(item.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (index != items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }

    private fun showOverlay(activity: AppCompatActivity, content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        lateinit var composeView: ComposeView
        composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    val dismiss: () -> Unit = { root.removeView(composeView) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = dismiss
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        ThemedDialogSurface(modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}) {
                            content(dismiss)
                        }
                    }
                }
            }
        }
        root.addView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }
}

@Composable
private fun ColumnScope.DialogBody(
    title: String,
    message: String,
    buttons: @Composable () -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Text(message, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(18.dp))
    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        buttons()
    }
}
