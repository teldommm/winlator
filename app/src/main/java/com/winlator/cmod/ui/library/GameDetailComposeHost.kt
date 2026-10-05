package com.winlator.cmod.ui.library

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.ui.KeepLandscapeChromeHidden
import com.winlator.cmod.ui.theme.controlAccentColor
import com.winlator.cmod.ui.theme.destructiveColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor

interface GameDetailCallbacks {
    fun onBack()
    fun onPlay()
    fun onConfigure()
    fun onArguments()
    fun onSaves()
    fun onFavorite(favorite: Boolean)
    // Called once the person confirmed in the screen's own dialog.
    fun onRemove()
    // Home-screen icon: pin it (the launcher asks), or switch the pinned one off (confirmed).
    fun onPinHome()
    fun onUnpinHome()
}

// The game detail screen. Opened by MainShell as a detail entry through GameDetailRoute
// (GameDetailRoute.kt); onBack pops it.
@Composable
internal fun GameDetailScreen(title: String, subtitle: String, bannerPath: String?, coverPath: String?, coverIsUser: Boolean, fallback: Bitmap?, initialFavorite: Boolean, stats: GameStats, homePinned: Boolean, callbacks: GameDetailCallbacks) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val activity = LocalContext.current as? MainActivity

    // Fullscreen is kept by the shell's tabs; the native Toolbar this screen used in portrait
    // (title + up arrow) is gone — portrait now has the same on-artwork back button as landscape.
    if (landscape) KeepLandscapeChromeHidden(activity)

    // The Library pager/tiles have usually decoded this banner/cover already (LibraryImageCache),
    // so start from it instead of flashing the exe icon first.
    // Banner when there is one, else the (portrait) cover. There is no icon fallback here on
    // purpose: an exe icon cropped over the whole header looked broken (see ArtworkViews.kt).
    val artworkPath = bannerPath ?: coverPath
    val coverOnly = bannerPath == null && coverPath != null
    val artworkKey = remember(artworkPath) { LibraryImageCache.keyFor(artworkPath) }
    val artwork by produceState<Bitmap?>(LibraryImageCache.peek(artworkKey), artworkKey) {
        value = withContext(Dispatchers.IO) { LibraryImageCache.load(artworkPath) }
    }
    var favorite by remember(initialFavorite) { mutableStateOf(initialFavorite) }
    val toggle = {
        favorite = !favorite
        callbacks.onFavorite(favorite)
    }
    var confirm by remember { mutableStateOf<DetailConfirm?>(null) }
    val items = listOf(
        DetailActionItem(Icons.Outlined.Settings, "Configure", callbacks::onConfigure),
        DetailActionItem(Icons.Outlined.Dns, "Container", callbacks::onArguments),
        DetailActionItem(Icons.Outlined.Folder, "Saves", callbacks::onSaves),
        DetailActionItem(
            Icons.Outlined.Home,
            if (homePinned) "Unpin" else "Home",
            { if (homePinned) confirm = DetailConfirm.UnpinHome else callbacks.onPinHome() },
            accent = homePinned
        ),
        DetailActionItem(Icons.Outlined.DeleteOutline, "Remove", { confirm = DetailConfirm.Remove }, destructive = true)
    )
    Box(Modifier.fillMaxSize()) {
        if (landscape) LandscapeDetail(title, subtitle, artwork, coverOnly, coverIsUser, favorite, stats, items, callbacks, toggle)
        else PortraitDetail(title, subtitle, artwork, coverOnly, coverIsUser, fallback, favorite, stats, items, callbacks, toggle)
        DetailConfirmOverlay(
            request = confirm,
            title = title,
            onDismiss = { confirm = null },
            onConfirm = { request ->
                confirm = null
                when (request) {
                    DetailConfirm.Remove -> callbacks.onRemove()
                    DetailConfirm.UnpinHome -> callbacks.onUnpinHome()
                }
            }
        )
    }
}

