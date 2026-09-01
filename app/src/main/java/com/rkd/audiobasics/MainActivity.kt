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
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.NavKey
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

// Settings, Updater, and EngineInfo together form the "settings flow" — Updater and
// EngineInfo are only ever reached by pushing deeper from Settings. Used below to tell
// "opening/closing the settings flow" (gets the special slide) apart from "navigating
// within it" (Settings -> Updater -> EngineInfo, or back, which keeps the scale+fade
// default) even though a second SettingsKey can itself be pushed from within the flow
// (Updater's onNavigateLibrary) — that push/pop still counts as "within."
private fun isSettingsSubtree(key: NavKey): Boolean =
    key is SettingsKey || key is UpdaterKey || key is EngineInfoKey

private fun defaultPushTransform(): ContentTransform =
    (scaleIn(
        animationSpec = tween(NAV_ANIMATION_DURATION_MS),
        initialScale = 0.85f
    ) + fadeIn(animationSpec = tween(NAV_ANIMATION_DURATION_MS))) togetherWith
            (scaleOut(
                animationSpec = tween(NAV_ANIMATION_DURATION_MS),
                targetScale = 1.1f
            ) + fadeOut(animationSpec = tween(NAV_ANIMATION_DURATION_MS)))

private fun defaultPopTransform(): ContentTransform =
    (scaleIn(
        animationSpec = tween(NAV_ANIMATION_DURATION_MS),
        initialScale = 1.1f
    ) + fadeIn(animationSpec = tween(NAV_ANIMATION_DURATION_MS))) togetherWith
            (scaleOut(
                animationSpec = tween(NAV_ANIMATION_DURATION_MS),
                targetScale = 0.85f
            ) + fadeOut(animationSpec = tween(NAV_ANIMATION_DURATION_MS)))

private enum class SlideKind { LEFT, RIGHT, NONE }

// Push: Queue always slides in right-to-left; Settings always slides in left-to-right
// when entered from outside the settings flow. Everything else (NONE) keeps the
// scale+fade default, including pushes deeper within the settings flow itself.
private fun pushSlideKind(initial: NavKey?, target: NavKey?): SlideKind = when {
    target is QueueKey -> SlideKind.LEFT
    target is SettingsKey && initial != null && !isSettingsSubtree(initial) -> SlideKind.RIGHT
    else -> SlideKind.NONE
}

// Pop: the exact reverse of pushSlideKind() above — Queue closing slides left-to-right,
// Settings closing (back out of the whole settings flow) slides right-to-left. Shared by
// popTransitionSpec and predictivePopTransitionSpec so the gesture-driven preview matches
// the regular back animation.
private fun popSlideKind(initial: NavKey?, target: NavKey?): SlideKind = when {
    initial is QueueKey -> SlideKind.RIGHT
    initial is SettingsKey && target != null && !isSettingsSubtree(target) -> SlideKind.LEFT
    else -> SlideKind.NONE
}

