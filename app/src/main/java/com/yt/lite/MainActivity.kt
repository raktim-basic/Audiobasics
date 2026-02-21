package com.yt.lite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yt.lite.ui.HomeScreen
import com.yt.lite.ui.MusicViewModel
import com.yt.lite.ui.PlayerBar
import com.yt.lite.ui.SearchScreen
import com.yt.lite.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme { YTLiteApp() }
        }
    }
}

@Composable
fun YTLiteApp() {
    val nav = rememberNavController()
    val vm: MusicViewModel = viewModel()
    val currentSong by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            Column {
                currentSong?.let { song ->
                    PlayerBar(
                        song = song,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        onToggle = vm::togglePlayPause
                    )
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = route == "home",
                        onClick = {
                            nav.navigate("home") {
                                popUpTo("home") { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Default.Home, "Home") },
                        label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = route == "search",
                        onClick = {
                            nav.navigate("search") {
                                popUpTo("home")
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Default.Search, "Search") },
                        label = { Text("Search") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(vm) }
            composable("search") { SearchScreen(vm) }
        }
    }
}
