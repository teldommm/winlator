package com.winlator.cmod.ui

import android.content.Context
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZOverlayTheme

// Compose replacement for the old native WinZBottomNavigationView (was a BottomNavigationView
// with the now-deleted ui_bottom_nav_shell/ui_bottom_nav_selected drawables). Lives inside a
// plain FrameLayout container (see WinZBottomNavigationView, now just an orientation-visibility
// helper) placed directly in main_activity.xml, same spot/margins as before. Reuses the exact
// main_menu_* destination ids MainActivity already routes through navigateToMainDestination, so
// no separate bottom_nav_* menu ids are needed anymore.
@Composable
private fun PortraitBottomNavigation(selected: Int, onNavigate: (Int) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(64.dp),
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

@Composable
private fun RowScope.PortraitNavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = .68f)
    val pillColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = pillColor, contentColor = tint, modifier = Modifier.height(28.dp).width(52.dp)) {
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

// Plain SAM interface (not a raw Kotlin (Int) -> Unit) so MainActivity.java can pass a bare
// void method reference (this::navigateToMainDestination) without Unit-boxing ambiguity.
fun interface PortraitNavListener {
    fun onNavigate(menuItemId: Int)
}

object PortraitBottomNavigationHost {
    @JvmStatic
    fun create(context: Context, listener: PortraitNavListener): ComposeView {
        val selectedState = mutableStateOf(R.id.main_menu_shortcuts)
        return ComposeView(context).apply {
            tag = selectedState
            setContent {
                WinZOverlayTheme {
                    PortraitBottomNavigation(selectedState.value) { listener.onNavigate(it) }
                }
            }
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun updateSelected(view: View?, menuItemId: Int) {
        (view?.tag as? MutableState<Int>)?.value = menuItemId
    }
}
