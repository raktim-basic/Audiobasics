package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

/** Colour laid over the played part of the player bar (and the navigation bar under it). */
fun playerBarPlayedShade(isDarkMode: Boolean): Color =
    if (isDarkMode) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.07f)

@Composable
fun PlayerBar(
    vm: MusicViewModel,
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    isDarkMode: Boolean,
    onToggle: () -> Unit,
    onAddTo: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    // Height of the navigation bar under the bar. The background and the played shade cover it too
    // (so the bar runs to the bottom edge of the screen); the content stays in the area above it.
    bottomInset: Dp = 0.dp
) {
    val context = LocalContext.current
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()

    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE0E0E0)
    // Only the top corners are rounded: the bottom edge sits flush against the system navigation bar.
    val barShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)

    if (song == null) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(barShape)
                .background(bgColor)
                .clickable(enabled = false) { }
                .padding(bottom = bottomInset)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isDarkMode) Color.Gray.copy(alpha = 0.3f) else Color.White)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                FadeText(
                    text = "Nothing is playing",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = textColor,
                )
                Text(
                    text = " ",
                    fontFamily = NothingFont,
                    fontSize = 12.sp,
                    color = subTextColor,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.width(48.dp))
            Spacer(modifier = Modifier.width(48.dp))
        }
        return
    }

    // How much of the song has played, as a slightly different shade on the left of the bar. The
    // states are only read while drawing, so the bar redraws as the song plays but never recomposes.
    val positionState = vm.currentPosition.collectAsState()
    val durationState = vm.duration.collectAsState()
    val playedShade = playerBarPlayedShade(isDarkMode)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(barShape)
            .background(bgColor)
            .drawBehind {
                val d = durationState.value
                if (d > 0L) {
                    val progress = (positionState.value.toFloat() / d.toFloat()).coerceIn(0f, 1f)
                    drawRect(color = playedShade, size = androidx.compose.ui.geometry.Size(size.width * progress, size.height))
                }
            }
            .clickable {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onTap()
            }
            .padding(bottom = bottomInset)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val cachedThumbPath = remember(song.id) {
            com.rkd.audiobasics.cache.CacheManager.getCachedThumbPath(context, song.id)
        }
        AsyncImage(
            model = cachedThumbPath ?: song.thumbnail,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            FadeText(
                text = song.title,
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = textColor,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.isExplicit) {
                    val explicitBgColor = if (isDarkMode) Color(0xFF444444) else Color(0xFFC4C4C4)
                    val explicitTextColor = if (isDarkMode) Color(0xFFCCCCCC) else Color(0xFF555555)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(explicitBgColor)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "E",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.sp,
                            color = explicitTextColor
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                }
                FadeText(
                    text = song.artist,
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = subTextColor,
                )
            }
        }

        // + button (Add to playlist)
        IconButton(onClick = {
            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
            onAddTo()
        }) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add to playlist",
                tint = textColor
            )
        }

        // Play/Pause
        IconButton(onClick = {
            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
            onToggle()
        }) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = textColor
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = textColor
                )
            }
        }
    }
}


/**
 * A strip exactly as tall as the navigation bar, pinned to the bottom of the screen above the
 * player sheet. While the sheet slides it hides whatever the expanded player has under the
 * navigation bar (the artwork edge) by fading from the bar's colour to [playerBg]; once the sheet
 * is nearly open it fades out so the player's own background takes over. [progress] is 0 when the
 * sheet is collapsed and 1 when expanded, and is only read while drawing.
 */
@Composable
fun PlayerBarNavCap(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    playerBg: Color,
    progress: () -> Float,
    modifier: Modifier = Modifier
) {
    // Same colours as PlayerBar's background and played shade.
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE0E0E0)
    val playedShade = playerBarPlayedShade(isDarkMode)
    val positionState = vm.currentPosition.collectAsState()
    val durationState = vm.duration.collectAsState()

    Spacer(
        modifier = modifier.drawBehind {
            val p = progress().coerceIn(0f, 1f)
            val alpha = 1f - ((p - 0.85f) / 0.15f).coerceIn(0f, 1f)
            if (alpha <= 0f) return@drawBehind
            drawRect(color = lerp(barColor, playerBg, p).copy(alpha = alpha))
            val d = durationState.value
            if (d > 0L) {
                val played = (positionState.value.toFloat() / d.toFloat()).coerceIn(0f, 1f)
                drawRect(
                    color = playedShade.copy(alpha = playedShade.alpha * (1f - p) * alpha),
                    size = androidx.compose.ui.geometry.Size(size.width * played, size.height)
                )
            }
        }
    )
}
