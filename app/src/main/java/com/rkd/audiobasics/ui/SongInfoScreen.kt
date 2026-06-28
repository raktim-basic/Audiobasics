package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rkd.audiobasics.cache.CacheManager
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.ui.theme.NothingFont
import android.content.Context

@Composable
fun SongInfoScreen(
    song: Song,
    isDarkMode: Boolean,
    context: Context,
    onDismiss: () -> Unit,
    onArtistClick: (String) -> Unit,   // artist name
    onAlbumClick: (String) -> Unit     // albumId
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)

    // Compute file size
    val fileSizeText = remember(song.id) {
        val f = CacheManager.getCacheFile(context, song.id)
        if (f.exists() && f.length() > 0) {
            val mb = f.length() / (1024.0 * 1024.0)
            "%.1f MB".format(mb)
        } else "N/A"
    }

    // Duration mm:ss
    val durationText = remember(song.duration) {
        if (song.duration > 0) {
            val totalSeconds = song.duration / 1000
            val m = totalSeconds / 60
            val s = totalSeconds % 60
            "%02d:%02d".format(m, s)
        } else "N/A"
    }

    // Has lyrics cached?
    val hasLyrics = remember(song.id) {
        CacheManager.isLyricsCached(context, song.id)
    }

    // Parse artists (comma/& separated in the artist string)
    val artists = remember(song.artist) {
        song.artist
            .split(Regex(",\\s*|\\s*&\\s*|\\s*feat\\.\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(bgColor)
        ) {
            // Title
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Song info.",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.Red
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = subColor.copy(0.3f))

            Spacer(Modifier.height(8.dp))

            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {

                InfoRow(label = "Name", value = song.title, textColor = textColor)

                Spacer(Modifier.height(18.dp))

                // Artists (clickable)
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = "Artist(s) : ",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = textColor
                    )
                    Column {
                        artists.forEachIndexed { i, artist ->
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(
                                        color = Color.Red,
                                        textDecoration = TextDecoration.Underline
                                    )) { append(artist) }
                                    if (i < artists.lastIndex) append(", ")
                                },
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.clickable { onArtistClick(artist) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Album (clickable if exists)
                if (song.albumId.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Album/EP : ",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = textColor
                        )
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(
                                    color = Color.Red,
                                    textDecoration = TextDecoration.Underline
                                )) { append(song.albumId) } // will be replaced with album title ideally
                            },
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.clickable { onAlbumClick(song.albumId) }
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                InfoRow(label = "Explicit", value = if (song.isExplicit) "Yes" else "No", textColor = textColor)
                Spacer(Modifier.height(16.dp))
                InfoRow(label = "Length", value = durationText, textColor = textColor)
                Spacer(Modifier.height(16.dp))
                InfoRow(label = "Size", value = fileSizeText, textColor = textColor)
                Spacer(Modifier.height(16.dp))
                InfoRow(label = "Lyrics", value = if (hasLyrics) "Yes" else "No", textColor = textColor)
            }

            Spacer(Modifier.height(8.dp))

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = subColor.copy(0.3f))

            // Back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, textColor: Color) {
    Text(
        text = "$label : $value",
        fontFamily = NothingFont,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        color = textColor
    )
}
