package com.yt.lite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.yt.lite.data.Album
import com.yt.lite.ui.*
import com.yt.lite.ui.theme.AppTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MusicViewModel = viewModel()
            val isDarkMode by vm.isDarkMode.collectAsState()
            AppTheme(darkTheme = isDarkMode) {
                AudiobasicsApp(vm = vm, isDarkMode = isDarkMode)
            }
        }
    }
}

sealed class Screen {
    object Home : Screen()
    object Search : Screen()
    object Queue : Screen()
    object Settings : Screen()
    object Liked : Screen()
    object Albums : Screen()
    data class AlbumDetail(val album: Album) : Screen()
}

@UnstableApi
@Composable
fun AudiobasicsApp(vm: MusicViewModel, isDarkMode: Boolean) {
    val currentSong by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()
    val showStorageLow by vm.showStorageLow.collectAsState()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var showPlayerDialog by remember { mutableStateOf(false) }
    var screenStack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }

    fun navigate(screen: Screen) {
        screenStack = screenStack + screen
        currentScreen = screen
    }

    fun navigateBack() {
        if (screenStack.size > 1) {
            screenStack = screenStack.dropLast(1)
            currentScreen = screenStack.last()
        }
    }

    // Storage low popup
    if (showStorageLow) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.dismissStorageLow() },
            title = {
                androidx.compose.material3.Text(
                    "Storage Low",
                    fontFamily = com.yt.lite.ui.theme.NothingFont
                )
            },
            text = {
                androidx.compose.material3.Text(
                    "Cannot cache song — less than 1GB storage available.",
                    fontFamily = com.yt.lite.ui.theme.NothingFont
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { vm.dismissStorageLow() }
                ) {
                    androidx.compose.material3.Text(
                        "OK",
                        fontFamily = com.yt.lite.ui.theme.NothingFont,
                        color = androidx.compose.ui.graphics.Color.Red
                    )
                }
            }
        )
    }

    // Player dialog
    if (showPlayerDialog && currentSong != null) {
        PlayerDialog(
            vm = vm,
            isDarkMode = isDarkMode,
            onDismiss = { showPlayerDialog = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Main content
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.weight(1f)
        ) {
            when (val screen = currentScreen) {
                is Screen.Home -> HomeScreen(
                    vm = vm,
                    onNavigateSearch = { navigate(Screen.Search) },
                    onNavigateQueue = { navigate(Screen.Queue) },
                    onNavigateSettings = { navigate(Screen.Settings) },
                    onNavigateLiked = { navigate(Screen.Liked) },
                    onNavigateAlbums = { navigate(Screen.Albums) }
                )
                is Screen.Search -> SearchScreen(
                    vm = vm,
                    isDarkMode = isDarkMode,
                    onBack = { navigateBack() },
                    onNavigateQueue = { navigate(Screen.Queue) }
                )
                is Screen.Queue -> QueueScreen(
                    vm = vm,
                    isDarkMode = isDarkMode,
                    onBack = { navigateBack() }
                )
                is Screen.Settings -> SettingsScreen(
                    vm = vm,
                    isDarkMode = isDarkMode,
                    onBack = { navigateBack() }
                )
                is Screen.Liked -> LikedScreen(
                    vm = vm,
                    isDarkMode = isDarkMode,
                    onBack = { navigateBack() },
                    onNavigateQueue = { navigate(Screen.Queue) }
                )
                is Screen.Albums -> SavedAlbumsScreen(
                    vm = vm,
                    isDarkMode = isDarkMode,
                    onBack = { navigateBack() },
                    onNavigateQueue = { navigate(Screen.Queue) },
                    onAlbumClick = { album -> navigate(Screen.AlbumDetail(album)) }
                )
                is Screen.AlbumDetail -> AlbumScreen(
                    vm = vm,
                    album = screen.album,
                    isDarkMode = isDarkMode,
                    onBack = { navigateBack() },
                    onNavigateQueue = { navigate(Screen.Queue) }
                )
            }
        }

        // Mini player bar
        currentSong?.let { song ->
            val isLiked = likedSongs.any { it.id == song.id }
            PlayerBar(
                song = song,
                isPlaying = isPlaying,
                isLoading = isLoading,
                isLiked = isLiked,
                isDarkMode = isDarkMode,
                onToggle = vm::togglePlayPause,
                onLike = { vm.toggleLike(song) },
                onTap = { showPlayerDialog = true }
            )
        }
    }
}
Also update app/src/main/java/com/yt/lite/ui/theme/Theme.kt — replace everything to support dark mode:
package com.yt.lite.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFFF0000),
    onPrimary = Color.White,
    secondary = Color(0xFF1A1A1A),
    onSecondary = Color.White,
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFF666666),
    error = Color(0xFFB00020),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF0000),
    onPrimary = Color.White,
    secondary = Color(0xFFEEEEEE),
    onSecondary = Color.Black,
    background = Color(0xFF121212),
    onBackground = Color.White,
    surface = Color(0xFF1E1E1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFAAAAAA),
    error = Color(0xFFCF6679),
    onError = Color.Black,
)

@Composable
fun AppTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
