package com.yt.lite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yt.lite.ui.HomeScreen
import com.yt.lite.ui.LikedScreen
import com.yt.lite.ui.MusicViewModel
import com.yt.lite.ui.PlayerBar
import com.yt.lite.ui.SearchScreen
import com.yt.lite.ui.theme.AppTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme { YTLiteApp() }
        }
    }
}

@UnstableApi
@Composable
fun YTLiteApp() {
    val nav = rememberNavController()
    val vm: MusicViewModel = viewModel()
    val currentSong by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            Column {
                currentSong?.let { song ->
                    val isLiked = likedSongs.any { it.id == song.id }
                    PlayerBar(
                        song = song,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        isLiked = isLiked,
                        onToggle = vm::togglePlayPause,
                        onLike = { vm.toggleLike(song) }
                    )
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = route == "queue",
                        onClick = {
                            nav.navigate("queue") {
                                popUpTo("queue") { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Default.QueueMusic, "Queue") },
                        label = { Text("Queue") }
                    )
                    NavigationBarItem(
                        selected = route == "search",
                        onClick = {
                            nav.navigate("search") {
                                popUpTo("queue")
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Default.Search, "Search") },
                        label = { Text("Search") }
                    )
                    NavigationBarItem(
                        selected = route == "liked",
                        onClick = {
                            nav.navigate("liked") {
                                popUpTo("queue")
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Default.Favorite, "Liked") },
                        label = { Text("Liked") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = "queue", modifier = Modifier.padding(padding)) {
            composable("queue") { HomeScreen(vm) }
            composable("search") { SearchScreen(vm) }
            composable("liked") { LikedScreen(vm) }
        }
    }
}
