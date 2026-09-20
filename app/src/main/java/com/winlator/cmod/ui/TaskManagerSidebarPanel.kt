package com.winlator.cmod.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.controlAccentColor

// One process row. rawName is the exact name WinHandler/Windows reports (used for the
// bring-to-front/kill/affinity calls); displayName additionally carries the " *32" suffix
// for WOW64 processes — cosmetic only, never sent back to WinHandler.
data class ProcessRowData(
    val pid: Int,
    val rawName: String,
    val displayName: String,
    val pidLabel: String,
    val memoryLabel: String,
    val affinityMask: Int,
    val iconBitmap: Bitmap?
)

interface TaskManagerCallbacks {
    fun onNewTask()
    fun onBringToFront(pid: Int, name: String)
    fun onEndProcess(pid: Int, name: String)
    fun onProcessorAffinity(pid: Int, name: String, affinityMask: Int)
}

// Plain observable holder, not a data class — TaskManagerSidebar.java mutates these
// properties directly (they compile to normal getX()/setX() from Java) on every timer
// tick / onGetProcessInfo callback, and Compose recomposes whatever actually changed.
class TaskManagerPanelState {
    var cpuLabel: String by mutableStateOf("--%")
    var memoryLabel: String by mutableStateOf("-- / --")
    var processes: List<ProcessRowData> by mutableStateOf(emptyList())
}

object TaskManagerPanelHost {
    @JvmStatic
    fun attach(composeView: ComposeView, callbacks: TaskManagerCallbacks): TaskManagerPanelState {
        val state = TaskManagerPanelState()
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            WinZOverlayTheme {
                TaskManagerPanel(state, callbacks)
            }
        }
        return state
    }
}

@Composable
private fun TaskManagerPanel(state: TaskManagerPanelState, callbacks: TaskManagerCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "Task Manager",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(18.dp))

        Row(Modifier.fillMaxWidth().height(66.dp)) {
            MetricCard(modifier = Modifier.weight(0.82f), title = "CPU", value = state.cpuLabel)
            Spacer(Modifier.width(10.dp))
            MetricCard(modifier = Modifier.weight(1.18f), title = "Memory", value = state.memoryLabel)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Processes: ${state.processes.size}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))

        if (state.processes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_items_to_display),
                    color = controlAccentColor()
                )
            }
        } else {
            state.processes.forEach { row ->
                ProcessRow(row = row, callbacks = callbacks)
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(4.dp))
        PanelActionRow(label = "+ New Task", onClick = callbacks::onNewTask)
    }
}

@Composable
private fun MetricCard(modifier: Modifier, title: String, value: String) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .padding(9.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = controlAccentColor()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = controlAccentColor()
        )
    }
}

@Composable
private fun ProcessRow(row: ProcessRowData, callbacks: TaskManagerCallbacks) {
    var menuExpanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = row.iconBitmap
        if (icon != null) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Icon(
                painter = painterResource(id = R.drawable.taskmgr_process),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.Unspecified
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = row.pidLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = row.memoryLabel,
            modifier = Modifier.width(72.dp),
            maxLines = 1,
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.ui_ic_more),
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.processor_affinity)) },
                    leadingIcon = { Icon(painterResource(id = R.drawable.icon_popup_menu_cpu), null) },
                    onClick = {
                        menuExpanded = false
                        callbacks.onProcessorAffinity(row.pid, row.rawName, row.affinityMask)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bring_to_front)) },
                    leadingIcon = { Icon(painterResource(id = R.drawable.icon_popup_menu_bring_to_front), null) },
                    onClick = {
                        menuExpanded = false
                        callbacks.onBringToFront(row.pid, row.rawName)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.end_process)) },
                    leadingIcon = { Icon(painterResource(id = R.drawable.icon_popup_menu_remove), null) },
                    onClick = {
                        menuExpanded = false
                        callbacks.onEndProcess(row.pid, row.rawName)
                    }
                )
            }
        }
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
            .background(MaterialTheme.colorScheme.surface)
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
