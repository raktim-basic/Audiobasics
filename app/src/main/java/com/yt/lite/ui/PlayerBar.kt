package com.yt.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yt.lite.data.Song
import com.yt.lite.ui.theme.NothingFont

@Composable
fun PlayerBar(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    isLiked: Boolean,
    onToggle: () -> Unit,
    onLike: () -> Unit,
    onTap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .clickable { onTap() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Artwork
        AsyncImage(
            model = song.thumbnail,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(Modifier.width(12.dp))

        // Title + Artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                fontFamily = NothingFont,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                color = Color(0xFF888888),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Like button
        IconButton(onClick = onLike) {
            Icon(
                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Like",
                tint = if (isLiked) Color.Red else Color.Gray
            )
        }

        // Play/Pause button
        IconButton(onClick = onToggle) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.Black
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.Black
                )
            }
        }
    }
}
