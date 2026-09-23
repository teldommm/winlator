package com.winlator.cmod.ui

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.controlAccentColor

fun applyAppFullscreen(activity: Activity?) {
    if (activity == null) return

    val window = activity.window
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

// Re-applies the app's fullscreen/immersive window setup while a landscape screen is shown
// (the system can bring the bars back, e.g. after a dialog). This used to also force the native
// Toolbar and bottom nav hidden on every frame; neither exists as a View anymore — MainShell
// owns all chrome — so only the fullscreen part remains.
@Composable
fun KeepLandscapeChromeHidden(activity: MainActivity?) {
    LaunchedEffect(activity) {
        applyAppFullscreen(activity)
        withFrameNanos { }
        applyAppFullscreen(activity)
    }
}

@Composable
fun LandscapeMainNavigation(
    activity: MainActivity?,
    selected: Int,
    title: String,
    modifier: Modifier = Modifier,
    actionIcon: ImageVector? = null,
    actionDescription: String = "Action",
    actionAccent: Boolean = false,
    onAction: (() -> Unit)? = null
) {
    KeepLandscapeChromeHidden(activity)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(start = 20.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.weight(1f))
        if (actionIcon != null && onAction != null) {
            Destination(actionIcon, actionDescription, false, accent = actionAccent, onClick = onAction)
        }
        Destination(Icons.Outlined.Home, "Library", selected == R.id.main_menu_shortcuts) {
            activity?.navigateToMainDestination(R.id.main_menu_shortcuts)
        }
        Destination(Icons.Outlined.SportsEsports, "Input Controls", selected == R.id.main_menu_input_controls) {
            activity?.navigateToMainDestination(R.id.main_menu_input_controls)
        }
        Destination(Icons.Outlined.Settings, "Settings", selected == R.id.main_menu_settings) {
            activity?.navigateToMainDestination(R.id.main_menu_settings)
        }
    }
}

// Portrait counterpart to LandscapeMainNavigation: in portrait the bottom navigation is drawn
// by MainShell, so the screen only needs its title. Screens that fully own their background (Library, and now Settings/Input
// Controls) hide the native Toolbar unconditionally and need a plain in-Compose title instead —
// otherwise the area behind the old Toolbar shows its @drawable/ui_glass_background gradient
// instead of the flat MaterialTheme.colorScheme.background every other screen sits on.
@Composable
fun PortraitMainHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun Destination(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    val normalIcon = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onBackground.copy(alpha = .68f)
    val iconColor = if (accent) Color.White else normalIcon
    val selectedBackground = MaterialTheme.colorScheme.primary.copy(alpha = .12f)
    val background = if (accent) controlAccentColor()
        else if (selected) selectedBackground else Color.Transparent

    Surface(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = background,
        contentColor = iconColor
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(icon, description, modifier = Modifier.size(23.dp))
        }
    }
}
