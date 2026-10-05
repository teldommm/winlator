package com.winlator.cmod.widget

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// What the classic HUD (FrameRating) shows. FrameRating (Java) owns the measuring and pushes
// the strings in; this file only draws them.
class FrameRatingState {
    var fps by mutableStateOf("0")
    var renderer by mutableStateOf("")
    var gpu by mutableStateOf("")
    var ram by mutableStateOf("")
    // HUD Size / Opacity from the sidebar. 1f = natural size / fully opaque.
    var scale by mutableStateOf(1f)
    var alpha by mutableStateOf(1f)
}

// Compose body of FrameRating, replacing the old frame_rating.xml (four TextView rows). Same
// look: bold 12dp text, a 33% black strip per row, 10dp from the left edge and 2dp between rows.
object FrameRatingComposeHost {
    @JvmStatic
    fun create(context: Context, state: FrameRatingState): ComposeView =
        ComposeView(context).apply { setContent { FrameRatingContent(state) } }
}

private val FpsLabel = Color(0xFF0277BD)
private val RendererLabel = Color(0xFFFC0303)
private val GpuLabel = Color(0xFF5C23A6)
private val RamLabel = Color(0xFF23A6A4)

@Composable
private fun FrameRatingContent(state: FrameRatingState) {
    // Nothing here has a pointer-input node, so touches fall through to the game underneath
    // (the old FrameLayout/TextViews were not clickable either).
    Column(
        Modifier
            .fillMaxSize()
            // Scaled from the top-left corner (where the HUD sits), like the Modern HUD; the
            // state is only read in the draw phase so a slider drag doesn't recompose.
            .graphicsLayer {
                scaleX = state.scale
                scaleY = state.scale
                alpha = state.alpha
                transformOrigin = TransformOrigin(0f, 0f)
            }
    ) {
        HudRow("FPS:", FpsLabel, state.fps)
        HudRow("Renderer:", RendererLabel, state.renderer)
        HudRow("GPU:", GpuLabel, state.gpu)
        HudRow("RAM:", RamLabel, state.ram)
    }
}

@Composable
private fun HudRow(label: String, labelColor: Color, value: String) {
    // The XML sized the text in dp (not sp), so it ignores the user's font scale; keep that.
    val size = with(LocalDensity.current) { 12.dp.toSp() }
    Row(
        Modifier
            .padding(start = 10.dp, top = 2.dp)
            .background(Color(0x33000000))
            .padding(2.dp)
    ) {
        BasicText(
            label,
            style = TextStyle(color = labelColor, fontSize = size, fontWeight = FontWeight.Bold),
            maxLines = 1
        )
        Spacer(Modifier.width(5.dp))
        BasicText(
            value,
            style = TextStyle(color = Color.White, fontSize = size, fontWeight = FontWeight.Bold),
            maxLines = 1
        )
    }
}
