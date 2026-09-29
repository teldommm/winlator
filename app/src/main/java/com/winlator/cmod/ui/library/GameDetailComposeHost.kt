package com.winlator.cmod.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
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
    fun onGameFolder()
    fun onFavorite(favorite: Boolean)
    fun onRemove()
}

// The game detail screen. Opened by MainShell as a detail entry through GameDetailRoute
// (GameDetailRoute.kt); onBack pops it.
@Composable
internal fun GameDetailScreen(title: String, subtitle: String, bannerPath: String?, coverPath: String?, coverIsUser: Boolean, fallback: Bitmap?, initialFavorite: Boolean, callbacks: GameDetailCallbacks) {
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
    if (landscape) LandscapeDetail(title, subtitle, artwork, coverOnly, coverIsUser, favorite, callbacks, toggle)
    else PortraitDetail(title, subtitle, artwork, coverOnly, coverIsUser, fallback, favorite, callbacks, toggle)
}

// Landscape game page: the picture stays open, the controls sit in the corners.
//   top-left  : back plate, then the title and the container under it
//   top-right : favorite plate (same look as the back plate)
//   bottom-right : one compact panel - Play and a row of icon buttons with captions
//   bottom-left  : the user's own portrait cover as a poster (clear of the panel)
// Two soft scrims (top for the title, bottom for the panel) replace the old heavy left-to-right
// darkening, so most of the picture keeps its own colours.
@Composable
private fun LandscapeDetail(title: String, subtitle: String, artwork: Bitmap?, coverOnly: Boolean, coverIsUser: Boolean, favorite: Boolean, callbacks: GameDetailCallbacks, toggleFavorite: () -> Unit) {
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
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            if (artwork != null && coverIsUser && !usesAsBackground(artwork, coverOnly, coverIsUser)) {
                DetailPoster(artwork, Modifier.align(Alignment.BottomStart))
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
                    style = MaterialTheme.typography.headlineSmall.copy(
                        shadow = Shadow(Color.Black.copy(.6f), Offset(0f, 2f), 6f)
                    ),
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    color = Color.White.copy(.78f),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        shadow = Shadow(Color.Black.copy(.6f), Offset(0f, 1f), 4f)
                    )
                )
            }

            FavoriteButton(favorite, toggleFavorite, Modifier.align(Alignment.TopEnd).padding(end = 18.dp, top = 16.dp))

            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 34.dp, bottom = 24.dp).width(304.dp),
                shape = WinZShapes.Large,
                color = Color.Black.copy(.42f),
                border = BorderStroke(1.dp, Color.White.copy(.16f))
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = callbacks::onPlay,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = WinZShapes.Medium,
                        colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.size(8.dp))
                        Text("Play", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailIconAction(Icons.Outlined.Settings, "Configure", Modifier.weight(1f), callbacks::onConfigure)
                        DetailIconAction(Icons.Outlined.Dns, "Container", Modifier.weight(1f), callbacks::onArguments)
                        DetailIconAction(Icons.Outlined.Folder, "Folder", Modifier.weight(1f), callbacks::onGameFolder)
                        DetailIconAction(Icons.Outlined.DeleteOutline, "Remove", Modifier.weight(1f), callbacks::onRemove, destructive = true)
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

// Compact square button for the landscape panel: icon over a short caption, light on the dark panel.
@Composable
private fun DetailIconAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit, destructive: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = WinZShapes.Medium,
        color = Color.White.copy(alpha = 0.10f),
        contentColor = if (destructive) destructiveColor() else Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
            .padding(start = 34.dp, top = 104.dp, bottom = 24.dp)
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
private fun PortraitDetail(title: String, subtitle: String, artwork: Bitmap?, coverOnly: Boolean, coverIsUser: Boolean, fallback: Bitmap?, favorite: Boolean, callbacks: GameDetailCallbacks, toggleFavorite: () -> Unit) {
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
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = callbacks::onPlay,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = WinZShapes.Medium,
                colors = ButtonDefaults.buttonColors(containerColor = controlAccentColor(), contentColor = Color.White)
            ) { Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.size(8.dp)); Text("Play", fontWeight = FontWeight.Bold) }
            DetailAction(Icons.Outlined.Settings, "Configure", Modifier.fillMaxWidth(), callbacks::onConfigure)
            DetailAction(Icons.Outlined.PlayArrow, "Enter container", Modifier.fillMaxWidth(), callbacks::onArguments)
            DetailAction(Icons.Outlined.Folder, "Game folder", Modifier.fillMaxWidth(), callbacks::onGameFolder)
            DetailAction(Icons.Outlined.DeleteOutline, "Remove", Modifier.fillMaxWidth(), callbacks::onRemove, true)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DetailAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit, destructive: Boolean = false, accent: Boolean = false) {
    val accentColor = controlAccentColor()
    val tint = if (destructive) MaterialTheme.colorScheme.error else if (accent) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        shape = WinZShapes.Medium,
        // Destructive keeps the regular fill and outline; only its icon and label are red.
        color = if (accent) accentColor else MaterialTheme.colorScheme.surface,
        contentColor = tint,
        border = BorderStroke(1.dp, if (accent) accentColor else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.size(10.dp))
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
