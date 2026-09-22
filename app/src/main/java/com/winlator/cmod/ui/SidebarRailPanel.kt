package com.winlator.cmod.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.destructiveColor
import com.winlator.cmod.ui.theme.sidebarCardFillColor

// One selectable rail entry (Graphics/Screen/Input/FPS/TaskManager). `id` reuses the
// panel's own R.id.LLSubXxx ComposeView id as its identity, so there's no separate
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

object SidebarRailHost {
    @JvmStatic
    fun attach(
        composeView: ComposeView,
        items: List<SidebarRailItemData>,
        initialSelectedId: Int,
        callbacks: SidebarRailCallbacks
    ): SidebarRailState {
        val state = SidebarRailState(initialSelectedId)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            WinZOverlayTheme {
                SidebarRail(state, items, callbacks)
            }
        }
        return state
    }
}

@Composable
private fun SidebarRail(
    state: SidebarRailState,
    items: List<SidebarRailItemData>,
    callbacks: SidebarRailCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
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

@Composable
private fun RailNavButton(iconRes: Int, contentDescription: String?, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        if (selected) sidebarCardFillColor() else Color.Transparent,
        label = "railItemBackground"
    )
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "railItemTint"
    )
    Column(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(background)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(painterResource(iconRes), contentDescription, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun RailPauseButton(paused: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(15.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painterResource(if (paused) R.drawable.icon_play else R.drawable.icon_pause),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun RailExitButton(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_sidebar_power),
            contentDescription = null,
            tint = destructiveColor(),
            modifier = Modifier.size(24.dp)
        )
    }
}
