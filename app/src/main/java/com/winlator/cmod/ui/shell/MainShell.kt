package com.winlator.cmod.ui.shell

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.winlator.cmod.R
import com.winlator.cmod.ui.PortraitBottomNavigation
import com.winlator.cmod.ui.filemanager.FileManagerRoute
import com.winlator.cmod.ui.inputcontrols.InputControlsRoute
import com.winlator.cmod.ui.library.LibraryRoute
import com.winlator.cmod.ui.settings.SettingsRoute
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// MainShell — the single Compose root of MainActivity's main screens.
//
// Before: MainActivity.show() did FragmentTransaction.replace() into FLFragmentContainer with a
// full-height slide-up/slide-down, so every tab switch destroyed the previous screen (lost
// scroll, re-ran its disk loading on the main thread mid-animation) and moved the whole screen
// vertically, which reads as "a modal opened", not "switched tab". The portrait nav lived in
// its own ComposeView inside a native FrameLayout, and chrome visibility was toggled by hand
// from ~10 call sites.
//
// Now:
//  - Every tab (Library, Input Controls, Settings, File Manager) is a plain composable route,
//    composed on first visit and then kept alive. Switching is a fade-through driven here.
//    No tab is a Fragment anymore.
//  - The portrait bottom nav is part of this composition; its visibility is derived
//    (portrait && no detail screen open) instead of being set by each screen.
//  - Detail screens (GameDetail, Containers, container editor, Components) are a stack of
//    composables drawn above the tabs (ShellDetails.kt). There are no Fragments left in
//    MainActivity.
//  - System back is handled here (BackHandler): pop a detail, else return to Library, else
//    fall through to the activity (finish, or return to the game in edit-controls mode).
//  - A tab that needs to refresh when it's shown again reacts to shownSerial (see TabContent).

// SAM interface so MainActivity.java can pass this::navigateToMainDestination.
fun interface MainShellListener {
    fun onNavigate(menuItemId: Int)
}

class MainShellController internal constructor(
    initialTab: Int,
    internal val inputControlsProfileId: Int,
    // Opened from a game to edit its controls: back from Input Controls must leave the
    // activity (return to the game), not switch to Library.
    internal val editControlsMode: Boolean
) {
    var selectedTab by mutableIntStateOf(initialTab)
        private set

    internal val detailEntries = mutableStateListOf<DetailEntry>()
    private var nextDetailId = 0L

    // True while any detail screen is open (not counting ones already animating out).
    val detailActive: Boolean
        get() = detailEntries.any { it.active }

    internal val visitedTabs = mutableStateListOf(initialTab)

    // Tabs that have been shown at least once. The first showing is covered by the route's own
    // initial load, so shownSerial is only bumped on later returns.
    internal val shownOnce = HashSet<Int>()

    // Bumped each time a tab is re-shown (after its fade-in) or explicitly asked to refresh;
    // the tab's route reacts with LaunchedEffect(serial).
    internal val shownSerial = mutableStateMapOf<Int, Int>()

    fun pushDetail(detail: ShellDetail) {
        detailEntries.add(DetailEntry(detail, nextDetailId++))
    }

    // Pops the top open detail; false if none is open.
    fun popDetail(): Boolean {
        val top = detailEntries.lastOrNull { it.active } ?: return false
        top.transition.targetState = false
        return true
    }

    fun closeAllDetails() {
        detailEntries.forEach { it.transition.targetState = false }
    }

    internal val backEnabled: Boolean
        get() = detailActive || (!editControlsMode && selectedTab != R.id.main_menu_shortcuts)

    internal fun onBack() {
        if (popDetail()) return
        select(R.id.main_menu_shortcuts)
    }

    // Ask a tab to reload now, e.g. the Library after a detail screen edited a shortcut.
    fun requestRefresh(tabId: Int) {
        if (tabId in visitedTabs) shownSerial[tabId] = (shownSerial[tabId] ?: 0) + 1
    }

    fun select(tabId: Int) {
        if (!isTab(tabId)) return
        if (tabId !in visitedTabs) visitedTabs.add(tabId)
        selectedTab = tabId
    }

    companion object {
        @JvmStatic
        fun isTab(menuItemId: Int): Boolean =
            menuItemId == R.id.main_menu_shortcuts ||
                menuItemId == R.id.main_menu_input_controls ||
                menuItemId == R.id.main_menu_settings ||
                menuItemId == R.id.main_menu_file_manager
    }
}

