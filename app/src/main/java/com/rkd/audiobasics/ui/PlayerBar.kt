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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

@Composable
fun PlayerBar(
    vm: MusicViewModel,
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    isDarkMode: Boolean,
    onToggle: () -> Unit,
    onAddTo: () -> Unit,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()

    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)

    if (song == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .clickable(enabled = false) { }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Gray.copy(alpha = 0.3f))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Nothing is playing",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onTap()
            }
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
            Text(
                text = song.title,
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                fontFamily = NothingFont,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                color = subTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
