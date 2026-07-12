package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rkd.audiobasics.ui.theme.NothingFont

// Emojis already used elsewhere in the app to represent Liked Songs' download state —
// reserved so playlists can't be confused with that UI.
private val RESERVED_EMOJIS = setOf("❤️", "💔", "❤️\u200D🩹")

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
    var emojiFieldValue by remember { mutableStateOf("") }
    var showReservedWarning by remember { mutableStateOf(false) }
    val emojiFocusRequester = remember { FocusRequester() }

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

            // Emoji selector — tapping focuses a hidden text field so the keyboard's own
            // emoji picker opens; only the most recently typed emoji is kept, and the app's
            // reserved emojis (used elsewhere for Liked Songs) are rejected.
            var isEmojiFieldFocused by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (isEmojiFieldFocused) Color.Red else subColor, CircleShape)
                    .clickable { emojiFocusRequester.requestFocus() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = selectedEmoji, fontSize = 32.sp)

                BasicTextField(
                    value = emojiFieldValue,
                    onValueChange = { newValue ->
                        val candidate = newValue.trim()
                        if (candidate.isBlank()) return@BasicTextField

                        if (candidate in RESERVED_EMOJIS) {
                            showReservedWarning = true
                        } else {
                            showReservedWarning = false
                            selectedEmoji = candidate
                        }
                        // Clear the field immediately after every pick so the *next* emoji the
                        // keyboard inserts lands in an empty field instead of being appended
                        // after the previous one — keeps exactly one emoji selected at a time.
                        emojiFieldValue = ""
                    },
                    modifier = Modifier
                        .matchParentSize()
                        .focusRequester(emojiFocusRequester)
                        .onFocusChanged { isEmojiFieldFocused = it.isFocused },
                    textStyle = TextStyle(
                        fontSize = 32.sp,
                        textAlign = TextAlign.Center,
                        color = Color.Transparent
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
                    singleLine = true
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tap to choose an emoji",
                fontFamily = NothingFont,
                fontSize = 12.sp,
                color = subColor
            )
            if (showReservedWarning) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "That emoji is reserved for Liked Songs — pick another",
                    fontFamily = NothingFont,
                    fontSize = 12.sp,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(16.dp))

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
