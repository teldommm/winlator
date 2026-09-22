package com.winlator.cmod.ui.theme

import android.R as AndroidR
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.google.android.material.navigation.NavigationView
import com.winlator.cmod.R

enum class WinlatorThemeType(
    val id: String,
    val displayName: String,
    val description: String
) {
    WHITE("white", "White", "Bright surfaces with dark text"),
    BLACK("black", "Black", "Balanced dark theme · Default"),
    AMOLED("amoled", "AMOLED", "Pure black background for OLED displays");

    companion object {
        fun fromId(id: String?): WinlatorThemeType = values().firstOrNull { it.id == id } ?: BLACK
    }
}

object WinlatorThemeManager {
    private const val PREF_KEY = "winlator_ui_theme"
    private val currentState = mutableStateOf(WinlatorThemeType.BLACK)
    private var initialized = false

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        currentState.value = WinlatorThemeType.fromId(prefs.getString(PREF_KEY, WinlatorThemeType.BLACK.id))
        initialized = true
    }

    @Composable
    fun currentTheme(): WinlatorThemeType {
        val context = LocalContext.current
        ensureInitialized(context)
        return currentState.value
    }

    fun currentTheme(context: Context): WinlatorThemeType {
        ensureInitialized(context)
        return currentState.value
    }

    fun setTheme(context: Context, theme: WinlatorThemeType) {
        ensureInitialized(context)
        if (currentState.value == theme) return
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .putString(PREF_KEY, theme.id)
            .apply()
        currentState.value = theme
    }
}

// Tuned to match claude.ai's own dark theme (extracted from the app's resources.arsc,
// night config: bg100/bg300/text100/text300/text500/oat/claude_widget_tile).
private val BlackColors = darkColorScheme(
    primary = Color(0xFFF9F9F7), onPrimary = Color(0xFF151515),
    primaryContainer = Color(0xFF242423), onPrimaryContainer = Color(0xFFF9F9F7),
    secondary = Color(0xFFC3C2B7), onSecondary = Color(0xFF151515),
    secondaryContainer = Color(0xFF242423), onSecondaryContainer = Color(0xFFC3C2B7),
    background = Color(0xFF151515), onBackground = Color(0xFFF9F9F7),
    // Cards float slightly translucent over the background (~90% opacity), per request.
    surface = Color(0xE620201F), onSurface = Color(0xFFF9F9F7),
    surfaceVariant = Color(0xFF313130), onSurfaceVariant = Color(0xFF97958D),
    outline = Color(0xFF3D3D3A), outlineVariant = Color(0xFF242423),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)

// Dedicated accent for Switch/Slider/button controls — intentionally NOT wired into
// `primary`, so it reads as one consistent brand blue across all three themes rather
// than each theme's own neutral primary.
private val ControlAccent = Color(0xFF3B82F6)

internal fun controlAccentFor(theme: WinlatorThemeType): Color = ControlAccent

@Composable
fun controlAccentColor(): Color = controlAccentFor(WinlatorThemeManager.currentTheme())

// Danger/destructive red for Remove/Delete-style confirm buttons and the components manager's
// Delete/In-use rows. Same red across all three themes, same treatment as controlAccentColor above.
private val DestructiveRed = Color(0xFFD03B3B)

internal fun destructiveFor(theme: WinlatorThemeType): Color = DestructiveRed

@Composable
fun destructiveColor(): Color = destructiveFor(WinlatorThemeManager.currentTheme())

// Sidebar's bordered cards/rows (FPS Limit, Enable HUD, ReShade, TaskManager rows, etc.
// — anything using the PanelCard/PanelActionRow/MetricCard/ProcessRow/ScreenPanelRow
// shape) and the rail's selected-item highlight reuse the Black theme's own
// surfaceVariant token (#313130) instead of colorScheme.surface/primaryContainer, per
// request — no new color introduced. Alpha is knocked back to the same ~90% every other
// translucent sidebar surface uses (colorScheme.surface itself), restoring the
// translucency this had before it was briefly made fully opaque. White/AMOLED keep
// colorScheme.surface as before (already translucent on its own).
@Composable
fun sidebarCardFillColor(): Color =
    if (WinlatorThemeManager.currentTheme() == WinlatorThemeType.BLACK) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.surface
    }

