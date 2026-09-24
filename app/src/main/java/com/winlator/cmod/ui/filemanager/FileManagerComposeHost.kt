package com.winlator.cmod.ui.filemanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.core.ExeIconExtractor
import com.winlator.cmod.ui.LandscapeScreenHeader
import com.winlator.cmod.ui.PortraitMainHeader
import com.winlator.cmod.ui.theme.controlAccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import com.winlator.cmod.ui.ShellChrome

// One row in the file listing — a file or a folder in the current directory.
data class FileEntryUiModel(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isExecutable: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val iconRes: Int,
    // Absolute path to the cached extracted-icon PNG; only set for executables. FileIcon()
    // below extracts and decodes it lazily — only for rows Compose actually renders — so
    // opening a big folder doesn't kick off extraction for every .exe in it at once.
    val iconCachePath: String? = null
)

// One choice in the drive-selector sheet: Drive D:/C:/Z:, a discovered external volume, or the
// "Add External Storage" scan action (id == DRIVE_OPTION_SCAN_ID).
data class DriveOptionUiModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val selected: Boolean
)

data class FileManagerModel(
    val currentPath: String,
    val entries: List<FileEntryUiModel>,
    val driveTitle: String,
    val driveIconRes: Int,
    val driveOptions: List<DriveOptionUiModel>,
    val storageUsedText: String,
    val storagePercent: Int,
    val pasteVisible: Boolean
)

interface FileManagerCallbacks {
    fun onUpDir()
    fun onDriveOptionSelected(id: String)
    fun onOpenDirectory(path: String)
    fun onRunFile(path: String)
    fun onAddGame(path: String)
    fun onCopyFile(path: String)
    fun onCutFile(path: String)
    fun onRenameFile(path: String)
    fun onDeleteFile(path: String)
    fun onPasteClick()
}

// Reserved DriveOptionUiModel id for the "Add External Storage / Scan again" row — it triggers a
// rescan rather than navigating anywhere, so the sheet is kept open after it's tapped.
const val DRIVE_OPTION_SCAN_ID = "scan"

// The File Manager screen. Hosted by MainShell through FileManagerRoute (FileManagerRoute.kt);
// the logic lives in FileManagerController.
@Composable
internal fun FileManagerScreen(model: FileManagerModel, callbacks: FileManagerCallbacks) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val activity = LocalContext.current as? MainActivity
    var driveSheetOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (landscape) {
            LandscapeScreenHeader("File Manager")
        } else {
            PortraitMainHeader("File Manager")
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = callbacks::onUpDir) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Up directory")
            }
            Spacer(Modifier.width(4.dp))
            Surface(
                onClick = { driveSheetOpen = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(painterResource(model.driveIconRes), null, modifier = Modifier.size(24.dp), tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        model.driveTitle,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text(
                model.currentPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 13.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
        }

        StorageMeter(
            usedText = model.storageUsedText,
            percent = model.storagePercent,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // In portrait MainShell's bottom nav floats over this tab; keep the paste button and the
        // last rows above it (File Manager used to be a detail screen in portrait, without the nav).
        val navClearance = if (landscape) 0.dp else ShellChrome.PortraitNavClearance
        Box(Modifier.weight(1f)) {
            if (model.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("This folder is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 92.dp + navClearance)
                ) {
                    items(model.entries, key = { it.path }) { entry ->
                        FileRow(entry = entry, callbacks = callbacks)
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 73.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            if (model.pasteVisible) {
                FloatingActionButton(
                    onClick = callbacks::onPasteClick,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 18.dp + navClearance),
                    containerColor = controlAccentColor(),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Outlined.ContentPaste, "Paste")
                }
            }
        }
    }

    if (driveSheetOpen) {
        DriveSelectorSheet(
            options = model.driveOptions,
            onSelect = { id ->
                if (id != DRIVE_OPTION_SCAN_ID) driveSheetOpen = false
                callbacks.onDriveOptionSelected(id)
            },
            onDismiss = { driveSheetOpen = false }
        )
    }
}

@Composable
private fun StorageMeter(usedText: String, percent: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Storage", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(usedText, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = controlAccentColor(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

// The file's action menu, anchored right on its row (same DropdownMenu/DropdownMenuItem
// technique as SettingChoice's audio-driver picker) rather than a full-screen dialog: tapping a
// file, or long-pressing any row, opens a small list right where it was tapped.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(entry: FileEntryUiModel, callbacks: FileManagerCallbacks) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (entry.isDirectory) callbacks.onOpenDirectory(entry.path) else menuExpanded = true
                    },
                    onLongClick = { menuExpanded = true }
                )
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                FileIcon(entry, Modifier.size(30.dp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    entryDetails(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.widthIn(min = 200.dp, max = 320.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            if (entry.isExecutable) {
                DropdownMenuItem(
                    text = { Text("Run / Open") },
                    leadingIcon = { Icon(painterResource(R.drawable.ui_ic_play), null) },
                    onClick = { menuExpanded = false; callbacks.onRunFile(entry.path) }
                )
                DropdownMenuItem(
                    text = { Text("Add this game") },
                    leadingIcon = { Icon(painterResource(R.drawable.ui_ic_add), null) },
                    onClick = { menuExpanded = false; callbacks.onAddGame(entry.path) }
                )
            }
            DropdownMenuItem(
                text = { Text("Copy") },
                leadingIcon = { Icon(painterResource(R.drawable.ui_ic_copy), null) },
                onClick = { menuExpanded = false; callbacks.onCopyFile(entry.path) }
            )
            DropdownMenuItem(
                text = { Text("Cut (Move)") },
                leadingIcon = { Icon(Icons.Outlined.ContentCut, null) },
                onClick = { menuExpanded = false; callbacks.onCutFile(entry.path) }
            )
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(painterResource(R.drawable.ui_ic_edit), null) },
                onClick = { menuExpanded = false; callbacks.onRenameFile(entry.path) }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ui_ic_delete), null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { menuExpanded = false; callbacks.onDeleteFile(entry.path) }
            )
        }
    }
}

