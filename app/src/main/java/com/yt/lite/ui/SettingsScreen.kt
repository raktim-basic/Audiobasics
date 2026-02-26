package com.yt.lite.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationCompat
import com.yt.lite.ui.theme.NothingFont

@Composable
fun SettingsScreen(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val likedSongs by vm.likedSongs.collectAsState()
    val cacheSize by vm.cacheSize.collectAsState()

    var showUpdater by remember { mutableStateOf(false) }
    var showImportWarning by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)

    // Export launcher
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { vm.exportData(context, it) }
    }

    // Import launcher
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            pendingImportUri = it
            showImportWarning = true
        }
    }

    // Import warning dialog
    if (showImportWarning) {
        Dialog(onDismissRequest = { showImportWarning = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(surfaceColor)
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "Import Warning",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = textColor
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "This will replace your current liked songs and saved albums. All existing data will be lost. Are you sure?",
                        fontFamily = NothingFont,
                        fontSize = 14.sp,
                        color = subTextColor
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showImportWarning = false }) {
                            Text("Cancel", fontFamily = NothingFont, color = Color.Gray)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Red)
                                .clickable {
                                    showImportWarning = false
                                    pendingImportUri?.let { vm.importData(context, it) }
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                "Import",
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }

    // Updater screen
    if (showUpdater) {
        Dialog(onDismissRequest = { showUpdater = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(surfaceColor)
                    .padding(4.dp)
            ) {
                UpdaterScreen()
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Settings",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = textColor
            )
        }

        DashedDivider(modifier = Modifier.fillMaxWidth())

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {

            // Dark mode toggle
            item {
                SettingsRow(
                    isDarkMode = isDarkMode,
                    title = if (isDarkMode) "Light Mode" else "Dark Mode",
                    subtitle = if (isDarkMode) "Switch to light theme" else "Switch to dark theme",
                    icon = {
                        Icon(
                            imageVector = if (isDarkMode) Icons.Default.LightMode
                            else Icons.Default.DarkMode,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    onClick = { vm.toggleDarkMode() }
                )
            }

            // Divider
            item { SettingsDivider(isDarkMode) }

            // Cache section header
            item {
                Text(
                    text = "CACHE",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color.Red,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            // Cache size display
            item {
                SettingsRow(
                    isDarkMode = isDarkMode,
                    title = "Cache Size",
                    subtitle = if (cacheSize.isBlank() || cacheSize == "0KB") "No cache"
                    else cacheSize,
                    icon = {},
                    onClick = {}
                )
            }

            // Cache all liked
            item {
                SettingsRow(
                    isDarkMode = isDarkMode,
                    title = "Cache All Liked Songs",
                    subtitle = "${likedSongs.count { !it.isCached }} songs not cached",
                    icon = {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    onClick = {
                        vm.cacheAllLiked()
                        showCacheNotification(context, likedSongs.count { !it.isCached })
                    }
                )
            }

            // Divider
            item { SettingsDivider(isDarkMode) }

            // Data section header
            item {
                Text(
                    text = "DATA",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color.Red,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            // Export
            item {
                SettingsRow(
                    isDarkMode = isDarkMode,
                    title = "Export Data",
                    subtitle = "Save liked songs and albums to file",
                    icon = {
                        Icon(
                            Icons.Default.Upload,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    onClick = {
                        exportLauncher.launch("audiobasics_backup.json")
                    }
                )
            }

            // Import
            item {
                SettingsRow(
                    isDarkMode = isDarkMode,
                    title = "Import Data",
                    subtitle = "Restore liked songs and albums from file",
                    icon = {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    onClick = {
                        importLauncher.launch(arrayOf("application/json"))
                    }
                )
            }

            // Divider
            item { SettingsDivider(isDarkMode) }

            // Version section header
            item {
                Text(
                    text = "VERSION AND UPDATE",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color.Red,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            // Updater
            item {
                SettingsRow(
                    isDarkMode = isDarkMode,
                    title = "Updater",
                    subtitle = "Check for NewPipe Extractor updates",
                    icon = {
                        Icon(
                            Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    onClick = { showUpdater = true }
                )
            }

            // App version
            item {
                SettingsRow(
                    isDarkMode = isDarkMode,
                    title = "App Version",
                    subtitle = "2.0.0",
                    icon = {},
                    onClick = {}
                )
            }
        }

        // Bottom bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8))
                .padding(vertical = 12.dp, horizontal = 20.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = textColor,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
fun SettingsRow(
    isDarkMode: Boolean,
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = textColor
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontFamily = NothingFont,
                    fontSize = 12.sp,
                    color = subTextColor
                )
            }
        }
    }
}

@Composable
fun SettingsDivider(isDarkMode: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFEEEEEE))
    )
}

private fun showCacheNotification(context: Context, count: Int) {
    if (count == 0) return
    val channelId = "cache_channel"
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(
        channelId,
        "Cache Progress",
        NotificationManager.IMPORTANCE_LOW
    )
    manager.createNotificationChannel(channel)

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("Caching songs")
        .setContentText("Caching $count liked songs in background")
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setAutoCancel(true)
        .build()

    manager.notify(1001, notification)
}
