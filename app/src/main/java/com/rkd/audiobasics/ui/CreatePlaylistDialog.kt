package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rkd.audiobasics.ui.theme.NothingFont

private val EMOJI_OPTIONS = listOf(
    "🎵", "🎶", "🔥", "💜", "⭐", "🌙", "☀️",
    "🎸", "🎹", "🥁", "🎺", "🎻", "🎤", "🎧", "🎼",
    "😎", "🤩", "😭", "💀", "🌹", "🌊", "🏆", "💎",
    "🚀", "🌈", "⚡", "🎃", "🍀", "🦋", "🐉", "🎯"
)

@Composable
fun CreatePlaylistDialog(
    isDarkMode: Boolean,
    initialName: String = "",
    initialEmoji: String = "🎵",
    title: String = "Create playlist",
    confirmLabel: String = "Create",
    existingNames: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onCreate: (name: String, emoji: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedEmoji by remember { mutableStateOf(initialEmoji) }
    var showEmojiPicker by remember { mutableStateOf(false) }

    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)

    // A rename against its own unchanged name shouldn't count as a duplicate — callers
    // exclude the current playlist's own name from existingNames for that case.
    val isDuplicate = remember(name, existingNames) {
        val trimmed = name.trim()
        trimmed.isNotEmpty() && existingNames.any { it.equals(trimmed, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(bgColor)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = textColor
            )

            Spacer(Modifier.height(20.dp))

            // Emoji selector
            if (showEmojiPicker) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    items(EMOJI_OPTIONS) { emoji ->
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (emoji == selectedEmoji) Color.Red.copy(alpha = 0.2f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    selectedEmoji = emoji
                                    showEmojiPicker = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = emoji, fontSize = 20.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            } else {
                // Emoji circle tap to open picker
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .border(2.dp, subColor, CircleShape)
                        .clickable { showEmojiPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = selectedEmoji, fontSize = 32.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Select an emoji",
                    fontFamily = NothingFont,
                    fontSize = 12.sp,
                    color = subColor
                )
                Spacer(Modifier.height(16.dp))
            }

            // Name field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = {
                    Text(
                        "Playlist name...",
                        fontFamily = NothingFont,
                        color = subColor
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = isDuplicate,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Red,
                    unfocusedBorderColor = Color.Red,
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor,
                    cursorColor = Color.Red,
                    errorBorderColor = Color.Red,
                    errorCursorColor = Color.Red
                ),
                shape = RoundedCornerShape(12.dp)
            )

            if (isDuplicate) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "A playlist with this name already exists",
                    fontFamily = NothingFont,
                    fontSize = 12.sp,
                    color = Color.Red,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", fontFamily = NothingFont, color = textColor)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (name.isNotBlank() && !isDuplicate) onCreate(name.trim(), selectedEmoji)
                    },
                    enabled = name.isNotBlank() && !isDuplicate,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(confirmLabel, fontFamily = NothingFont, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