// Landscape game page: the picture stays open, the controls sit in the corners.
//   top-left  : back plate, then the title and the container under it
//   top-right : favorite plate (same look as the back plate)
//   bottom-right : one compact panel - Play and a row of icon buttons with captions
//   bottom-left  : the user's own portrait cover as a poster (clear of the panel)
// Two soft scrims (top for the title, bottom for the panel) replace the old heavy left-to-right
// darkening, so most of the picture keeps its own colours.
@Composable
private fun LandscapeDetail(title: String, subtitle: String, artwork: Bitmap?, coverOnly: Boolean, coverIsUser: Boolean, favorite: Boolean, stats: GameStats, items: List<DetailActionItem>, callbacks: GameDetailCallbacks, toggleFavorite: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DetailArtwork(artwork, coverOnly, coverIsUser, landscape = true)
        Box(
            Modifier.align(Alignment.TopStart).fillMaxWidth().height(132.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(.66f), Color.Transparent)))
        )
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(210.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.60f))))
        )

        // Everything interactive stays clear of a display cutout on the left / right edge.
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            // ~42% of the width: 320dp on a small landscape phone, up to 400dp on a wide one.
            val panelWidth = (maxWidth * 0.42f).coerceIn(320.dp, 400.dp)
            // Five icon buttons share the panel: a narrow cell falls back to the smaller caption.
            val iconCell = (panelWidth - 28.dp - 8.dp * (items.size - 1)) / items.size
            val compactCaptions = iconCell < 64.dp
            val posterShown = artwork != null && coverIsUser && !usesAsBackground(artwork, coverOnly, coverIsUser)
            if (artwork != null && posterShown) {
                DetailPoster(artwork, Modifier.align(Alignment.BottomStart))
            }

            // The stat chips sit bottom-left, beside the poster when there is one. Where the picture
            // and the panel leave no room (small phone), they move into the panel above Play.
            val posterWidth = if (artwork != null && posterShown) {
                (maxHeight - 156.dp).coerceAtLeast(0.dp) * (artwork.width.toFloat() / artwork.height.toFloat()).coerceIn(0.5f, 1.29f)
            } else 0.dp
            val chipsStart = 34.dp + if (posterShown) posterWidth + 14.dp else 0.dp
            val chipsEndReserve = panelWidth + 34.dp + 16.dp
            val chipsInPanel = stats.hasAny && maxWidth - chipsStart - chipsEndReserve < 150.dp
            if (stats.hasAny && !chipsInPanel) {
                StatChips(stats, onArt = true, modifier = Modifier.align(Alignment.BottomStart).padding(start = chipsStart, end = chipsEndReserve, bottom = 24.dp))
            }

            ArtPlateButton(
                onClick = callbacks::onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 16.dp)
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", modifier = Modifier.size(24.dp)) }

            // 74dp = 18 margin + 44 plate + 12 gap, on both sides, so a long title never runs under a plate.
            Column(Modifier.align(Alignment.TopStart).padding(start = 74.dp, end = 74.dp, top = 15.dp)) {
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        shadow = Shadow(Color.Black.copy(.6f), Offset(0f, 2f), 6f)
                    ),
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    color = Color.White.copy(.78f),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        shadow = Shadow(Color.Black.copy(.6f), Offset(0f, 1f), 4f)
                    )
                )
            }

            FavoriteButton(favorite, toggleFavorite, Modifier.align(Alignment.TopEnd).padding(end = 18.dp, top = 16.dp))

            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 34.dp, bottom = 24.dp).width(panelWidth),
                shape = WinZShapes.Large,
                color = Color.Black.copy(.42f),
                border = BorderStroke(1.dp, Color.White.copy(.16f))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (chipsInPanel) StatChips(stats, onArt = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = callbacks::onPlay,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = WinZShapes.Medium,
                        colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Play", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.forEach { DetailIconAction(it, Modifier.weight(1f), compactCaptions, onArt = true) }
                    }
                }
            }
        }
    }
}

// Round plate for a control that sits on the artwork (back, favorite). Same translucent look as the
// selected All / Favorites / Recent chip, but always light-on-dark: the artwork under it is darkened
// by a scrim in every theme, so the White theme's dark glyph would vanish.
@Composable
private fun ArtPlateButton(onClick: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        shape = WinZShapes.Medium,
        color = Color.White.copy(alpha = 0.10f),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
    ) { Box(contentAlignment = Alignment.Center) { content() } }
}

// Favorite star on the same plate as the back arrow; filled and in the accent colour when set.
@Composable
private fun FavoriteButton(favorite: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    ArtPlateButton(onClick = onToggle, modifier = modifier) {
        Icon(
            if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            if (favorite) "Remove from favorites" else "Add to favorites",
            modifier = Modifier.size(24.dp),
            tint = if (favorite) controlAccentColor() else LocalContentColor.current
        )
    }
}

