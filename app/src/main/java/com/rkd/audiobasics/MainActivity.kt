package com.rkd.audiobasics

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import com.rkd.audiobasics.ui.DebugLogOverlay
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.data.Artist
import com.rkd.audiobasics.data.db.PlaylistEntity
import com.rkd.audiobasics.ui.AddToPlaylistSheet
import com.rkd.audiobasics.ui.AlbumScreen
import com.rkd.audiobasics.ui.APP_CURRENT_VERSION
import com.rkd.audiobasics.ui.ArtistScreen
import com.rkd.audiobasics.ui.CreatePlaylistDialog
import com.rkd.audiobasics.ui.CustomPlaylistScreen
import com.rkd.audiobasics.ui.EngineInfoScreen
import com.rkd.audiobasics.ui.HomeScreen
import com.rkd.audiobasics.ui.LibraryScreen
import com.rkd.audiobasics.ui.LikedScreen
import com.rkd.audiobasics.ui.MusicViewModel
import com.rkd.audiobasics.ui.PlayerBar
import com.rkd.audiobasics.ui.PlayerDialog
import com.rkd.audiobasics.ui.QueueScreen
import com.rkd.audiobasics.ui.SavedAlbumsScreen
import com.rkd.audiobasics.ui.SearchAlbumsScreen
import com.rkd.audiobasics.ui.SearchArtistsScreen
import com.rkd.audiobasics.ui.SearchScreen
import com.rkd.audiobasics.ui.SettingsScreen
import com.rkd.audiobasics.ui.UpdaterScreen
import com.rkd.audiobasics.ui.fetchLatestAppVersion
import com.rkd.audiobasics.ui.theme.AppTheme
import com.rkd.audiobasics.ui.theme.NothingFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

private const val NOTIF_CHANNEL_ID = "audiobasics_updates"
private const val NOTIF_ID = 1001

