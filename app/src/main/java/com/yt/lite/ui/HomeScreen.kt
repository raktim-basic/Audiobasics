package com.yt.lite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yt.lite.ui.theme.NothingFont

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

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Decorative text
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 32.dp)
        ) {
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

        // Dashed divider — red + white in dark, red + black in light
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
                .clickable { onNavigateAlbums() }
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
                .clickable { onNavigateLiked() }
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

        // Bottom bar
        HomeBottomBar(
            isDarkMode = isDarkMode,
            onSettings = onNavigateSettings,
            onSearch = onNavigateSearch,
            onQueue = onNavigateQueue
        )
    }
}

@Composable
fun HomeBottomBar(
    isDarkMode: Boolean,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onQueue: () -> Unit
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
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )
        }
        IconButton(onClick = onSearch) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search",
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )
        }
        IconButton(onClick = onQueue) {
            Icon(
                Icons.Default.QueueMusic,
                contentDescription = "Queue",
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun DashedDivider(
    modifier: Modifier = Modifier,
    isDarkMode: Boolean = false
) {
    val secondColor = if (isDarkMode) Color.White else Color.Black

    Canvas(modifier = modifier.height(12.dp)) {
        val totalWidth = size.width
        val halfWidth = totalWidth / 2f
        val y = size.height / 2f
        val dashWidth = 16f
        val dashGap = 8f

        drawLine(
            color = Color.Red,
            start = Offset(0f, y),
            end = Offset(halfWidth, y),
            strokeWidth = 3f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashWidth, dashGap), 0f)
        )

        drawLine(
            color = secondColor,
            start = Offset(halfWidth, y),
            end = Offset(totalWidth, y),
            strokeWidth = 3f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashWidth, dashGap), 0f)
        )
    }
}