// Square button: icon over a short caption. On the artwork (landscape panel) it is light on the dark
// panel; in the portrait row it takes the theme's own surface and outline.
@Composable
private fun DetailIconAction(item: DetailActionItem, modifier: Modifier, compactCaption: Boolean, onArt: Boolean) {
    val content = when {
        item.destructive -> destructiveColor()
        item.accent -> controlAccentColor()
        onArt -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = item.onClick,
        modifier = modifier.height(70.dp),
        shape = WinZShapes.Medium,
        color = if (onArt) Color.White.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
        contentColor = content,
        border = BorderStroke(1.dp, if (onArt) Color.White.copy(alpha = 0.16f) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(item.icon, contentDescription = item.label, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(4.dp))
            // A narrow cell (small phone) falls back to a smaller caption so "Configure" fits.
            val base = if (compactCaption) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
            Text(
                item.label,
                style = if (compactCaption) base.copy(fontSize = 10.sp) else base,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

// The user's own portrait cover as a poster, bottom-left, kept sharp above the scrims and clear of
// the title (top padding) and of the action panel (it is on the right).
@Composable
private fun DetailPoster(artwork: Bitmap, modifier: Modifier) {
    val ratio = (artwork.width.toFloat() / artwork.height.toFloat()).coerceIn(0.5f, 1.29f)
    Image(
        artwork.asImageBitmap(),
        null,
        modifier
            .padding(start = 34.dp, top = 132.dp, bottom = 24.dp)
            .fillMaxHeight()
            .aspectRatio(ratio)
            .kenBurns(amplitude = 0.04f, periodMs = 18_000, pan = 0f, phase = 0.5f)
            .clip(WinZShapes.Small),
        contentScale = ContentScale.Crop
    )
}

// A wide picture of the user's own is the background itself; anything else that is only a cover
// gets the soft wash with the cover shown in front.
private fun usesAsBackground(artwork: Bitmap, coverOnly: Boolean, coverIsUser: Boolean): Boolean =
    !coverOnly || (coverIsUser && artwork.width >= artwork.height * 1.3f)

// What the header shows, by where the picture came from (all of it moves only when the screen
// allowed motion: LocalArtworkMotion, the "Animated artwork" setting):
//  - banner or the user's own background: sharp, full-bleed, slowly breathing;
//  - the user's own cover, wide enough (>= 1.3): the same, it is the background itself;
//  - any other cover (downloaded portrait cover, the offline placeholder): a soft colour wash of it
//    drifting behind, plus - in the portrait header only - the cover itself in front, at the top.
//    (In landscape the user's own cover is a poster drawn above the scrims: see DetailPoster.)
//  - the user's own cover keeps its own proportions instead of being cropped to 2:3.
@Composable
private fun DetailArtwork(artwork: Bitmap?, coverOnly: Boolean, coverIsUser: Boolean, landscape: Boolean) {
    if (artwork == null) return
    val image = artwork.asImageBitmap()
    val ratio = artwork.width.toFloat() / artwork.height.toFloat()
    val frameRatio = if (coverIsUser) ratio.coerceIn(0.5f, 1.29f) else 2f / 3f
    Box(Modifier.fillMaxSize().clipToBounds()) {
        if (usesAsBackground(artwork, coverOnly, coverIsUser)) {
            Image(image, null, Modifier.fillMaxSize().kenBurns(), contentScale = ContentScale.Crop)
        } else {
            val soft = remember(artwork) { SoftArt.from(artwork) }
            Image(
                soft.asImageBitmap(),
                null,
                Modifier.fillMaxSize().kenBurns(amplitude = 0.12f, periodMs = 26_000, pan = 0.9f),
                contentScale = ContentScale.Crop
            )
            if (!landscape) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Image(
                        image,
                        null,
                        Modifier
                            .padding(top = 16.dp, bottom = 112.dp)
                            .fillMaxHeight()
                            .aspectRatio(frameRatio)
                            .kenBurns(amplitude = 0.04f, periodMs = 18_000, pan = 0f, phase = 0.5f)
                            .clip(WinZShapes.Small),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

@Composable
private fun PortraitDetail(title: String, subtitle: String, artwork: Bitmap?, coverOnly: Boolean, coverIsUser: Boolean, fallback: Bitmap?, favorite: Boolean, stats: GameStats, items: List<DetailActionItem>, callbacks: GameDetailCallbacks, toggleFavorite: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.28f).background(MaterialTheme.colorScheme.surface)) {
            if (artwork != null) DetailArtwork(artwork, coverOnly, coverIsUser, landscape = false)
            else if (fallback != null) IconTile(null, fallback, Modifier.fillMaxSize())
            // A little more scrim at the top than before: the two plates sit right on the picture.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.38f), Color.Transparent, Color.Black.copy(.88f)))))
            ArtPlateButton(
                onClick = callbacks::onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 14.dp)
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", modifier = Modifier.size(24.dp)) }
            FavoriteButton(favorite, toggleFavorite, Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 14.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Color.White.copy(.72f), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (stats.hasAny) StatChips(stats, onArt = false, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = callbacks::onPlay,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = WinZShapes.Medium,
                colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
            ) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.size(8.dp)); Text("Play", fontWeight = FontWeight.Bold) }
            DetailIconRow(items)
            Spacer(Modifier.height(12.dp))
        }
    }
}

// One row of icon buttons that shrinks to whatever the width allows (up to 78dp each, centred on a
// wide screen), so every action fits on a narrow phone without a second row or a scroll.
@Composable
private fun DetailIconRow(items: List<DetailActionItem>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 8.dp
        val cell = minOf(78.dp, (maxWidth - gap * (items.size - 1)) / items.size)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
            items.forEach { DetailIconAction(it, Modifier.width(cell), compactCaption = cell < 64.dp, onArt = false) }
        }
    }
}

// Playtime, plays, last launch and size. On the artwork (landscape) the chips are dark glass like the
// action panel; on the page background (portrait) they take the theme's surface and outline.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatChips(stats: GameStats, onArt: Boolean, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (stats.playtimeMillis > 0L) StatChip(Icons.Outlined.Schedule, "Playtime", formatPlaytime(stats.playtimeMillis), onArt)
        if (stats.playCount > 0) StatChip(Icons.Outlined.SportsEsports, "Plays", stats.playCount.toString(), onArt)
        if (stats.lastPlayedMillis > 0L) StatChip(Icons.Outlined.History, "Last played", formatLastPlayed(stats.lastPlayedMillis), onArt)
        stats.sizeText?.let { StatChip(Icons.Outlined.Storage, "Size", it, onArt) }
    }
}

