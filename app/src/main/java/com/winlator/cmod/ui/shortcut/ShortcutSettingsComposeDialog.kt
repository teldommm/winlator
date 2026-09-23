package com.winlator.cmod.ui.shortcut

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.appcompat.app.AppCompatActivity
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.ui.theme.WinZTheme

object ShortcutSettingsComposeDialog {
    // onShortcutsChanged: called after an edit that changes what the Library shows (rename,
    // copy to another container) — the Library reloads, GameDetail asks MainActivity to.
    @JvmStatic
    fun show(activity: AppCompatActivity, shortcut: Shortcut, onShortcutsChanged: Runnable) {
        val dialog = ComponentDialog(activity)
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
            decorView.setPadding(0, 0, 0, 0)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            decorView.systemUiVisibility = immersiveUiFlagsV2()
        }
        dialog.setContentView(ComposeView(activity).apply {
            setContent { WinZTheme { ShortcutEditorV2(activity, shortcut, { onShortcutsChanged.run() }, dialog::dismiss) } }
        })
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

private fun immersiveUiFlagsV2() = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
