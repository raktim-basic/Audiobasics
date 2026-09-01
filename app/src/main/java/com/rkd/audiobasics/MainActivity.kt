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
import androidx.activity.compose.setContent
import com.rkd.audiobasics.ui.DebugLogOverlay
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
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
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.media3.common.util.UnstableApi
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.data.Artist
import com.rkd.audiobasics.data.db.PlaylistEntity
import com.rkd.audiobasics.navigation.AlbumDetailKey
import com.rkd.audiobasics.navigation.AlbumsKey
import com.rkd.audiobasics.navigation.ArtistDetailKey
import com.rkd.audiobasics.navigation.CustomPlaylistKey
import com.rkd.audiobasics.navigation.EngineInfoKey
import com.rkd.audiobasics.navigation.HomeKey
import com.rkd.audiobasics.navigation.LibraryKey
import com.rkd.audiobasics.navigation.LikedKey
import com.rkd.audiobasics.navigation.QueueKey
import com.rkd.audiobasics.navigation.SearchAlbumsKey
import com.rkd.audiobasics.navigation.SearchArtistsKey
import com.rkd.audiobasics.navigation.SearchKey
import com.rkd.audiobasics.navigation.SettingsKey
import com.rkd.audiobasics.navigation.UpdaterKey
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
import com.rkd.audiobasics.ui.resetCustomPlaylistScroll
import com.rkd.audiobasics.ui.resetLikedScreenScroll
import com.rkd.audiobasics.ui.theme.AppTheme
import com.rkd.audiobasics.ui.theme.NothingFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

private const val NOTIF_CHANNEL_ID = "audiobasics_updates"
private const val NOTIF_ID = 1001

