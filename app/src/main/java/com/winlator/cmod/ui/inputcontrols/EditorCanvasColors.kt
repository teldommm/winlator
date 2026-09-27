package com.winlator.cmod.ui.inputcontrols

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.winlator.cmod.ui.theme.WinlatorThemeManager
import com.winlator.cmod.ui.theme.WinlatorThemeType
import com.winlator.cmod.ui.theme.controlAccentFor
import com.winlator.cmod.ui.theme.destructiveFor
import com.winlator.cmod.ui.theme.winlatorColorScheme

// Colors the controls-editor canvas (InputControlsView in edit mode) paints with, taken from
// WinZTheme instead of the old hardcoded black / #303030 / #424242 / #C62828.
//
// The canvas stands in for the game screen and the controls on it are drawn light (white
// strokes, blue icons), so the White theme deliberately gets the Dark canvas — a white grid
// would make white buttons invisible. The floating toolbar/panel over it still follow White.
class EditorCanvasColors private constructor(
    @JvmField val background: Int,
    @JvmField val gridLine: Int,
    @JvmField val gridCenter: Int,
    @JvmField val cursor: Int,
    @JvmField val guide: Int
) {
    companion object {
        @JvmStatic
        fun resolve(context: Context): EditorCanvasColors {
            val theme = WinlatorThemeManager.currentTheme(context)
            val canvasTheme = if (theme == WinlatorThemeType.WHITE) WinlatorThemeType.BLACK else theme
            val scheme = winlatorColorScheme(canvasTheme)
            return EditorCanvasColors(
                background = scheme.background.toArgb(),
                gridLine = scheme.outlineVariant.toArgb(),
                gridCenter = scheme.outline.toArgb(),
                cursor = destructiveFor(theme).toArgb(),
                guide = controlAccentFor(theme).toArgb()
            )
        }
    }
}
