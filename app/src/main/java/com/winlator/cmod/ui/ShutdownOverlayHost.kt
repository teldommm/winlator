package com.winlator.cmod.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.ThemedDialogSurface
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.theme.controlAccentColor

// Themed replacement for PreloaderDialog's "standard preloader" mode as used by
// XServerDisplayActivity.exit() (R.string.shutdown) — a full-screen scrim with an
// indeterminate spinner + message card, not cancelable (there's nothing to cancel:
// the container is already being torn down by the time this shows).
//
// PreloaderDialog itself is left alone: it's still the launch-screen (R.string.starting_up,
// with game artwork) and the generic loading overlay for MainActivity/SettingsFragment/
// InputControlsFragment (downloading_file/loading), none of which this port touches.
object ShutdownOverlayHost {
    @JvmStatic
    fun show(activity: Activity, message: String): View {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        ThemedDialogSurface {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = controlAccentColor(),
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    strokeWidth = 3.dp
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
        root.addView(
            composeView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        return composeView
    }

    @JvmStatic
    fun dismiss(view: View?) {
        if (view == null) return
        (view.parent as? ViewGroup)?.removeView(view)
    }
}
