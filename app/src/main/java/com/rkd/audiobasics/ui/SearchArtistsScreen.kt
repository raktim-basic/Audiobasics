package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.rkd.audiobasics.api.Innertube
import com.rkd.audiobasics.data.Artist
import com.rkd.audiobasics.ui.theme.NothingFont

@Composable
fun SearchArtistsScreen(
    vm: MusicViewModel,
    query: String,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onArtistClick: (Artist) -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

    var artists by remember { mutableStateOf<List<Artist>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(query) {
        isLoading = true
        try {
            artists = Innertube.searchArtists(query)
        } catch (_: Exception) {
            artists = emptyList()
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Text(
            text = "Artists",
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = textColor,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
        )

        HorizontalDivider(color = subTextColor.copy(alpha = 0.3f))

        Box(modifier = Modifier.weight(1f)) {
            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Red)
                }
                artists.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No artists found", fontFamily = NothingFont, color = Color.Gray)
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(artists) { artist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onArtistClick(artist) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = artist.thumbnail,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = artist.name,
                                        fontFamily = NothingFont,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = textColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "(Artist)",
                                        fontFamily = NothingFont,
                                        fontSize = 13.sp,
                                        color = subTextColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor)
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
            }
        }
    }
}