@Composable
private fun StatChip(icon: ImageVector, label: String, value: String, onArt: Boolean) {
    val iconTint = if (onArt) Color.White.copy(.78f) else MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = if (onArt) Color.White.copy(.62f) else MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = if (onArt) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = WinZShapes.Small,
        color = if (onArt) Color.Black.copy(.44f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (onArt) Color.White.copy(.12f) else hairlineColor())
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = iconTint)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(label.uppercase(), color = labelColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private class DetailActionItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val accent: Boolean = false
)

private enum class DetailConfirm { Remove, UnpinHome }

// Confirmation drawn over the page itself (no separate window, so the immersive screen and the toasts
// stay as they are): a dimmed scrim and a small themed card, red confirm button. Back closes it first.
@Composable
private fun DetailConfirmOverlay(request: DetailConfirm?, title: String, onDismiss: () -> Unit, onConfirm: (DetailConfirm) -> Unit) {
    BackHandler(enabled = request != null, onBack = onDismiss)
    // Keep the last request while the card fades out.
    var shown by remember { mutableStateOf(request) }
    if (request != null) shown = request
    AnimatedVisibility(visible = request != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
        val current = shown ?: return@AnimatedVisibility
        val (icon, heading, message, confirmLabel) = when (current) {
            DetailConfirm.Remove -> ConfirmTexts(
                Icons.Outlined.DeleteOutline,
                "Remove from library?",
                "\"$title\" will be removed from your library. The game files stay on your device.",
                "Remove"
            )
            DetailConfirm.UnpinHome -> ConfirmTexts(
                Icons.Outlined.Home,
                "Remove from home screen?",
                "The home screen icon for \"$title\" will stop working. The game stays in your library.",
                "Remove"
            )
        }
        val noRipple = remember { MutableInteractionSource() }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(.46f)).clickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.padding(16.dp).fillMaxWidth().widthIn(max = 286.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                shape = WinZShapes.Medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, hairlineColor()),
                shadowElevation = 14.dp
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(icon, null, Modifier.size(18.dp), tint = destructiveColor())
                        Text(heading, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { onConfirm(current) }) { Text(confirmLabel, color = destructiveColor(), fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}

private data class ConfirmTexts(val icon: ImageVector, val heading: String, val message: String, val confirmLabel: String)
