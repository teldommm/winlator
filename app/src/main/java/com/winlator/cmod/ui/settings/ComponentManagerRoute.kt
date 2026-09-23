package com.winlator.cmod.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.winlator.cmod.ComponentCatalogController
import com.winlator.cmod.core.WineInfo
import com.winlator.cmod.core.WineRuntimeGuard
import com.winlator.cmod.ui.onboarding.OnboardingComposeHost
import com.winlator.cmod.ui.theme.findActivity

// Components (catalog/install/remove) as a MainShell detail entry (replaces
// ComponentManagerFragment). The logic is ComponentCatalogController, shared with
// OnboardingActivity's first-run flow; this route is its Host.
//
// The UI is OnboardingComposeHost's flow, which is built around attaching to a ComposeView, so
// it's embedded through AndroidView rather than duplicated. The local-file pickers moved from
// Fragment.registerForActivityResult to rememberLauncherForActivityResult.
@Composable
fun ComponentManagerRoute(onClose: () -> Unit) {
    val activity = LocalContext.current.findActivity() as AppCompatActivity
    val currentOnClose by rememberUpdatedState(onClose)
    val alive = remember { booleanArrayOf(true) }

    val controller = remember {
        ComponentCatalogController(object : ComponentCatalogController.Host {
            override fun context(): Context = activity
            override fun hostActivity(): AppCompatActivity = activity
            override fun isAlive(): Boolean = alive[0]
            override fun runOnUi(action: Runnable) = activity.runOnUiThread(action)
            override fun close() = currentOnClose()
        })
    }

    val pickComponent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) controller.handleLocalComponentPicked(uri)
    }
    val pickDriver = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) controller.handleLocalDriverPicked(uri)
    }

    DisposableEffect(controller) {
        onDispose {
            alive[0] = false
            controller.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            ComposeView(context).also { view ->
                controller.initialize(null, null, Int.MIN_VALUE)
                controller.attachComposeController(
                    OnboardingComposeHost.attachToView(
                        context,
                        view,
                        controller.isCoreReady,
                        controller.coreProgress,
                        WineRuntimeGuard.isBundledMainInstalled(context),
                        WineRuntimeGuard.isInUse(context, WineInfo.MAIN_WINE_VERSION.identifier()),
                        controller.createCallbacks(object : ComponentCatalogController.ExtraCallbacks {
                            override fun onBrowseLocal() = pickComponent.launch(openDocumentIntent())
                            override fun onBrowseDriver() = pickDriver.launch(openDocumentIntent())
                        })
                    )
                )
                controller.start()
            }
        }
    )
}

private fun openDocumentIntent(): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
