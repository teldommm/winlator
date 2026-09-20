package com.winlator.cmod.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.controlAccentColor

// Java-friendly callback surface (plain interface, not a Kotlin function type) so
// XServerDisplayActivity.java can implement it with a normal anonymous class, the same
// way it already wires click listeners for every other sidebar item.
interface ScreenPanelCallbacks {
    fun onPipMode()
    fun onToggleFullscreen()
    fun onMagnifier()

    // enabled = the NEW state after the tap; Compose owns the toggle's on/off state,
    // this callback is purely for applying the resulting side effect on the renderer.
    fun onSoftStretch(enabled: Boolean)
}

// Native bridge, same shape as AboutDialogHost: XServerDisplayActivity finds the
// ComposeView already declared in left_sidebar_original.xml (id LLSubScreen) and hands
// it here once, during wireSidebarListeners(). The existing openSidebarPanel()/
// hideAllSidebarPanels() machinery keeps working unchanged since it only ever toggled
// that id's visibility — it doesn't know or care that the id is now a ComposeView.
object ScreenSidebarPanelHost {
    @JvmStatic
    fun attach(composeView: ComposeView, callbacks: ScreenPanelCallbacks) {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            WinZOverlayTheme {
                ScreenSidebarPanel(callbacks)
            }
        }
    }
}

@Composable
private fun ScreenSidebarPanel(callbacks: ScreenPanelCallbacks) {
    // Soft Stretch has no persisted state anywhere else in the app (confirmed: the old
    // Java field it used to live in wasn't read by anything but this same click), so it's
    // safe for Compose to own this bit of UI state outright rather than round-tripping it
    // through Java on every recomposition.
    var softStretchEnabled by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "Display and Effects",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(18.dp))
        ScreenPanelRow(label = "Picture in Picture", onClick = callbacks::onPipMode)
        Spacer(Modifier.height(10.dp))
        ScreenPanelRow(label = "Toggle Fullscreen", onClick = callbacks::onToggleFullscreen)
        Spacer(Modifier.height(10.dp))
        ScreenPanelRow(label = "Magnifier", onClick = callbacks::onMagnifier)
        Spacer(Modifier.height(10.dp))
        ScreenPanelRow(
            label = "Soft Stretch",
            selected = softStretchEnabled,
            onClick = {
                softStretchEnabled = !softStretchEnabled
                callbacks.onSoftStretch(softStretchEnabled)
            }
        )
    }
}

@Composable
private fun ScreenPanelRow(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val accent = controlAccentColor()
    val shape = RoundedCornerShape(18.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .let {
                if (selected) it.border(BorderStroke(1.5.dp, accent), shape) else it
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (selected) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "ON",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
    }
}