@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        createNotificationChannel()
        checkForUpdateAndNotify()

        setContent {
            val vm: MusicViewModel = viewModel(
                factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
            )
            val isDarkMode by vm.isDarkMode.collectAsState()

            val openUpdater = intent.getBooleanExtra("OPEN_UPDATER", false)
            if (openUpdater) vm.triggerUpdater()

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        vm.syncState()
                        vm.checkForUpdate()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            AppTheme(darkTheme = isDarkMode) {
                val logsEnabled by vm.logsEnabled.collectAsState()
                Box(modifier = Modifier.fillMaxSize()) {
                    AudiobasicsApp(vm = vm, isDarkMode = isDarkMode)
                    DebugLogOverlay(logsEnabled = logsEnabled)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { setIntent(it) }
        if (intent?.getBooleanExtra("OPEN_UPDATER", false) == true) {
            val vm = ViewModelProvider(
                this,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application)
            )[MusicViewModel::class.java]
            vm.triggerUpdater()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifications for new Audiobasics updates" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun checkForUpdateAndNotify() {
        CoroutineScope(Dispatchers.IO).launch {
            val latest = fetchLatestAppVersion() ?: return@launch
            if (latest == APP_CURRENT_VERSION) return@launch
            val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                putExtra("OPEN_UPDATER", true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this@MainActivity, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this@MainActivity, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Audiobasics update available")
                .setContentText("Version $latest is now available. Tap to update.")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
        }
    }
}

sealed class Screen {
    object Home : Screen()
    object Search : Screen()
    object Queue : Screen()
    data class Settings(val openCache: Boolean = false, val openLibrary: Boolean = false) : Screen()
    object Liked : Screen()
    object Albums : Screen()
    object Library : Screen()
    object Updater : Screen()
    object EngineInfo : Screen()
    data class AlbumDetail(val album: Album) : Screen()
    data class ArtistDetail(val artistName: String, val artistBrowseId: String = "") : Screen()
    data class SearchAlbums(val query: String) : Screen()
    data class SearchArtists(val query: String) : Screen()
    data class CustomPlaylist(val playlist: PlaylistEntity) : Screen()
}

@UnstableApi
@Composable
fun AudiobasicsApp(
    vm: MusicViewModel,
    isDarkMode: Boolean
) {
    val currentSong by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val showStorageLow by vm.showStorageLow.collectAsState()
    val navigateToUpdater by vm.navigateToUpdater.collectAsState()

    var screenStack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    val currentScreen = screenStack.last()
    var showPlayerDialog by remember { mutableStateOf(false) }
    var addToSheetSong by remember { mutableStateOf<com.rkd.audiobasics.data.Song?>(null) }
    var showCreatePlaylistFromSheet by remember { mutableStateOf(false) }

    LaunchedEffect(navigateToUpdater) {
        if (navigateToUpdater) {
            screenStack = listOf(Screen.Home, Screen.Settings(), Screen.Updater)
            vm.onUpdaterNavigated()
        }
    }

    fun navigate(screen: Screen) { screenStack = screenStack + screen }
    fun navigateBack() {
        if (screenStack.size > 1) {
            if (screenStack.last() is Screen.Search) vm.clearSearch()
            screenStack = screenStack.dropLast(1)
        }
    }

    BackHandler(enabled = screenStack.size > 1) { navigateBack() }

    if (showStorageLow) {
        AlertDialog(
            onDismissRequest = { vm.dismissStorageLow() },
            title = { Text("Storage Low", fontFamily = NothingFont) },
            text = { Text("Cannot download song — less than 1GB storage available.", fontFamily = NothingFont) },
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
            onDismiss = { showPlayerDialog = false },
            onNavigateQueue = { showPlayerDialog = false; navigate(Screen.Queue) },
            onNavigateArtist = { name -> showPlayerDialog = false; navigate(Screen.ArtistDetail(name)) },
            onNavigateAlbum = { albumTitle ->
                showPlayerDialog = false
                // Search for the album by name rather than browsing this specific id —
                // YTM itself sometimes has more than one catalog entry for what's really
                // the same album, so search reliably lands on a real, complete result
                // instead of risking opening a different, possibly-incomplete duplicate.
                navigate(Screen.SearchAlbums(albumTitle))
            }
        )
    }

    // Global Add-to-playlist sheet
    addToSheetSong?.let { song ->
        AddToPlaylistSheet(
            song = song,
            vm = vm,
            isDarkMode = isDarkMode,
            onDismiss = { addToSheetSong = null },
            onCreateNew = { showCreatePlaylistFromSheet = true }
        )
    }
    if (showCreatePlaylistFromSheet) {
        CreatePlaylistDialog(
            isDarkMode = isDarkMode,
            onDismiss = { showCreatePlaylistFromSheet = false },
            onCreate = { name, emoji ->
                vm.createPlaylist(name, emoji)
                showCreatePlaylistFromSheet = false
            }
        )
    }

    val rootBgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(rootBgColor)
            .systemBarsPadding()
            .imePadding()
    ) {
        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    val goingDeeper = screenStack.size > 1 && targetState != Screen.Home
                    if (goingDeeper)
                        (scaleIn(initialScale = 0.93f) + fadeIn()) togetherWith
                                (scaleOut(targetScale = 0.97f) + fadeOut())
                    else
                        (scaleIn(initialScale = 1.03f) + fadeIn()) togetherWith
                                (scaleOut(targetScale = 1.07f) + fadeOut())
                },
                label = "screen_transition"
            ) { screen ->
                when (screen) {
                    is Screen.Home -> HomeScreen(
                        vm = vm,
                        onNavigateSearch = { navigate(Screen.Search) },
                        onNavigateQueue = { navigate(Screen.Queue) },
                        onNavigateSettings = { navigate(Screen.Settings()) },
                        onNavigateLiked = { navigate(Screen.Liked) },
                        onNavigateAlbums = { navigate(Screen.Albums) },
                        onNavigateLibrary = { navigate(Screen.Library) }
                    )
                    is Screen.Search -> SearchScreen(
                        vm = vm,
                        isDarkMode = isDarkMode,
                        onBack = { navigateBack() },
                        onNavigateQueue = { navigate(Screen.Queue) },
                        onAlbumClick = { album -> navigate(Screen.AlbumDetail(album)) },
                        onNavigateAlbums = { q -> navigate(Screen.SearchAlbums(q)) },
                        onNavigateArtists = { q -> navigate(Screen.SearchArtists(q)) },
                        onAddTo = { song -> addToSheetSong = song }
                    )
                    is Screen.Queue -> QueueScreen(
                        vm = vm,
                        isDarkMode = isDarkMode,
                        onBack = { navigateBack() },
                        onAddTo = { song -> addToSheetSong = song }
                    )
                    is Screen.Settings -> SettingsScreen(
                        vm = vm,
                        isDarkMode = isDarkMode,
                        openCache = screen.openCache,
                        openLibrary = screen.openLibrary,
                        onBack = { navigateBack() },
                        onNavigateUpdater = { navigate(Screen.Updater) }
                    )
                    is Screen.Liked -> LikedScreen(
                        vm = vm,
                        isDarkMode = isDarkMode,
                        onBack = { navigateBack() },
                        onNavigateQueue = { navigate(Screen.Queue) },
                        onNavigateCacheSettings = { navigate(Screen.Settings(openCache = true)) },
                        onAddTo = { song -> addToSheetSong = song }
                    )
                    is Screen.Albums -> SavedAlbumsScreen(
                        vm = vm,
                        isDarkMode = isDarkMode,
                        onBack = { navigateBack() },
                        onNavigateQueue = { navigate(Screen.Queue) },
                        onAlbumClick = { album -> navigate(Screen.AlbumDetail(album)) }
                    )
                    is Screen.Library -> LibraryScreen(
                        vm = vm,
                        onBack = { navigateBack() },
                        onNavigateLiked = { navigate(Screen.Liked) },
                        onNavigateAlbums = { navigate(Screen.Albums) },
                        onNavigatePlaylist = { playlist -> navigate(Screen.CustomPlaylist(playlist)) },
                        onNavigateQueue = { navigate(Screen.Queue) }
                    )
                    is Screen.Updater -> UpdaterScreen(
                        vm = vm,
                        isDarkMode = isDarkMode,
                        onBack = { navigateBack() },
                        onEngineInfo = { navigate(Screen.EngineInfo) },
                        onNavigateLibrary = { navigate(Screen.Settings(openLibrary = true)) }
                    )
                    is Screen.EngineInfo -> EngineInfoScreen(
                        isDarkMode = isDarkMode,
                        onBack = { navigateBack() }
                    )
                    is Screen.AlbumDetail -> AlbumScreen(
                        vm = vm,
                        album = screen.album,
                        isDarkMode = isDarkMode,
                        onBack = { navigateBack() },
                        onNavigateQueue = { navigate(Screen.Queue) },
                        onNavigateArtist = { name -> navigate(Screen.ArtistDetail(name)) },
                        onAddTo = { song -> addToSheetSong = song },
                        onNavigateCacheSettings = { navigate(Screen.Settings(openCache = true)) }
                    )
                    is Screen.ArtistDetail -> ArtistScreen(
                        vm = vm,
                        artistName = screen.artistName,
                        artistBrowseId = screen.artistBrowseId,
                        isDarkMode = isDarkMode,
                        onBack = { navigateBack() },
                        onAlbumClick = { album -> navigate(Screen.AlbumDetail(album)) },
                        onAddTo = { song -> addToSheetSong = song },
                        onNavigateQueue = { navigate(Screen.Queue) }
                    )
                    is Screen.SearchAlbums -> SearchAlbumsScreen(
                        vm = vm,
                        query = screen.query,
                        isDarkMode = isDarkMode,
                        onBack = { navigateBack() },
                        onAlbumClick = { album -> navigate(Screen.AlbumDetail(album)) }
                    )
                    is Screen.SearchArtists -> SearchArtistsScreen(
                        vm = vm,
                        query = screen.query,
                        isDarkMode = isDarkMode,
                        onBack = { navigateBack() },
                        onArtistClick = { artist -> navigate(Screen.ArtistDetail(artist.name, artist.id)) }
                    )
                    is Screen.CustomPlaylist -> CustomPlaylistScreen(
                        vm = vm,
                        playlist = screen.playlist,
                        isDarkMode = isDarkMode,
                        onBack = { navigateBack() },
                        onAddTo = { song -> addToSheetSong = song },
                        onNavigateQueue = { navigate(Screen.Queue) },
                        onNavigateCacheSettings = { navigate(Screen.Settings(openCache = true)) }
                    )
                }
            }
        }

        PlayerBar(
            vm = vm,
            song = currentSong,
            isPlaying = isPlaying,
            isLoading = isLoading,
            isDarkMode = isDarkMode,
            onToggle = vm::togglePlayPause,
            onAddTo = { currentSong?.let { addToSheetSong = it } },
            onTap = { showPlayerDialog = true }
        )
    }
}
