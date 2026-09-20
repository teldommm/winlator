package com.winlator.cmod.ui.inputcontrols

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R

// Replaces the old controls_editor_activity.xml floating toolbar (a LinearLayout with
// android:background="@drawable/input_controls_toolbar" and four ImageButtons styled
// @style/ToolButton) with an equivalent Compose pill, added directly to FLContainer
// alongside InputControlsView instead of being inflated from XML. Colors/radius/stroke below
// mirror that drawable and style exactly (#99000000 fill, 8dp corners, 2dp #303030 stroke,
// #E7E8EC icon tint, #1F2B3A dividers) so the on-screen result is unchanged.
object ControlsEditorToolbarComposeHost {
    @JvmStatic
    fun create(
        context: Context,
        profileName: String,
        onAdd: Runnable,
        onRemove: Runnable,
        onSettings: Runnable,
        onSchemeColor: Runnable
    ): ComposeView {
        return ComposeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ControlsEditorToolbar(
                    profileName = profileName,
                    onAdd = { onAdd.run() },
                    onRemove = { onRemove.run() },
                    onSettings = { onSettings.run() },
                    onSchemeColor = { onSchemeColor.run() }
                )
            }
        }
    }
}

@Composable
private fun ControlsEditorToolbar(
    profileName: String,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onSettings: () -> Unit,
    onSchemeColor: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0x99000000),
        border = BorderStroke(2.dp, Color(0xFF303030))
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.padding(horizontal = 8.dp)) {
                Text("Profile", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(profileName, color = Color(0xFFE7E8EC), fontSize = 14.sp)
            }
            ToolbarDivider()
            ToolbarIconButton(R.drawable.icon_add, "Add element", onAdd)
            ToolbarIconButton(R.drawable.icon_remove, "Remove element", onRemove)
            ToolbarIconButton(R.drawable.icon_settings, "Element settings", onSettings)
            ToolbarDivider()
            ToolbarIconButton(android.R.drawable.ic_menu_edit, "Scheme color", onSchemeColor)
        }
    }
}

@Composable
private fun ToolbarDivider() {
    Box(
        Modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .width(2.dp)
            .height(28.dp)
            .background(Color(0xFF1F2B3A))
    )
}

@Composable
private fun ToolbarIconButton(iconRes: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = description,
            tint = Color(0xFFE7E8EC)
        )
    }
}
