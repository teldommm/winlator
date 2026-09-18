package com.winlator.cmod.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.theme.controlAccentColor

// Native bridge so plain Java call sites (component/driver/runtime delete confirmations,
// "in use" warnings, etc.) get the exact same card look and button styling as every
// Compose-triggered dialog, instead of the stock (unthemed) android.app.AlertDialog.Builder.
object ThemedAlertHost {
    @JvmStatic
    fun confirm(activity: Activity, title: String, message: String, confirmLabel: String, onConfirm: Runnable) {
        showDialog(activity) { dismiss ->
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
    fun info(activity: Activity, title: String, message: String) {
        showDialog(activity) { dismiss ->
            DialogBody(title, message) {
                Button(
                    onClick = dismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
                ) { Text("OK") }
            }
        }
    }

    private fun showDialog(activity: Activity, content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit) {
        val dialog = Dialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZTheme {
                    ThemedDialogSurface {
                        content { dialog.dismiss() }
                    }
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.show()
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
