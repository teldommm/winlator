package com.winlator.cmod.ui.library

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.GameSaveManager
import com.winlator.cmod.ui.ThemedAlertHost
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.accentSwitchColors
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.dividerColor
import com.winlator.cmod.ui.theme.hairlineColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// "Game saves" window of a game (the Saves button on the game page): where its save files were
// found, the latest backup, the automatic-backup switch and Back up / Restore.
// Hosted like WinlatorServicesDialog: a ComposeView added onto the activity's content root, so it
// shares the themed dialog shell and scrim of every other window in the app.
//
// The window cannot be dismissed while a backup or restore is running, so the file work is never
// cut off half way. Restoring asks first: it overwrites the game's current save files.
object GameSavesComposeDialog {
    private const val VIEW_TAG = "game_saves_dialog"

    @JvmStatic
    fun show(context: Context, shortcut: Shortcut) {
        val activity = context as? Activity ?: return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        if (root.findViewWithTag<View>(VIEW_TAG) != null) return

        lateinit var composeView: ComposeView
        composeView = ComposeView(activity).apply {
            tag = VIEW_TAG
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    val visibleState = remember { MutableTransitionState(false) }
                    var busy by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { visibleState.targetState = true }
                    LaunchedEffect(visibleState.currentState) {
                        if (!visibleState.currentState && !visibleState.targetState) {
                            (composeView.parent as? ViewGroup)?.removeView(composeView)
                        }
                    }
                    val dismiss: () -> Unit = { if (!busy) visibleState.targetState = false }
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
                                // Swallow taps inside the card so they don't reach the dismiss scrim.
                                ThemedDialogSurface(
                                    modifier = Modifier
                                        .heightIn(max = 620.dp)
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                                ) {
                                    GameSavesPanel(
                                        activity = activity,
                                        shortcut = shortcut,
                                        busy = busy,
                                        setBusy = { busy = it },
                                        onClose = dismiss
                                    )
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

/**
 * Restoring replaces the game's current save files with the latest backup, so it is confirmed
 * first (same confirmation window as removing a game). Shared with the shortcut editor.
 */
internal fun confirmGameSavesRestore(context: Context, gameName: String, onConfirm: () -> Unit) {
    val activity = context as? AppCompatActivity
    if (activity == null) {
        onConfirm()
        return
    }
    ThemedAlertHost.confirm(
        activity,
        "Restore saves?",
        "The latest backup replaces the current save files of $gameName.",
        "Restore",
        Runnable { onConfirm() },
        true
    )
}

@Composable
private fun ColumnScope.GameSavesPanel(
    activity: Activity,
    shortcut: Shortcut,
    busy: Boolean,
    setBusy: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val globalAutoBackup = GameSaveManager.isGlobalAutoBackupEnabled(activity)
    var roots by remember(shortcut.file.path) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var autoBackup by remember { mutableStateOf(GameSaveManager.isAutoBackupEnabled(shortcut)) }
    var latest by remember { mutableStateOf(GameSaveManager.getLatestBackup(shortcut)) }
    var message by remember { mutableStateOf<String?>(null) }

    fun rescan() {
        if (busy) return
        setBusy(true)
        message = "Scanning common save locations…"
        scope.launch {
            roots = withContext(Dispatchers.IO) { GameSaveManager.rediscoverSaveRoots(shortcut) }
            message = if (roots.isEmpty()) {
                "No per-game folder detected. A manual backup will fall back to the whole Wine profile."
            } else {
                "Detected ${roots.size} save location${if (roots.size == 1) "" else "s"}."
            }
            setBusy(false)
        }
    }

    fun backupNow() {
        if (busy) return
        setBusy(true)
        message = "Backing up saves…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { GameSaveManager.backup(shortcut, false) }
            latest = GameSaveManager.getLatestBackup(shortcut)
            message = when {
                result.ok && result.wholeProfile ->
                    "Backup complete: ${result.fileCount} files. No per-game folder was detected, so the Wine profile was used."
                result.ok -> "Backup complete: ${result.fileCount} files."
                else -> "Backup failed: ${result.error ?: "unknown error"}"
            }
            setBusy(false)
        }
    }

    fun restoreNow() {
        if (busy) return
        confirmGameSavesRestore(activity, shortcut.name) {
            setBusy(true)
            message = "Restoring latest backup…"
            scope.launch {
                val result = withContext(Dispatchers.IO) { GameSaveManager.restoreLatest(shortcut) }
                message = if (result.ok) {
                    "Restored ${result.fileCount} files."
                } else {
                    "Restore failed: ${result.error ?: "unknown error"}"
                }
                setBusy(false)
            }
        }
    }

    LaunchedEffect(shortcut.file.path) {
        roots = withContext(Dispatchers.IO) { GameSaveManager.getSaveRoots(shortcut) }
        latest = GameSaveManager.getLatestBackup(shortcut)
        loading = false
    }

    Text("Game saves", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(
        shortcut.name,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp)
    )
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = dividerColor())

    Surface(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        shape = WinZShapes.Small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SavesLabel("Backup folder")
            Text(
                "Winlator/Saves/${GameSaveManager.getGameDir(shortcut).name}/",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SavesLabel("Latest backup")
            Text(
                latest?.let(::backupLabel) ?: "No backup yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = dividerColor())
            val autoChecked = globalAutoBackup || autoBackup
            val autoEnabled = !busy && !globalAutoBackup
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = autoChecked, enabled = autoEnabled, role = Role.Switch) {
                        autoBackup = it
                        GameSaveManager.setAutoBackupEnabled(shortcut, it)
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Automatic backup", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (globalAutoBackup) "Enabled for all games in Settings"
                        else "Replace auto-latest.zip when the game exits",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoChecked,
                    onCheckedChange = null,
                    enabled = autoEnabled,
                    colors = accentSwitchColors()
                )
            }

            HorizontalDivider(color = dividerColor())
            SavesLabel("Save locations")
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = controlAccentColor())
                    Text(
                        "  Detecting…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                roots.isEmpty() -> Text(
                    "No specific folder detected yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> roots.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            SavesOutlinedButton("Rescan save locations", Modifier.fillMaxWidth(), enabled = !busy && !loading, icon = Icons.Outlined.Refresh) { rescan() }

            message?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = controlAccentColor())
                        Text("  ")
                    }
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SavesOutlinedButton("Close", Modifier.weight(1f), enabled = !busy) { onClose() }
        SavesOutlinedButton("Restore", Modifier.weight(1f), enabled = !busy && latest != null) { restoreNow() }
        Button(
            onClick = { backupNow() },
            modifier = Modifier.weight(1f),
            enabled = !busy && !loading,
            colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
        ) { Text("Back up") }
    }
}

@Composable
private fun SavesLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SavesOutlinedButton(
    text: String,
    modifier: Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text)
    }
}

private fun backupLabel(file: File): String {
    val date = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    return "${file.name}\n$date"
}
