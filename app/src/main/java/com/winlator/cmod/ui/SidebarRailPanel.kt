package com.winlator.cmod.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.destructiveColor
import com.winlator.cmod.ui.theme.sidebarCardFillColor

// One selectable rail entry (Graphics/Screen/Input/FPS/TaskManager). `id` reuses the
// panel's R.id.LLSubXxx id (res/values/ids_sidebar.xml) as its identity, so there's no separate
// parentId/subId pair to keep in sync the way the old native rail needed — selecting a
// rail item and showing its panel are now driven by the same int.
data class SidebarRailItemData(val id: Int, val iconRes: Int, val contentDescription: String)

// Callbacks are id-based (not per-item lambdas) so Java keeps one switch-like call site,
// matching how the existing panel Host objects (TaskManagerCallbacks, ScreenPanelCallbacks)
// are implemented anonymously from XServerDisplayActivity.
interface SidebarRailCallbacks {
    fun onSelect(itemId: Int)
    fun onPauseToggle()
    fun onExit()
}

// Exposed to Java so wireSidebarListeners()/openSidebarPanel() can drive selection/pause
// state without reaching back into a native View tree — same shape as TaskManagerPanelState.
class SidebarRailState(initialSelectedId: Int) {
    var selectedId: Int by mutableStateOf(initialSelectedId)
    var paused: Boolean by mutableStateOf(false)
}

// Rendered by IngameSidebar (IngameSidebar.kt) as the left column of the single sidebar
// card; no longer attached to its own ComposeView.
@Composable
internal fun SidebarRail(
    state: SidebarRailState,
    items: List<SidebarRailItemData>,
    callbacks: SidebarRailCallbacks,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            // Same translucent surface token every card/container in the app already
            // uses — composited over the panel's #151515 background this renders as
            // #1F1F1E, matching the rest of the UI without a dedicated color.
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 5.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items.forEach { item ->
            RailNavButton(
                iconRes = item.iconRes,
                contentDescription = item.contentDescription,
                selected = state.selectedId == item.id,
                onClick = { callbacks.onSelect(item.id) }
            )
            Spacer(Modifier.size(10.dp))
        }
        Spacer(Modifier.size(6.dp))
        RailPauseButton(paused = state.paused, onClick = callbacks::onPauseToggle)
        Spacer(Modifier.weight(1f))
        RailExitButton(onClick = callbacks::onExit)
    }
}

// All three rail buttons share one 48dp cell with the medium (14dp) theme radius — the
// exit button used to be 13dp and nav/pause 15dp, neither of which is a theme shape.
@Composable
private fun RailCell(
    selected: Boolean,
    description: String?,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val background by animateColorAsState(
        if (selected) sidebarCardFillColor() else Color.Transparent,
        label = "railCellBackground"
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .clickable(onClick = onClick)
            .semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun RailNavButton(iconRes: Int, contentDescription: String?, selected: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "railItemTint"
    )
    RailCell(selected = selected, description = contentDescription, onClick = onClick) {
        Icon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

// While paused the button keeps a filled cell and an accent play icon, so the paused state
// is visible at a glance instead of only swapping the glyph.
@Composable
private fun RailPauseButton(paused: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(
        if (paused) controlAccentColor() else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "railPauseTint"
    )
    RailCell(
        selected = paused,
        description = if (paused) "Resume" else "Pause",
        onClick = onClick
    ) {
        Icon(
            painterResource(if (paused) R.drawable.icon_play else R.drawable.icon_pause),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun RailExitButton(onClick: () -> Unit) {
    RailCell(selected = false, description = "Exit", onClick = onClick) {
        Icon(
            painterResource(R.drawable.ic_sidebar_power),
            contentDescription = null,
            tint = destructiveColor(),
            modifier = Modifier.size(24.dp)
        )
    }
}
