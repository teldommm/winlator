package com.winlator.cmod.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R

// Portrait bottom navigation (Library / Input Controls / Settings). Rendered by MainShell
// (ui/shell/MainShell.kt) as a floating bar over the tab content; it no longer has its own
// ComposeView or native container (the WinZBottomNavigationView FrameLayout and the
// PortraitBottomNavigationHost bridge are gone). Destinations are the same main_menu_* ids
// MainActivity.navigateToMainDestination() routes.
//
// Selection now animates (pill fill + tint, 180ms — in step with the tab fade-through in
// MainShell instead of snapping while the content is still moving), and a press gives a
// small scale-down so a tap is acknowledged immediately.
@Composable
internal fun PortraitBottomNavigation(
    selected: Int,
    onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(25.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PortraitNavItem(Icons.Outlined.Home, "Library", selected == R.id.main_menu_shortcuts) {
                onNavigate(R.id.main_menu_shortcuts)
            }
            PortraitNavItem(Icons.Outlined.SportsEsports, "Input Controls", selected == R.id.main_menu_input_controls) {
                onNavigate(R.id.main_menu_input_controls)
            }
            PortraitNavItem(Icons.Outlined.Settings, "Settings", selected == R.id.main_menu_settings) {
                onNavigate(R.id.main_menu_settings)
            }
        }
    }
}

private const val NAV_SELECTION_MS = 180

@Composable
private fun RowScope.PortraitNavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onBackground.copy(alpha = .68f),
        animationSpec = tween(NAV_SELECTION_MS),
        label = "navTint"
    )
    val pillColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent,
        animationSpec = tween(NAV_SELECTION_MS),
        label = "navPill"
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(100), label = "navPress")

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = pillColor,
            contentColor = tint,
            modifier = Modifier
                .height(28.dp)
                .width(52.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, label, modifier = Modifier.height(20.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}
