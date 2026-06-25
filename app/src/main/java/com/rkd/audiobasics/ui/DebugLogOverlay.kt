package com.rkd.audiobasics.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

// ── Log Interceptor ──────────────────────────────────────────────────────────

object DebugLogCollector {
    private val _logs = mutableStateListOf<LogEntry>()
    val logs: List<LogEntry> get() = _logs

    // Max log lines — bumped from 200 to 1000
    private const val MAX_LOGS = 1000

    data class LogEntry(
        val tag: String,
        val message: String,
        val level: Int,   // Log.DEBUG=3, Log.WARN=5, Log.ERROR=6
        val time: String
    )

    fun add(level: Int, tag: String?, message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _logs.add(LogEntry(tag ?: "?", message, level, time))
        if (_logs.size > MAX_LOGS) _logs.removeAt(0)
    }

    fun clear() = _logs.clear()

    // Returns all logs as a plain text string for clipboard copy
    fun exportText(): String = _logs.joinToString("\n") { entry ->
        val levelChar = when (entry.level) {
            Log.ERROR -> "E"
            Log.WARN  -> "W"
            Log.DEBUG -> "D"
            else      -> "I"
        }
        "${entry.time} $levelChar [${entry.tag}] ${entry.message}"
    }
}

// ── Composable Overlay ───────────────────────────────────────────────────────

@Composable
fun DebugLogOverlay(logsEnabled: Boolean) {
    var visible by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    // Snapshot of logs taken when paused — so display freezes while paused
    val liveLogs = DebugLogCollector.logs
    var pausedSnapshot by remember { mutableStateOf<List<DebugLogCollector.LogEntry>>(emptyList()) }
    val displayLogs = if (paused) pausedSnapshot else liveLogs

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Auto-scroll to bottom when new logs arrive (only when not paused)
    LaunchedEffect(liveLogs.size, paused) {
        if (!paused && displayLogs.isNotEmpty()) {
            listState.animateScrollToItem(displayLogs.size - 1)
        }
    }

    if (!logsEnabled) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(999f)
    ) {
        // Toggle button — visible in top-right corner when logs are enabled in Dev tools
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 8.dp)
                .zIndex(1000f)
        ) {
            Button(
                onClick = { visible = !visible },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (visible) Color(0xFFCC0000) else Color(0xFF222222)
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = if (visible) "✕ LOG" else "🐛 LOG",
                    fontSize = 11.sp,
                    color = Color.White
                )
            }
        }

        // Log panel
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .background(Color(0xEE111111))
                    .border(1.dp, Color(0xFF444444), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            ) {
                // Header row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A1A))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Log count + pause indicator
                    Text(
                        text = buildString {
                            append("Logs (${displayLogs.size}/1000)")
                            if (paused) append("  ⏸ PAUSED")
                        },
                        color = if (paused) Color(0xFFFFD740) else Color(0xFFAAAAAA),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Scroll to bottom
                        Text(
                            "↓",
                            color = Color(0xFF4CAF50),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    if (displayLogs.isNotEmpty())
                                        listState.animateScrollToItem(displayLogs.size - 1)
                                }
                            }
                        )

                        // Pause / Resume
                        Text(
                            text = if (paused) "▶ RUN" else "⏸ PAUSE",
                            color = if (paused) Color(0xFF4CAF50) else Color(0xFFFFD740),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable {
                                if (!paused) {
                                    // Take snapshot of current logs when pausing
                                    pausedSnapshot = liveLogs.toList()
                                }
                                paused = !paused
                            }
                        )

                        // Copy to clipboard
                        Text(
                            "COPY",
                            color = Color(0xFF64B5F6),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable {
                                val text = if (paused)
                                    pausedSnapshot.joinToString("\n") { e ->
                                        val l = when (e.level) { Log.ERROR -> "E"; Log.WARN -> "W"; Log.DEBUG -> "D"; else -> "I" }
                                        "${e.time} $l [${e.tag}] ${e.message}"
                                    }
                                else DebugLogCollector.exportText()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("AudiobasicsLogs", text))
                            }
                        )

                        // Clear
                        Text(
                            "CLEAR",
                            color = Color(0xFFFF5722),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable {
                                DebugLogCollector.clear()
                                pausedSnapshot = emptyList()
                                paused = false
                            }
                        )
                    }
                }

                // Log lines
                if (displayLogs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No logs yet — play a song!", color = Color(0xFF666666), fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        items(displayLogs) { entry ->
                            val color = when (entry.level) {
                                Log.ERROR -> Color(0xFFFF5252)
                                Log.WARN  -> Color(0xFFFFD740)
                                Log.DEBUG -> Color(0xFF69F0AE)
                                else      -> Color(0xFFCCCCCC)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    entry.time,
                                    color = Color(0xFF666666),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(52.dp)
                                )
                                Text(
                                    "[${entry.tag}]",
                                    color = Color(0xFF888888),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(80.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    entry.message,
                                    color = color,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
