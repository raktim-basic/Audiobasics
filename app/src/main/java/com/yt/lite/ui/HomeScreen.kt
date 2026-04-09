package com.yt.lite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yt.lite.ui.theme.NothingFont

@Composable
fun rememberScrollProgress(listState: LazyListState, totalItems: Int): Float {
    return remember(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (totalItems <= 1) return@remember 0f
        val itemProgress = listState.firstVisibleItemIndex.toFloat() / (totalItems - 1).toFloat()
        itemProgress.coerceIn(0f, 1f)
    }
}

@Composable
fun HomeScreen(
    vm: MusicViewModel,
    onNavigateSearch: () -> Unit,
    onNavigateQueue: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateLiked: () -> Unit,
    onNavigateAlbums: () -> Unit
) {
    val likedSongs by vm.likedSongs.collectAsState()
    val savedAlbums by vm.savedAlbums.collectAsState()
    val cacheSize by vm.cacheSize.collectAsState()
    val isDarkMode by vm.isDarkMode.collectAsState()
    val updateAvailable by vm.updateAvailable.collectAsState()
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val haptic = LocalHapticFeedback.current

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 32.dp)
        ) {
            if (updateAvailable) {
                Text(
                    text = "An update is available!",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = textColor
                )
                Text(
                    text = "Check in settings",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = textColor
                )
            } else {
                Text(
                    text = "No recommendation bs",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = textColor
                )
                Text(
                    text = "Own your taste",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = textColor
                )
            }
        }

        DashedDivider(
            modifier = Modifier.fillMaxWidth(),
            isDarkMode = isDarkMode
        )

        Spacer(Modifier.weight(1f))

        // Saved Albums button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFFF8A80))
                .clickable {
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.KeyTap)
                    onNavigateAlbums()
                }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Bookmark,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Saved Albums (${savedAlbums.size})",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = ">",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )
            }
        }

        // Liked Songs button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFFF0000))
                .clickable {
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.KeyTap)
                    onNavigateLiked()
                }
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Liked Songs (${likedSongs.size})",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = Color.White
                    )
                    if (cacheSize.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = cacheSize,
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Normal,
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFDDDDDD))
        )

        HomeBottomBar(
            isDarkMode = isDarkMode,
            onSettings = onNavigateSettings,
            onSearch = onNavigateSearch,
            onQueue = onNavigateQueue,
            hapticsEnabled = hapticsEnabled,
            haptic = haptic
        )
    }
}

@Composable
fun HomeBottomBar(
    isDarkMode: Boolean,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onQueue: () -> Unit,
    hapticsEnabled: Boolean,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)
    val iconColor = if (isDarkMode) Color.White else Color.Black

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.KeyTap)
            onSettings()
        }) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )
        }
        IconButton(onClick = {
            if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.KeyTap)
            onSearch()
        }) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search",
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )
        }
        IconButton(onClick = {
            if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.KeyTap)
            onQueue()
        }) {
            Icon(
                Icons.Default.QueueMusic,
                contentDescription = "Queue",
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

// scrollProgress: null = static (Home/Settings), 0f..1f = scroll indicator
@Composable
fun DashedDivider(
    modifier: Modifier = Modifier,
    isDarkMode: Boolean = false,
    scrollProgress: Float? = null
) {
    val secondColor = if (isDarkMode) Color.White else Color.Black

    Canvas(modifier = modifier.height(12.dp)) {
        val totalWidth = size.width
        val y = size.height / 2f
        val dashWidth = 16f
        val dashGap = 8f

        if (scrollProgress == null) {
            drawLine(
                color = Color.Red,
                start = Offset(0f, y),
                end = Offset(totalWidth / 2f, y),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashWidth, dashGap), 0f)
            )
            drawLine(
                color = secondColor,
                start = Offset(totalWidth / 2f, y),
                end = Offset(totalWidth, y),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashWidth, dashGap), 0f)
            )
        } else {
            val redEnd = totalWidth * scrollProgress.coerceIn(0f, 1f)
            if (redEnd > 0f) {
                drawLine(
                    color = Color.Red,
                    start = Offset(0f, y),
                    end = Offset(redEnd, y),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashWidth, dashGap), 0f)
                )
            }
            if (redEnd < totalWidth) {
                drawLine(
                    color = secondColor.copy(alpha = 0.3f),
                    start = Offset(redEnd, y),
                    end = Offset(totalWidth, y),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashWidth, dashGap), 0f)
                )
            }
        }
    }
}
