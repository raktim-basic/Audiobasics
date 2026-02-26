package com.yt.lite

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.yt.lite.data.Album
import com.yt.lite.ui.AlbumScreen
import com.yt.lite.ui.HomeScreen
import com.yt.lite.ui.LikedScreen
import com.yt.lite.ui.MusicViewModel
import com.yt.lite.ui.PlayerBar
import com.yt.lite.ui.PlayerDialog
import com.yt.lite.ui.QueueScreen
import com.yt.lite.ui.SavedAlbumsScreen
import com.yt.lite.ui.SearchScreen
import com.yt.lite.ui.SettingsScreen
import com.yt.lite.ui.theme.AppTheme
import com.yt.lite.ui.theme.NothingFont

@UnstableApi
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val vm: MusicViewModel = viewModel()
            val isDarkMode by vm.isDarkMode.collectAsState()

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        vm.syncState()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

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

    var screenStack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    val currentScreen = screenStack.last()
    var showPlayerDialog by remember { mutableStateOf(false) }

    fun navigate(screen: Screen) {
        screenStack = screenStack + screen
    }

    fun navigateBack() {
        if (screenStack.size > 1) {
            screenStack = screenStack.dropLast(1)
        }
    }

    BackHandler(enabled = screenStack.size > 1) {
        navigateBack()
    }

    if (showStorageLow) {
        AlertDialog(
            onDismissRequest = { vm.dismissStorageLow() },
            title = { Text("Storage Low", fontFamily = NothingFont) },
            text = {
                Text(
                    "Cannot cache song — less than 1GB storage available.",
                    fontFamily = NothingFont
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissStorageLow() }) {
                    Text("OK", fontFamily = NothingFont, color = Color.Red)
                }
            }
        )
    }

    if (showPlayerDialog && currentSong != null) {
        PlayerDialog(
            vm = vm,
            isDarkMode = isDarkMode,
            onDismiss = { showPlayerDialog = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    val goingDeeper = screenStack.size > 1 &&
                            targetState != Screen.Home
                    if (goingDeeper) {
                        (scaleIn(initialScale = 0.93f) + fadeIn()) togetherWith
                                (scaleOut(targetScale = 0.97f) + fadeOut())
                    } else {
                        (scaleIn(initialScale = 1.03f) + fadeIn()) togetherWith
                                (scaleOut(targetScale = 1.07f) + fadeOut())
                    }
                },
                label = "screen_transition"
            ) { screen ->
                when (screen) {
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
                        onNavigateQueue = { navigate(Screen.Queue) },
                        onAlbumClick = { album -> navigate(Screen.AlbumDetail(album)) }
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
        }

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