// Shared Switch styling for the whole app — matches claude.ai's own switch look:
// no visible border/outline when off, thumb stays white in both states (never gray).
@Composable
fun accentSwitchColors(): SwitchColors {
    val accent = controlAccentColor()
    val trackOff = MaterialTheme.colorScheme.surfaceVariant
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = accent,
        checkedBorderColor = accent,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = trackOff,
        uncheckedBorderColor = trackOff
    )
}

// Shared dialog shell for the whole app: same transparent surface + outline border as
// every unified list/card (GroupCard, the Box64/FEX preset sheet, etc). Use this instead
// of the stock Material3 AlertDialog, which defaults to a different (opaque, borderless)
// surfaceContainerHigh look that doesn't match the rest of the app.
@Composable
fun ThemedDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        ThemedDialogSurface(modifier, content)
    }
}

// Same card look as ThemedDialog, without the Compose Dialog wrapper — for call sites
// that are already hosted inside a native android.app.Dialog (see ThemedAlertHost).
@Composable
fun ThemedDialogSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.widthIn(min = 280.dp, max = 420.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

private val AmoledColors = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color(0xFF191919), onPrimaryContainer = Color.White,
    secondary = Color(0xFFD0D0D0), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF101010), onSecondaryContainer = Color(0xFFECECEC),
    background = Color.Black, onBackground = Color(0xFFF7F7F7),
    // Same ~90% translucent-surface treatment as Black, over a pure black background.
    surface = Color(0xE6050505), onSurface = Color(0xFFF7F7F7),
    surfaceVariant = Color(0xFF0D0D0D), onSurfaceVariant = Color(0xFFAAAAAA),
    outline = Color(0xFF383838), outlineVariant = Color(0xFF202020),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005)
)

private val WhiteColors = lightColorScheme(
    primary = Color(0xFF23252B), onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E3E8), onPrimaryContainer = Color(0xFF17191E),
    secondary = Color(0xFF555861), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E8EC), onSecondaryContainer = Color(0xFF26282E),
    background = Color(0xFFF5F6F8), onBackground = Color(0xFF18191D),
    // Same ~90% translucent-surface treatment as Black/Amoled, over the light background.
    surface = Color(0xE6FFFFFF), onSurface = Color(0xFF18191D),
    surfaceVariant = Color(0xFFE8E9ED), onSurfaceVariant = Color(0xFF60636B),
    outline = Color(0xFF92959D), outlineVariant = Color(0xFFD1D3D8),
    error = Color(0xFFBA1A1A), onError = Color.White
)

internal fun winlatorColorScheme(theme: WinlatorThemeType): ColorScheme = when (theme) {
    WinlatorThemeType.WHITE -> WhiteColors
    WinlatorThemeType.BLACK -> BlackColors
    WinlatorThemeType.AMOLED -> AmoledColors
}

private val WinlatorTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-0.6).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
)

private val WinlatorShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

@Composable
fun WinlatorTheme(content: @Composable () -> Unit) {
    val theme = WinlatorThemeManager.currentTheme()
    val colors = winlatorColorScheme(theme)
    ConfigureComposeHostFocus()
    HideSystemBars(theme)
    ApplyLegacyChrome(colors)
    MaterialTheme(colorScheme = colors, typography = WinlatorTypography, shapes = WinlatorShapes) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            content()
        }
    }
}

@Composable
fun WinZTheme(content: @Composable () -> Unit) = WinlatorTheme(content)