// Mirrors FileManagerController's (formerly FileManagerFragment's) folderDetails()/fileDetails()/modifiedLabel() text
// formatting. Kept here rather than pushed from Java so it stays lazy: a folder's child count
// (File.list()) is only read for rows Compose actually composes, matching the RecyclerView's
// original per-visible-item binding cost instead of paying it for the whole directory upfront.
@Composable
private fun entryDetails(entry: FileEntryUiModel): String {
    val dateText = remember(entry.lastModified) {
        if (entry.lastModified <= 0) "" else
            SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()).format(Date(entry.lastModified))
    }
    return if (entry.isDirectory) {
        val count = remember(entry.path) { File(entry.path).list()?.size ?: 0 }
        "Folder  •  $count" + (if (count == 1) " item" else " items") +
                (if (dateText.isEmpty()) "" else "  •  $dateText")
    } else {
        formatSize(entry.sizeBytes) + (if (dateText.isEmpty()) "" else "  •  $dateText")
    }
}

private fun formatSize(size: Long): String {
    if (size < 1024) return "$size B"
    val z = (63 - java.lang.Long.numberOfLeadingZeros(size)) / 10
    return String.format(Locale.getDefault(), "%.1f %sB", size / (1L shl (z * 10)).toDouble(), " KMGTPE"[z])
}

@Composable
private fun FileIcon(entry: FileEntryUiModel, modifier: Modifier = Modifier) {
    val cachePath = entry.iconCachePath
    if (entry.isExecutable && cachePath != null) {
        val bitmap by produceState<Bitmap?>(initialValue = null, entry.path, cachePath) {
            value = withContext(Dispatchers.IO) {
                val cacheFile = File(cachePath)
                if (!cacheFile.exists()) {
                    // ExeIconExtractor.extractAsync runs the PE-resource parsing on its own
                    // background thread and calls back (off the main thread) when the PNG has
                    // been written; bridge that callback into this coroutine.
                    suspendCancellableCoroutine<Unit> { cont ->
                        ExeIconExtractor.extractAsync(File(entry.path), cacheFile, false) {
                            if (cont.isActive) cont.resumeWith(Result.success(Unit))
                        }
                    }
                }
                if (cacheFile.exists()) BitmapFactory.decodeFile(cacheFile.absolutePath) else null
            }
        }
        val loaded = bitmap
        if (loaded != null) {
            Image(loaded.asImageBitmap(), null, modifier = modifier)
            return
        }
    }
    Icon(
        painterResource(entry.iconRes),
        null,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveSelectorSheet(
    options: List<DriveOptionUiModel>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        val accent = controlAccentColor()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 20.dp)
        ) {
            options.forEach { option ->
                val isScan = option.id == DRIVE_OPTION_SCAN_ID
                Surface(
                    onClick = { onSelect(option.id) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (option.selected) accent.copy(alpha = 0.16f) else Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isScan) {
                            Icon(
                                Icons.Outlined.Refresh,
                                null,
                                tint = accent,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = when {
                                    option.selected -> accent
                                    isScan -> accent
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            if (option.subtitle.isNotEmpty()) {
                                Text(
                                    option.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (option.selected) Icon(Icons.Outlined.Check, null, tint = accent)
                    }
                }
            }
        }
    }
}