// Matches umihi's Constants.Animation.NAVIGATION_DURATION — snappier than Compose's
// 300ms defaults.
private const val NAV_ANIMATION_DURATION_MS = 200

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("OPEN_UPDATER", false)) {
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

    val backStack = rememberNavBackStack(HomeKey)
    var showPlayerDialog by remember { mutableStateOf(false) }
    var addToSheetSong by remember { mutableStateOf<com.rkd.audiobasics.data.Song?>(null) }
    var showCreatePlaylistFromSheet by remember { mutableStateOf(false) }

    LaunchedEffect(navigateToUpdater) {
        if (navigateToUpdater) {
            backStack.clear()
            backStack.addAll(listOf(HomeKey, SettingsKey(), UpdaterKey))
            vm.onUpdaterNavigated()
        }
    }

    // Navigation goes straight through backStack.add(...) at each call site below (push ->
    // NavDisplay's transitionSpec). navigateBack() below handles pops (-> popTransitionSpec)
    // and is also what the system/predictive back gesture calls via onBack (->
    // predictivePopTransitionSpec). Unlike the old manual screenStack, direction is never
    // guessed here — NavDisplay always knows push from pop.
    fun navigateBack() {
        if (backStack.size > 1) {
            when (val leaving = backStack.last()) {
                is SearchKey -> vm.clearSearch()
                is LikedKey -> resetLikedScreenScroll()
                is CustomPlaylistKey -> resetCustomPlaylistScroll(leaving.playlist.id)
                else -> {}
            }
            backStack.removeLastOrNull()
        }
    }

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
            onNavigateQueue = { showPlayerDialog = false; backStack.add(QueueKey) },
            onNavigateArtist = { name, artistId ->
                showPlayerDialog = false
                backStack.add(ArtistDetailKey(name, artistId ?: ""))
            },
            onNavigateAlbum = { albumTitle ->
                showPlayerDialog = false
                // Search for the album by name rather than browsing this specific id —
                // YTM itself sometimes has more than one catalog entry for what's really
                // the same album, so search reliably lands on a real, complete result
                // instead of risking opening a different, possibly-incomplete duplicate.
                backStack.add(SearchAlbumsKey(albumTitle))
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
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize(),
                onBack = { navigateBack() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                transitionSpec = {
                    (scaleIn(
                        animationSpec = tween(NAV_ANIMATION_DURATION_MS),
                        initialScale = 0.85f
                    ) + fadeIn(animationSpec = tween(NAV_ANIMATION_DURATION_MS))) togetherWith
                            (scaleOut(
                                animationSpec = tween(NAV_ANIMATION_DURATION_MS),
                                targetScale = 1.1f
                            ) + fadeOut(animationSpec = tween(NAV_ANIMATION_DURATION_MS)))
                },
                popTransitionSpec = {
                    (scaleIn(
                        animationSpec = tween(NAV_ANIMATION_DURATION_MS),
                        initialScale = 1.1f
                    ) + fadeIn(animationSpec = tween(NAV_ANIMATION_DURATION_MS))) togetherWith
                            (scaleOut(
                                animationSpec = tween(NAV_ANIMATION_DURATION_MS),
                                targetScale = 0.85f
                            ) + fadeOut(animationSpec = tween(NAV_ANIMATION_DURATION_MS)))
                },
                predictivePopTransitionSpec = {
                    (scaleIn(
                        animationSpec = tween(NAV_ANIMATION_DURATION_MS),
                        initialScale = 1.1f
                    ) + fadeIn(animationSpec = tween(NAV_ANIMATION_DURATION_MS))) togetherWith
                            (scaleOut(
                                animationSpec = tween(NAV_ANIMATION_DURATION_MS),
                                targetScale = 0.85f
                            ) + fadeOut(animationSpec = tween(NAV_ANIMATION_DURATION_MS)))
                },
                entryProvider = { key ->
                    when (key) {
                        is HomeKey -> NavEntry(key) {
                            HomeScreen(
                                vm = vm,
                                onNavigateSearch = { backStack.add(SearchKey) },
                                onNavigateQueue = { backStack.add(QueueKey) },
                                onNavigateSettings = { backStack.add(SettingsKey()) },
                                onNavigateLiked = { backStack.add(LikedKey) },
                                onNavigateAlbums = { backStack.add(AlbumsKey) },
                                onNavigateLibrary = { backStack.add(LibraryKey) }
                            )
                        }
                        is SearchKey -> NavEntry(key) {
                            SearchScreen(
                                vm = vm,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onNavigateQueue = { backStack.add(QueueKey) },
                                onAlbumClick = { album -> backStack.add(AlbumDetailKey(album)) },
                                onNavigateAlbums = { q -> backStack.add(SearchAlbumsKey(q)) },
                                onNavigateArtists = { q -> backStack.add(SearchArtistsKey(q)) },
                                onAddTo = { song -> addToSheetSong = song }
                            )
                        }
                        is QueueKey -> NavEntry(key) {
                            QueueScreen(
                                vm = vm,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onAddTo = { song -> addToSheetSong = song }
                            )
                        }
                        is SettingsKey -> NavEntry(key) {
                            SettingsScreen(
                                vm = vm,
                                isDarkMode = isDarkMode,
                                openCache = key.openCache,
                                openLibrary = key.openLibrary,
                                onBack = { navigateBack() },
                                onNavigateUpdater = { backStack.add(UpdaterKey) }
                            )
                        }
                        is LikedKey -> NavEntry(key) {
                            LikedScreen(
                                vm = vm,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onNavigateQueue = { backStack.add(QueueKey) },
                                onNavigateCacheSettings = { backStack.add(SettingsKey(openCache = true)) },
                                onAddTo = { song -> addToSheetSong = song }
                            )
                        }
                        is AlbumsKey -> NavEntry(key) {
                            SavedAlbumsScreen(
                                vm = vm,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onNavigateQueue = { backStack.add(QueueKey) },
                                onAlbumClick = { album -> backStack.add(AlbumDetailKey(album)) }
                            )
                        }
                        is LibraryKey -> NavEntry(key) {
                            LibraryScreen(
                                vm = vm,
                                onBack = { navigateBack() },
                                onNavigateLiked = { backStack.add(LikedKey) },
                                onNavigateAlbums = { backStack.add(AlbumsKey) },
                                onNavigatePlaylist = { playlist -> backStack.add(CustomPlaylistKey(playlist)) },
                                onNavigateQueue = { backStack.add(QueueKey) }
                            )
                        }
                        is UpdaterKey -> NavEntry(key) {
                            UpdaterScreen(
                                vm = vm,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onEngineInfo = { backStack.add(EngineInfoKey) },
                                onNavigateLibrary = { backStack.add(SettingsKey(openLibrary = true)) }
                            )
                        }
                        is EngineInfoKey -> NavEntry(key) {
                            EngineInfoScreen(
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() }
                            )
                        }
                        is AlbumDetailKey -> NavEntry(key) {
                            AlbumScreen(
                                vm = vm,
                                album = key.album,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onNavigateQueue = { backStack.add(QueueKey) },
                                onNavigateArtist = { name, artistId ->
                                    backStack.add(ArtistDetailKey(name, artistId ?: ""))
                                },
                                onAddTo = { song -> addToSheetSong = song },
                                onNavigateCacheSettings = { backStack.add(SettingsKey(openCache = true)) }
                            )
                        }
                        is ArtistDetailKey -> NavEntry(key) {
                            ArtistScreen(
                                vm = vm,
                                artistName = key.artistName,
                                artistBrowseId = key.artistBrowseId,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onAlbumClick = { album -> backStack.add(AlbumDetailKey(album)) },
                                onAddTo = { song -> addToSheetSong = song },
                                onNavigateQueue = { backStack.add(QueueKey) }
                            )
                        }
                        is SearchAlbumsKey -> NavEntry(key) {
                            SearchAlbumsScreen(
                                vm = vm,
                                query = key.query,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onAlbumClick = { album -> backStack.add(AlbumDetailKey(album)) }
                            )
                        }
                        is SearchArtistsKey -> NavEntry(key) {
                            SearchArtistsScreen(
                                vm = vm,
                                query = key.query,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onArtistClick = { artist -> backStack.add(ArtistDetailKey(artist.name, artist.id)) }
                            )
                        }
                        is CustomPlaylistKey -> NavEntry(key) {
                            CustomPlaylistScreen(
                                vm = vm,
                                playlist = key.playlist,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onAddTo = { song -> addToSheetSong = song },
                                onNavigateQueue = { backStack.add(QueueKey) },
                                onNavigateCacheSettings = { backStack.add(SettingsKey(openCache = true)) }
                            )
                        }
                        else -> error("Unknown nav key: $key")
                    }
                }
            )
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
