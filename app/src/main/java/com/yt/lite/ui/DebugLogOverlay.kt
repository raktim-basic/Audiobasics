package com.yt.lite.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.yt.lite.BuildConfig
import kotlinx.coroutines.launch

// ── Log Interceptor ──────────────────────────────────────────────────────────

object DebugLogCollector {
    private val _logs = mutableStateListOf<LogEntry>()
    val logs: List<LogEntry> get() = _logs

    data class LogEntry(
        val tag: String,
        val message: String,
        val level: Int,   // Log.DEBUG=3, Log.WARN=5, Log.ERROR=6
        val time: String
    )

    // Call this from AudiobasicsApp.onCreate() to intercept logs
    fun install() {
        if (!BuildConfig.DEBUG) return
        val origHandler = Thread.getDefaultUncaughtExceptionHandler()
        // We hook into Timber via a custom tree - see AudiobasicsApp
    }

    fun add(level: Int, tag: String?, message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _logs.add(LogEntry(tag ?: "?", message, level, time))
        // Keep max 200 lines to avoid memory issues
        if (_logs.size > 200) _logs.removeAt(0)
    }

    fun clear() = _logs.clear()
}

// ── Composable Overlay ───────────────────────────────────────────────────────

@Composable
fun DebugLogOverlay() {
    if (!BuildConfig.DEBUG) return  // Never shows in release builds

    var visible by remember { mutableStateOf(false) }
    val logs = DebugLogCollector.logs
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(999f)
    ) {
        // Toggle button — always visible in top-right corner
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
                    .fillMaxHeight(0.5f)
                    .background(Color(0xEE111111))
                    .border(1.dp, Color(0xFF444444), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A1A))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Debug Logs (${logs.size})",
                        color = Color(0xFFAAAAAA),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "↓ scroll",
                            color = Color(0xFF4CAF50),
                            fontSize = 10.sp,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    if (logs.isNotEmpty())
                                        listState.animateScrollToItem(logs.size - 1)
                                }
                            }
                        )
                        Text(
                            "CLEAR",
                            color = Color(0xFFFF5722),
                            fontSize = 10.sp,
                            modifier = Modifier.clickable { DebugLogCollector.clear() }
                        )
                    }
                }

                // Log lines
                if (logs.isEmpty()) {
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
                        items(logs) { entry ->
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
