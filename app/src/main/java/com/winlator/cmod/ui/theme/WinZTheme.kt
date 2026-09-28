package com.winlator.cmod.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.winlator.cmod.R

enum class WinlatorThemeType(
    val id: String,
    val displayName: String,
    val description: String
) {
    WHITE("white", "White", "Bright surfaces with dark text"),
    BLACK("black", "Dark", "Balanced dark theme · Default"),
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
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    // Tokens below were unset, so Material3 filled them from its purple baseline palette
    // (menus/app bars/dialogs with no explicit color picked those up). Same neutral ramp.
    tertiary = Color(0xFFC3C2B7), onTertiary = Color(0xFF151515),
    tertiaryContainer = Color(0xFF242423), onTertiaryContainer = Color(0xFFC3C2B7),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFF9F9F7), inverseOnSurface = Color(0xFF151515), inversePrimary = Color(0xFF313130),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFF151515), surfaceBright = Color(0xFF313130),
    surfaceContainerLowest = Color(0xFF151515), surfaceContainerLow = Color(0xFF1B1B1A),
    surfaceContainer = Color(0xFF20201F), surfaceContainerHigh = Color(0xFF242423),
    surfaceContainerHighest = Color(0xFF313130)
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

// Highlight for the in-game sidebar rail (selected section, the Pause / Exit cell). Dark keeps
// its card fill. White and AMOLED used colorScheme.surface for that fill, which is the very
// colour the rail itself is painted with, so the selection was invisible there. They now get a
// neutral onSurface tint instead: a light grey on White, a dark grey on AMOLED.
@Composable
fun sidebarRailHighlightColor(): Color = when (WinlatorThemeManager.currentTheme()) {
    WinlatorThemeType.BLACK -> sidebarCardFillColor()
    WinlatorThemeType.WHITE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
}

// One border and one divider color for the whole app (borders used to be drawn at nine
// different outlineVariant alphas, list dividers at six). Card/field/dialog outlines use
// hairlineColor(); separators between rows inside a card or list use dividerColor().
@Composable
fun hairlineColor(): Color = MaterialTheme.colorScheme.outlineVariant

@Composable
fun dividerColor(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

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

// Title row for every dialog: the title, then the same 1dp dividerColor() line as under
// "Version" in About, which separates the heading from the dialog's content. Emits into the
// caller's Column (ThemedDialogSurface / ThemedDialog content), so no Spacer is needed after it.
@Composable
fun ThemedDialogTitle(
    text: String,
    leading: (@Composable () -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = dividerColor())
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
        shape = WinZShapes.Large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
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
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    tertiary = Color(0xFFD0D0D0), onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF101010), onTertiaryContainer = Color(0xFFECECEC),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFF7F7F7), inverseOnSurface = Color.Black, inversePrimary = Color(0xFF191919),
    surfaceTint = Color.Transparent,
    surfaceDim = Color.Black, surfaceBright = Color(0xFF191919),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF050505),
    surfaceContainer = Color(0xFF0A0A0A), surfaceContainerHigh = Color(0xFF0D0D0D),
    surfaceContainerHighest = Color(0xFF191919)
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
    error = Color(0xFFBA1A1A), onError = Color.White,
    tertiary = Color(0xFF555861), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE7E8EC), onTertiaryContainer = Color(0xFF26282E),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF2E3036), inverseOnSurface = Color(0xFFF1F1F4), inversePrimary = Color(0xFFE1E3E8),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFFE1E3E8), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFF0F1F4), surfaceContainerHigh = Color(0xFFE8E9ED),
    surfaceContainerHighest = Color(0xFFE1E3E8)
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
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    // The styles below used to be undefined here, so they silently fell back to Material3's
    // defaults (different font family object, extra letter-spacing up to 0.5sp) and read
    // slightly off next to the rest of the text. Same family/zero tracking as everything above.
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-0.8).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-0.7).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp)
)

// The app's only corner radii. Every screen uses these instead of ad-hoc
// RoundedCornerShape(N.dp) values (there used to be 14 different radii in use). Plain vals so
// they also work outside @Composable scope; MaterialTheme.shapes points at the same objects.
//  ExtraSmall  6dp — tiny swatches/badges
//  Small      10dp — icon tiles, small chips, inner tiles
//  Medium     14dp — cards, rows, fields, buttons, menus
//  Large      18dp — big cards, panels, dialogs
//  ExtraLarge 22dp — floating bars and hero cards
object WinZShapes {
    val ExtraSmall = RoundedCornerShape(6.dp)
    val Small = RoundedCornerShape(10.dp)
    val Medium = RoundedCornerShape(14.dp)
    val Large = RoundedCornerShape(18.dp)
    val ExtraLarge = RoundedCornerShape(22.dp)
}

private val WinlatorShapes = Shapes(
    extraSmall = WinZShapes.ExtraSmall, small = WinZShapes.Small,
    medium = WinZShapes.Medium, large = WinZShapes.Large,
    extraLarge = WinZShapes.ExtraLarge
)

@Composable
fun WinlatorTheme(content: @Composable () -> Unit) {
    val theme = WinlatorThemeManager.currentTheme()
    val colors = winlatorColorScheme(theme)
    ConfigureComposeHostFocus()
    HideSystemBars(theme)
    ApplyWindowBackground(colors)
    MaterialTheme(colorScheme = colors, typography = WinlatorTypography, shapes = WinlatorShapes) {
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        ClearFocusWhenKeyboardHides(focusManager)
        Surface(
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                })
            },
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            content()
        }
    }
}

// A text field's cursor/label keep animating for as long as Compose thinks it's focused.
// Tapping a Button/Surface elsewhere already clears focus via the pointerInput above, but
// dismissing the keyboard some other way (system back button, swipe-down gesture) hides the
// IME without Compose ever being told to drop focus from the field that opened it — so the
// field is left blinking with no keyboard on screen. Watching the IME's own visibility and
// clearing focus the moment it goes away closes that gap everywhere at once.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClearFocusWhenKeyboardHides(focusManager: FocusManager) {
    val imeVisible = WindowInsets.isImeVisible
    var wasVisible by remember { mutableStateOf(imeVisible) }
    LaunchedEffect(imeVisible) {
        if (wasVisible && !imeVisible) focusManager.clearFocus()
        wasVisible = imeVisible
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
            window.setLegacySystemBarColors(colors.background.toArgb())
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

// Paints the window and MainActivity's root with the theme background, so nothing but the
// theme color can show before/around Compose content (e.g. during a theme switch). The native
// Toolbar/FLFragmentContainer/NavigationView tinting that used to live here is gone with those
// views.
@Composable
private fun ApplyWindowBackground(colors: ColorScheme) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, colors) {
        activity?.let { host ->
            val background = colors.background.toArgb()
            host.window.decorView.setBackgroundColor(background)
            host.findViewById<View>(R.id.DrawerLayout)?.setBackgroundColor(background)
        }
        onDispose { }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// Bar colours only matter below API 35 (and only while the hidden bars are swiped in
// transiently); on 35+ edge-to-edge is enforced and these setters are no-ops, hence deprecated.
// Kept for older devices; the deprecation is acknowledged here, in one place.
@Suppress("DEPRECATION")
internal fun android.view.Window.setLegacySystemBarColors(color: Int) {
    statusBarColor = color
    navigationBarColor = color
}