// Builds the actual ContentTransform for a given SlideKind. Receiver is star-projected
// (AnimatedContentTransitionScope<*>) rather than naming NavDisplay's exact type parameter
// (which has changed shape across Nav3 releases) — slideIntoContainer/slideOutOfContainer
// don't need to know the specific type argument, so this compiles regardless of what
// NavDisplay wraps entries in internally.
private fun AnimatedContentTransitionScope<*>.slideTransform(
    kind: SlideKind,
    isPush: Boolean
): ContentTransform = when (kind) {
    SlideKind.LEFT -> slideIntoContainer(
        AnimatedContentTransitionScope.SlideDirection.Left,
        animationSpec = tween(NAV_ANIMATION_DURATION_MS)
    ) togetherWith slideOutOfContainer(
        AnimatedContentTransitionScope.SlideDirection.Left,
        animationSpec = tween(NAV_ANIMATION_DURATION_MS)
    )
    SlideKind.RIGHT -> slideIntoContainer(
        AnimatedContentTransitionScope.SlideDirection.Right,
        animationSpec = tween(NAV_ANIMATION_DURATION_MS)
    ) togetherWith slideOutOfContainer(
        AnimatedContentTransitionScope.SlideDirection.Right,
        animationSpec = tween(NAV_ANIMATION_DURATION_MS)
    )
    SlideKind.NONE -> if (isPush) defaultPushTransform() else defaultPopTransform()
}

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

    // Which special slide (if any) the transition currently in flight should use. Set
    // explicitly at each push()/navigateBack() call site, immediately before the backStack
    // mutation that triggers it — read back inside transitionSpec/popTransitionSpec below.
    // This sidesteps needing to know the exact type NavDisplay wraps entries in internally
    // (it's changed shape across Nav3 releases); we already fully control every navigation
    // call site ourselves, so there's no need to reverse-engineer it from the transition
    // scope's targetState/initialState.
    var pendingSlideKind by remember { mutableStateOf(SlideKind.NONE) }

    LaunchedEffect(navigateToUpdater) {
        if (navigateToUpdater) {
            pendingSlideKind = SlideKind.NONE
            backStack.clear()
            backStack.addAll(listOf(HomeKey, SettingsKey(), UpdaterKey))
            vm.onUpdaterNavigated()
        }
    }

    // push()/navigateBack() are the only places the back stack is ever mutated. Each one
    // decides pendingSlideKind BEFORE mutating, from the stack state as it stood just prior
    // to the change — see the comment on pendingSlideKind above for why.
    fun push(key: NavKey) {
        pendingSlideKind = pushSlideKind(backStack.lastOrNull(), key)
        backStack.add(key)
    }

    fun navigateBack() {
        if (backStack.size > 1) {
            val leaving = backStack.last()
            when (leaving) {
                is SearchKey -> vm.clearSearch()
                is LikedKey -> resetLikedScreenScroll()
                is CustomPlaylistKey -> resetCustomPlaylistScroll(leaving.playlist.id)
                else -> {}
            }
            val revealed = backStack.getOrNull(backStack.size - 2)
            pendingSlideKind = popSlideKind(leaving, revealed)
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
            onNavigateQueue = { showPlayerDialog = false; push(QueueKey) },
            onNavigateArtist = { name, artistId ->
                showPlayerDialog = false
                push(ArtistDetailKey(name, artistId ?: ""))
            },
            onNavigateAlbum = { albumTitle ->
                showPlayerDialog = false
                // Search for the album by name rather than browsing this specific id —
                // YTM itself sometimes has more than one catalog entry for what's really
                // the same album, so search reliably lands on a real, complete result
                // instead of risking opening a different, possibly-incomplete duplicate.
                push(SearchAlbumsKey(albumTitle))
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
                transitionSpec = { slideTransform(pendingSlideKind, isPush = true) },
                popTransitionSpec = { slideTransform(pendingSlideKind, isPush = false) },
                predictivePopTransitionSpec = {
                    // Unlike transitionSpec/popTransitionSpec above, this renders a live
                    // preview WHILE the user is still dragging — before navigateBack() has
                    // run and set pendingSlideKind, and before backStack has actually
                    // shrunk. So this one case does need to peek at backStack directly
                    // (still fully intact at this point) rather than reading
                    // pendingSlideKind.
                    val leaving = backStack.lastOrNull()
                    val revealed = if (backStack.size >= 2) backStack[backStack.size - 2] else null
                    slideTransform(popSlideKind(leaving, revealed), isPush = false)
                },
                entryProvider = { key ->
                    when (key) {
                        is HomeKey -> NavEntry(key) {
                            HomeScreen(
                                vm = vm,
                                onNavigateSearch = { push(SearchKey) },
                                onNavigateQueue = { push(QueueKey) },
                                onNavigateSettings = { push(SettingsKey()) },
                                onNavigateLiked = { push(LikedKey) },
                                onNavigateAlbums = { push(AlbumsKey) },
                                onNavigateLibrary = { push(LibraryKey) }
                            )
                        }
                        is SearchKey -> NavEntry(key) {
                            SearchScreen(
                                vm = vm,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onNavigateQueue = { push(QueueKey) },
                                onAlbumClick = { album -> push(AlbumDetailKey(album)) },
                                onNavigateAlbums = { q -> push(SearchAlbumsKey(q)) },
                                onNavigateArtists = { q -> push(SearchArtistsKey(q)) },
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
                                onNavigateUpdater = { push(UpdaterKey) }
                            )
                        }
                        is LikedKey -> NavEntry(key) {
                            LikedScreen(
                                vm = vm,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onNavigateQueue = { push(QueueKey) },
                                onNavigateCacheSettings = { push(SettingsKey(openCache = true)) },
                                onAddTo = { song -> addToSheetSong = song }
                            )
                        }
                        is AlbumsKey -> NavEntry(key) {
                            SavedAlbumsScreen(
                                vm = vm,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onNavigateQueue = { push(QueueKey) },
                                onAlbumClick = { album -> push(AlbumDetailKey(album)) }
                            )
                        }
                        is LibraryKey -> NavEntry(key) {
                            LibraryScreen(
                                vm = vm,
                                onBack = { navigateBack() },
                                onNavigateLiked = { push(LikedKey) },
                                onNavigateAlbums = { push(AlbumsKey) },
                                onNavigatePlaylist = { playlist -> push(CustomPlaylistKey(playlist)) },
                                onNavigateQueue = { push(QueueKey) }
                            )
                        }
                        is UpdaterKey -> NavEntry(key) {
                            UpdaterScreen(
                                vm = vm,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onEngineInfo = { push(EngineInfoKey) },
                                onNavigateLibrary = { push(SettingsKey(openLibrary = true)) }
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
                                onNavigateQueue = { push(QueueKey) },
                                onNavigateArtist = { name, artistId ->
                                    push(ArtistDetailKey(name, artistId ?: ""))
                                },
                                onAddTo = { song -> addToSheetSong = song },
                                onNavigateCacheSettings = { push(SettingsKey(openCache = true)) }
                            )
                        }
                        is ArtistDetailKey -> NavEntry(key) {
                            ArtistScreen(
                                vm = vm,
                                artistName = key.artistName,
                                artistBrowseId = key.artistBrowseId,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onAlbumClick = { album -> push(AlbumDetailKey(album)) },
                                onAddTo = { song -> addToSheetSong = song },
                                onNavigateQueue = { push(QueueKey) }
                            )
                        }
                        is SearchAlbumsKey -> NavEntry(key) {
                            SearchAlbumsScreen(
                                vm = vm,
                                query = key.query,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onAlbumClick = { album -> push(AlbumDetailKey(album)) }
                            )
                        }
                        is SearchArtistsKey -> NavEntry(key) {
                            SearchArtistsScreen(
                                vm = vm,
                                query = key.query,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onArtistClick = { artist -> push(ArtistDetailKey(artist.name, artist.id)) }
                            )
                        }
                        is CustomPlaylistKey -> NavEntry(key) {
                            CustomPlaylistScreen(
                                vm = vm,
                                playlist = key.playlist,
                                isDarkMode = isDarkMode,
                                onBack = { navigateBack() },
                                onAddTo = { song -> addToSheetSong = song },
                                onNavigateQueue = { push(QueueKey) },
                                onNavigateCacheSettings = { push(SettingsKey(openCache = true)) }
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
