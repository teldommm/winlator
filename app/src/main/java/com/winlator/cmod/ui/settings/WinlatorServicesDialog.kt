package com.winlator.cmod.ui.settings

import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.contentdialog.DriverRepo
import com.winlator.cmod.core.RemoteSources
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.dividerColor
import com.winlator.cmod.ui.theme.hairlineColor

// "Winlator Services": one window for every remote address the app uses (component catalog,
// Proton manifest, driver repositories, input-controls profiles, artwork). Same hosting technique
// as PresetEditorComposeDialog: a ComposeView added onto the activity's content root, so it shares
// the themed dialog shell and scrim of every other dialog in the app.
//
// Edits are a draft until Save; the values are stored through RemoteSources (blank / default means
// "no override"), so the rest of the app reads them from there and needs no other plumbing.
object WinlatorServicesDialog {
    private const val VIEW_TAG = "winlator_services_dialog"

    @JvmStatic
    fun show(context: Context) {
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
                    LaunchedEffect(Unit) { visibleState.targetState = true }
                    LaunchedEffect(visibleState.currentState) {
                        if (!visibleState.currentState && !visibleState.targetState) {
                            (composeView.parent as? ViewGroup)?.removeView(composeView)
                        }
                    }
                    val dismiss: () -> Unit = { visibleState.targetState = false }
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
                                    ServicesScreen(context = activity, onCancel = dismiss, onSaved = dismiss)
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
private fun ColumnScope.ServicesScreen(context: Context, onCancel: () -> Unit, onSaved: () -> Unit) {
    var contentsUrl by remember { mutableStateOf(RemoteSources.contentsUrl(context)) }
    var protonManifest by remember { mutableStateOf(RemoteSources.protonManifestUrl(context)) }
    var inputControls by remember { mutableStateOf(RemoteSources.get(context, RemoteSources.KEY_INPUT_CONTROLS, RemoteSources.DEFAULT_INPUT_CONTROLS)) }
    var steamGrid by remember { mutableStateOf(RemoteSources.get(context, RemoteSources.KEY_STEAMGRID, RemoteSources.DEFAULT_STEAMGRID)) }
    var gamesSearch by remember { mutableStateOf(RemoteSources.get(context, RemoteSources.KEY_GAMESDB_SEARCH, RemoteSources.DEFAULT_GAMESDB_SEARCH)) }
    var gamesCdn by remember { mutableStateOf(RemoteSources.get(context, RemoteSources.KEY_GAMESDB_CDN, RemoteSources.DEFAULT_GAMESDB_CDN)) }
    val repos = remember { mutableStateListOf<DriverRepo>().apply { addAll(RemoteSources.driverRepos(context)) } }
    var newRepoName by remember { mutableStateOf("") }
    var newRepoUrl by remember { mutableStateOf("") }

    val manifestInvalid = protonManifest.isNotBlank() && !RemoteSources.isHttps(protonManifest)
    val gamesSearchInvalid = !gamesSearch.contains(RemoteSources.QUERY_PLACEHOLDER)

    fun addPendingRepo() {
        val api = RemoteSources.normalizeDriverRepoUrl(newRepoUrl)
        if (api.isEmpty()) return
        if (repos.none { it.apiUrl == api }) {
            val name = newRepoName.trim().ifEmpty { RemoteSources.suggestRepoName(api) }
            repos.add(DriverRepo(name, api))
        }
        newRepoName = ""
        newRepoUrl = ""
    }

    Text("Winlator Services", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(
        "Choose where the app downloads components, drivers, controls and artwork from",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            GroupLabel("Components")
            ServiceField("Contents URL", contentsUrl, RemoteSources.DEFAULT_CONTENTS_URL) { contentsUrl = it }
            ServiceField(
                label = "Proton manifest URL (optional)",
                value = protonManifest,
                default = RemoteSources.DEFAULT_PROTON_MANIFEST,
                error = if (manifestInvalid) "Must start with https://" else null
            ) { protonManifest = it }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    "Driver repositories",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                TextButton(onClick = {
                    repos.clear()
                    repos.addAll(RemoteSources.defaultDriverRepos())
                }) {
                    Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Default")
                }
            }
            if (repos.isEmpty()) {
                Text(
                    "No repositories: the driver list will be empty",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            repos.toList().forEachIndexed { index, repo ->
                RepoRow(repo) { repos.removeAt(index) }
            }
            OutlinedTextField(
                value = newRepoName,
                onValueChange = { newRepoName = it },
                singleLine = true,
                label = { Text("Name (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = newRepoUrl,
                onValueChange = { newRepoUrl = it },
                singleLine = true,
                label = { Text("owner/repo or GitHub releases API URL") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = { addPendingRepo() },
                enabled = newRepoUrl.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, hairlineColor())
            ) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add repository")
            }

            HorizontalDivider(color = dividerColor())
            GroupLabel("Input controls")
            ServiceField("Profiles folder URL", inputControls, RemoteSources.DEFAULT_INPUT_CONTROLS) { inputControls = it }

            HorizontalDivider(color = dividerColor())
            GroupLabel("Artwork")
            ServiceField("SteamGridDB API URL", steamGrid, RemoteSources.DEFAULT_STEAMGRID) { steamGrid = it }
            ServiceField(
                label = "TheGamesDB search URL",
                value = gamesSearch,
                default = RemoteSources.DEFAULT_GAMESDB_SEARCH,
                error = if (gamesSearchInvalid) "Must contain ${RemoteSources.QUERY_PLACEHOLDER}" else null
            ) { gamesSearch = it }
            ServiceField("TheGamesDB images URL", gamesCdn, RemoteSources.DEFAULT_GAMESDB_CDN) { gamesCdn = it }
        }
    }

    TextButton(
        onClick = {
            contentsUrl = RemoteSources.DEFAULT_CONTENTS_URL
            protonManifest = RemoteSources.DEFAULT_PROTON_MANIFEST
            inputControls = RemoteSources.DEFAULT_INPUT_CONTROLS
            steamGrid = RemoteSources.DEFAULT_STEAMGRID
            gamesSearch = RemoteSources.DEFAULT_GAMESDB_SEARCH
            gamesCdn = RemoteSources.DEFAULT_GAMESDB_CDN
            repos.clear()
            repos.addAll(RemoteSources.defaultDriverRepos())
            newRepoName = ""
            newRepoUrl = ""
        },
        modifier = Modifier.align(Alignment.End).padding(top = 6.dp)
    ) {
        Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Restore defaults")
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
            border = BorderStroke(1.dp, hairlineColor())
        ) { Text("Cancel") }
        Button(
            onClick = {
                if (newRepoUrl.isNotBlank()) addPendingRepo()
                RemoteSources.set(context, RemoteSources.KEY_CONTENTS_URL, contentsUrl, RemoteSources.DEFAULT_CONTENTS_URL)
                RemoteSources.set(context, RemoteSources.KEY_PROTON_MANIFEST, protonManifest, RemoteSources.DEFAULT_PROTON_MANIFEST)
                RemoteSources.set(context, RemoteSources.KEY_INPUT_CONTROLS, inputControls, RemoteSources.DEFAULT_INPUT_CONTROLS)
                RemoteSources.set(context, RemoteSources.KEY_STEAMGRID, steamGrid, RemoteSources.DEFAULT_STEAMGRID)
                RemoteSources.set(context, RemoteSources.KEY_GAMESDB_SEARCH, gamesSearch, RemoteSources.DEFAULT_GAMESDB_SEARCH)
                RemoteSources.set(context, RemoteSources.KEY_GAMESDB_CDN, gamesCdn, RemoteSources.DEFAULT_GAMESDB_CDN)
                RemoteSources.saveDriverRepos(context, repos.toList())
                onSaved()
            },
            enabled = !manifestInvalid && !gamesSearchInvalid,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
        ) { Text("Save") }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// One address. The trailing icon puts the default back (or clears the field when the default is
// empty); the default is shown underneath while the field differs from it, so it is never lost.
@Composable
private fun ServiceField(
    label: String,
    value: String,
    default: String,
    error: String? = null,
    onChange: (String) -> Unit
) {
    val differs = value.trim() != default
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        isError = error != null,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        supportingText = when {
            error != null -> ({ Text(error) })
            differs && default.isNotEmpty() -> ({
                Text("Default: $default", maxLines = 1, overflow = TextOverflow.Ellipsis)
            })
            else -> null
        },
        trailingIcon = if (differs) ({
            IconButton(onClick = { onChange(default) }) {
                Icon(
                    if (default.isEmpty()) Icons.Outlined.Close else Icons.Outlined.Refresh,
                    contentDescription = if (default.isEmpty()) "Clear" else "Restore default",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }) else null
    )
}

@Composable
private fun RepoRow(repo: DriverRepo, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = WinZShapes.Small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    repo.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    repo.apiUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "Remove repository",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
