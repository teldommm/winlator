package com.winlator.cmod.ui

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.controlAccentColor

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
