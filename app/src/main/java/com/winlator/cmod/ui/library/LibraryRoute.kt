package com.winlator.cmod.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.theme.findActivity

// Library as a plain composable tab of MainShell (replaces ShortcutsFragment). The logic is in
// LibraryScreenController; this route only wires it to the composition:
//  - the icon picker launcher (was Fragment.registerForActivityResult);
//  - reload on every resume (was onViewCreated + onResume — back from a game, from the
//    Containers/Components activities, …);
//  - reload when MainShell re-shows the tab or asks for a refresh (shownSerial), e.g. after
//    File Manager added a game or GameDetail edited a shortcut.
@Composable
fun LibraryRoute(shownSerial: Int) {
    val activity = LocalContext.current.findActivity() as AppCompatActivity
    val controller = remember { LibraryScreenController(activity) }

    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        controller.onIconPicked(uri)
    }
    SideEffect { controller.setIconPickerLauncher { pickIcon.launch("image/*") } }

    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    LifecycleResumeEffect(controller) {
        controller.loadShortcutsList()
        onPauseOrDispose { }
    }
    LaunchedEffect(shownSerial) {
        if (shownSerial > 0) controller.loadShortcutsList()
    }

    WinZTheme {
        LibraryContent(controller.libraryController, controller.callbacks)
    }
}