object MainShellHost {
    @JvmStatic
    fun attach(
        composeView: ComposeView,
        initialTab: Int,
        inputControlsProfileId: Int,
        editControlsMode: Boolean,
        listener: MainShellListener
    ): MainShellController {
        val controller = MainShellController(
            if (MainShellController.isTab(initialTab)) initialTab else R.id.main_menu_shortcuts,
            inputControlsProfileId,
            editControlsMode
        )
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent { MainShell(controller, listener) }
        return controller
    }
}

// Fade-through (Material motion for switching between peer destinations): the outgoing tab
// fades out quickly, then the incoming one fades in while settling from a slight scale-down.
// Nothing travels across the screen, so the shell's own chrome stays still.
private const val EXIT_MS = 90
private const val ENTER_MS = 210
private const val ENTER_SCALE = 0.96f

@Composable
private fun MainShell(controller: MainShellController, listener: MainShellListener) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    BackHandler(enabled = controller.backEnabled) { controller.onBack() }

    LaunchedEffect(controller.selectedTab) {
        val tab = controller.selectedTab
        if (controller.shownOnce.add(tab)) return@LaunchedEffect
        delay((EXIT_MS + ENTER_MS).toLong())
        controller.shownSerial[tab] = (controller.shownSerial[tab] ?: 0) + 1
    }

    Box(Modifier.fillMaxSize()) {
        controller.visitedTabs.forEach { tab ->
            key(tab) { ShellTab(controller, tab) }
        }

        AnimatedVisibility(
            visible = !landscape && !controller.detailActive,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(2f)
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            enter = fadeIn(tween(ENTER_MS)) + slideInVertically(tween(ENTER_MS)) { it / 2 },
            exit = fadeOut(tween(EXIT_MS)) + slideOutVertically(tween(EXIT_MS)) { it / 2 }
        ) {
            WinZOverlayTheme {
                PortraitBottomNavigation(
                    selected = controller.selectedTab,
                    onNavigate = { listener.onNavigate(it) }
                )
            }
        }

        Box(Modifier.fillMaxSize().zIndex(3f)) {
            DetailLayer(controller)
        }
    }
}

@Composable
private fun ShellTab(controller: MainShellController, tab: Int) {
    val selected = controller.selectedTab == tab

    // Only the very first tab of the session starts fully visible. Every tab composed later is
    // composed *because* it was just selected, and must fade in like any other switch — which
    // animateFloatAsState can't do (it starts at its first target), hence Animatable.
    val initiallyShown = remember { selected && controller.visitedTabs.size == 1 }
    val alpha = remember { Animatable(if (initiallyShown) 1f else 0f) }
    val scale = remember { Animatable(if (initiallyShown) 1f else ENTER_SCALE) }

    LaunchedEffect(selected) {
        if (selected) {
            // Wait for the outgoing tab to fade out, then fade in while settling to full size.
            delay(EXIT_MS.toLong())
            launch { alpha.animateTo(1f, tween(ENTER_MS, easing = LinearOutSlowInEasing)) }
            scale.animateTo(1f, tween(ENTER_MS, easing = FastOutSlowInEasing))
        } else {
            alpha.animateTo(0f, tween(EXIT_MS, easing = FastOutLinearInEasing))
            // Reset for the next entrance only once it's invisible.
            scale.snapTo(ENTER_SCALE)
        }
    }

    // Out of reach while faded out, or while a detail screen covers the tabs.
    val hidden by remember {
        derivedStateOf { (controller.selectedTab != tab && alpha.value == 0f) || controller.detailActive }
    }

    Box(
        Modifier
            .fillMaxSize()
            .zIndex(if (selected) 1f else 0f)
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            }
            // A fully faded-out tab can't take focus (D-pad/gamepad traversal would otherwise
            // walk into an invisible screen) and exposes no accessibility nodes.
            .then(
                if (hidden) {
                    Modifier
                        .clearAndSetSemantics { }
                        .focusProperties { onEnter = { cancelFocusChange() } }
                        .focusGroup()
                } else {
                    Modifier
                }
            )
    ) {
        TabContent(controller, tab)
    }
}

@Composable
private fun TabContent(controller: MainShellController, tab: Int) {
    val serial = controller.shownSerial[tab] ?: 0
    when (tab) {
        R.id.main_menu_shortcuts -> LibraryRoute(shownSerial = serial)
        R.id.main_menu_input_controls -> InputControlsRoute(
            initialProfileId = controller.inputControlsProfileId,
            shownSerial = serial
        )
        R.id.main_menu_settings -> SettingsRoute(shownSerial = serial)
        R.id.main_menu_file_manager -> FileManagerRoute(shownSerial = serial)
    }
}
