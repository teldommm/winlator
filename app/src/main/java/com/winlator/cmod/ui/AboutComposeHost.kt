package com.winlator.cmod.ui

import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.theme.controlAccentColor

// Native bridge so any Java call site (nav-drawer "About" item, Settings row) shows
// the exact same Compose content — a single implementation, no risk of desync.
object AboutDialogHost {
    @JvmStatic
    fun show(activity: MainActivity) {
        val dialog = Dialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.decorView?.let { decorView ->
            ViewTreeLifecycleOwner.set(decorView, activity)
            ViewTreeViewModelStoreOwner.set(decorView, activity)
            ViewTreeSavedStateRegistryOwner.set(decorView, activity)
        }
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WinZTheme { AboutDialogContent(onDismiss = { dialog.dismiss() }) } }
        }
        dialog.setContentView(composeView)
        dialog.show()
    }
}

@Composable
private fun AboutDialogContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
    }
    Surface(
        modifier = Modifier.widthIn(max = 340.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Winlator CMOD", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(linkText("winlator.org" to "https://www.winlator.org"))
                    Text(
                        "${stringResource(R.string.version)} $versionName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(56.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)

            Text(stringResource(R.string.credits_and_third_party_apps), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                linkText(
                    "Winlator Ludashi by StevenMX, pipetto-crypto (",
                    "Fork" to "https://github.com/StevenMXZ/Winlator-Ludashi",
                    ", ",
                    "Fork" to "https://github.com/Pipetto-crypto/winlator",
                    ")"
                )
            )
            Text("Big Picture Mode Music by")
            Text("Dale Melvin Blevens III (Fumer)")
            Text("---")
            Text(linkText("Termux Package(", "github.com/termux/termux-package" to "https://github.com/termux/termux-packages", ")"))
            Text(linkText("Wine (", "winehq.org" to "https://www.winehq.org", ")"))
            Text(linkText("Box64 (", "github.com/ptitSeb/box64" to "https://github.com/ptitSeb/box64", ")"))
            Text(linkText("Mesa (Turnip/Zink/Wrapper) (", "github.com/xMeM/mesa" to "https://github.com/xMeM/mesa/tree/wrapper", ")"))
            Text(linkText("DXVK (", "github.com/doitsujin/dxvk" to "https://github.com/doitsujin/dxvk", ")"))
            Text(linkText("VKD3D (", "gitlab.winehq.org/wine/vkd3d" to "https://gitlab.winehq.org/wine/vkd3d", ")"))
            Text(linkText("D8VK (", "github.com/AlpyneDreams/d8vk" to "https://github.com/AlpyneDreams/d8vk", ")"))
            Text(linkText("CNC DDraw (", "github.com/FunkyFr3sh/cnc-ddraw" to "https://github.com/FunkyFr3sh/cnc-ddraw", ")"))
            Text(linkText("dxwrapper (", "github.com/elishacloud/dxwrapper" to "https://github.com/elishacloud/dxwrapper", ")"))
            Text(linkText("FEX-Emu (", "github.com/FEX-Emu/FEX" to "https://github.com/FEX-Emu/FEX", ")"))
            Text(linkText("libadrenotools (", "github.com/bylaws/libadrenotools" to "https://github.com/bylaws/libadrenotools", ")"))

            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.glibc_exp_edition), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(linkText("longjunyu2's ", "(GLIBC Fork)" to "https://github.com/longjunyu2/winlator/tree/use-glibc-instead-of-proot"))

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) { Text("Close") }
        }
    }
}

@Composable
private fun linkText(vararg parts: Any): AnnotatedString {
    val accent = controlAccentColor()
    return buildAnnotatedString {
        for (part in parts) {
            when (part) {
                is Pair<*, *> -> {
                    val label = part.first as String
                    val url = part.second as String
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline)))) {
                        append(label)
                    }
                }
                else -> append(part.toString())
            }
        }
    }
}