// Lightweight variant for floating overlays added directly into an existing screen
// (see AboutDialogHost / ThemedAlertHost): gives the same color scheme/typography/shapes
// as WinZTheme, but skips the opaque full-screen Surface, system-bar and focus setup that
// WinlatorTheme applies for full-screen hosts — those would paint over/hide the screen
// the overlay is meant to float on top of.
@Composable
fun WinZOverlayTheme(content: @Composable () -> Unit) {
    val theme = WinlatorThemeManager.currentTheme()
    val colors = winlatorColorScheme(theme)
    MaterialTheme(colorScheme = colors, typography = WinlatorTypography, shapes = WinlatorShapes) {
        content()
    }
}

@Composable
private fun ConfigureComposeHostFocus() {
    val owner = LocalView.current
    DisposableEffect(owner) {
        val previousHighlight = owner.defaultFocusHighlightEnabled
        owner.defaultFocusHighlightEnabled = false
        val host = owner.parent as? AbstractComposeView
        val previousHostFocusable = host?.focusable
        val previousHostTouchFocus = host?.isFocusableInTouchMode
        val previousHostHighlight = host?.defaultFocusHighlightEnabled
        val previousDescendantFocus = host?.descendantFocusability
        val previousAccessibility = host?.importantForAccessibility
        host?.apply {
            isFocusableInTouchMode = false
            isFocusable = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            defaultFocusHighlightEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        onDispose {
            owner.defaultFocusHighlightEnabled = previousHighlight
            host?.apply {
                isFocusableInTouchMode = previousHostTouchFocus!!
                focusable = previousHostFocusable!!
                descendantFocusability = previousDescendantFocus!!
                defaultFocusHighlightEnabled = previousHostHighlight!!
                importantForAccessibility = previousAccessibility!!
            }
        }
    }
}

@Composable
private fun HideSystemBars(theme: WinlatorThemeType) {
    val activity = LocalContext.current.findActivity()
    val colors = winlatorColorScheme(theme)
    DisposableEffect(activity, theme) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            val light = theme == WinlatorThemeType.WHITE
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
        onDispose { }
    }
}

@Composable
private fun ApplyLegacyChrome(colors: ColorScheme) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, colors) {
        activity?.let { host ->
            val background = colors.background.toArgb()
            val surface = colors.surface.toArgb()
            val onSurface = colors.onSurface.toArgb()

            host.window.decorView.setBackgroundColor(background)
            host.findViewById<View>(R.id.DrawerLayout)?.let { rootLayout ->
                rootLayout.setBackgroundColor(background)
                if (rootLayout is android.view.ViewGroup && rootLayout.childCount > 0) {
                    rootLayout.getChildAt(0)?.setBackgroundColor(background)
                }
            }
            host.findViewById<View>(R.id.FLFragmentContainer)?.setBackgroundColor(background)

            val toolbar = host.findViewById<Toolbar>(R.id.Toolbar)
            toolbar?.setBackgroundColor(surface)
            toolbar?.setTitleTextColor(onSurface)
            toolbar?.navigationIcon = toolbar?.navigationIcon?.mutate()?.apply { setTint(onSurface) }
            toolbar?.menu?.let { menu ->
                for (i in 0 until menu.size()) {
                    menu.getItem(i).icon?.mutate()?.setTint(onSurface)
                }
            }

            host.findViewById<NavigationView>(R.id.NavigationView)?.let { drawer ->
                val selectedStates = arrayOf(
                    intArrayOf(AndroidR.attr.state_checked),
                    intArrayOf()
                )
                val navColors = intArrayOf(colors.primary.toArgb(), colors.onSurfaceVariant.toArgb())
                val tint = ColorStateList(selectedStates, navColors)
                drawer.setBackgroundColor(surface)
                drawer.itemIconTintList = tint
                drawer.itemTextColor = ColorStateList.valueOf(onSurface)
            }
        }
        onDispose { }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
