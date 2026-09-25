package com.rkd.audiobasics.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.AudiobasicsLinks
import com.rkd.audiobasics.utils.HapticUtils
import kotlinx.coroutines.launch

/**
 * The three faces of the player's flip card. Player is "home": Lyrics and Info each flip back
 * to Player only — there's no direct flip between Lyrics and Info themselves. See the rotation
 * math where `currentFace`/the drag handler are defined for how that's enforced.
 */
private enum class PlayerFace { PLAYER, LYRICS, INFO }

private fun faceFor(rotationDegrees: Float): PlayerFace = when {
    rotationDegrees <= -90f -> PlayerFace.LYRICS
    rotationDegrees >= 90f -> PlayerFace.INFO
    else -> PlayerFace.PLAYER
}

private const val FLIP_DURATION_MS = 420
private const val FLIP_FLING_VELOCITY = 300f // degrees/sec — a flick past this always commits
// Which physical swipe direction returns Lyrics/Info back to Player. I couldn't verify this by
// eye and got it wrong twice; if it's still backwards, just flip this one value — 1f and -1f are
// the only two possibilities, so one of them is definitely right.
private const val RETURN_SWIPE_SIGN = -1f

@Composable
fun PlayerDialog(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onNavigateQueue: () -> Unit,
    onNavigateArtist: (String, String?) -> Unit,
    onNavigateAlbum: (String) -> Unit
) {
    val context = LocalContext.current
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val song by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val position by vm.currentPosition.collectAsState()
    val duration by vm.duration.collectAsState()
    val savedAlbums by vm.savedAlbums.collectAsState()
    val customPlaylists by vm.customPlaylists.collectAsState()
    val resolvedAlbumCache by vm.resolvedAlbumCache.collectAsState()
    val sleepTimerMode by vm.sleepTimerMode.collectAsState()
    val sleepTimerRemaining by vm.sleepTimerRemaining.collectAsState()
    val repeatMode by vm.repeatMode.collectAsState()
    val tempoPitchSpeed by vm.currentSpeed.collectAsState()
    val tempoPitchPitch by vm.currentPitch.collectAsState()

    var showAddToSheet by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showTempoPitchDialog by remember { mutableStateOf(false) }
    var showShareChoice by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf<Long?>(null) }

    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF2A2A2A) else Color.White
    val subTextColor = if (isDarkMode) Color(0xFF888888) else Color(0xFF666666)

    // ── Flip-card state ──────────────────────────────────────────────────────
    // rotationValue (degrees) is the single source of truth for the card's Y-axis rotation:
    // 0 = Player face-on, -180 = Lyrics face-on, +180 = Info face-on. While actively dragging,
    // it comes from `liveRotation`, a plain Float updated synchronously (no coroutine) so it
    // can never lag behind the finger. Once released, it comes from `settleRotation`, an
    // Animatable driving the settle-into-place animation. Mixing "many scope.launch{snapTo}
    // calls, one per drag event" into the SAME Animatable used for the settle animation was
    // the earlier bug here — Animatable serializes those calls through an internal mutex, so
    // under fast/long drags (lots of events) the calls can back up, leaving rotation.value
    // reading stale at release time and making the settle decision (and therefore the flip
    // direction) inconsistent. Tracking the live drag synchronously sidesteps that entirely.
    val settleRotation = remember { Animatable(0f) }
    var liveRotation by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val rotationValue = if (isDragging) liveRotation else settleRotation.value
    val scope = rememberCoroutineScope()
    var cardWidthPx by remember { mutableIntStateOf(1) }
    val currentFace = faceFor(rotationValue)

    suspend fun flipTo(target: Float) {
        settleRotation.animateTo(target, tween(FLIP_DURATION_MS, easing = FastOutSlowInEasing))
    }

    // The player now opens as a MorphPopup (see MainActivity: MorphPlacement.Center, wide =
    // true, anchored to the player bar) instead of a standalone Android Dialog window — it
    // morphs open/closed like every other floating popup, and MorphContainer already supplies
    // the backdrop scrim, outside-tap-to-dismiss, and back-press-to-close for free. Nested
    // popups below (Add to playlist, Create playlist, Sleep timer, Tempo/Pitch, Share choice,
    // this card's own 3-dot menu) now resolve LocalMorphOverlay to that same root overlay, so
    // they stack correctly above the player instead of needing a window of their own.
    //
    // Back press/gesture flips back to Player instead of closing, while on another face.
    // Registered here so it's the most-recently-registered BackHandler while this content is
    // composed — MorphContainer's own BackHandler (which requests close) registered earlier,
    // when this entry was created, so it only runs once this one is disabled (i.e. already on
    // the Player face).
    BackHandler(enabled = currentFace != PlayerFace.PLAYER) {
        scope.launch { flipTo(0f) }
    }

        // Each face keeps its own original size — a real card's size doesn't visibly jump
            // mid-flip, but since `currentFace` only changes exactly at the ±90° edge-on point
            // (see faceFor below), switching the size modifier here happens at that same instant.
            //
            // Sized off settleRotation, NOT the live rotationValue: settleRotation only moves
            // during the post-release settle animation, never while a finger is actually on the
            // card, so the box's own bounds stay fixed for the whole gesture. That matters
            // beyond the visual — this box's bounds are also the coordinate frame the drag
            // gesture measures the finger against. Resizing it mid-drag (as this used to do,
            // keyed off the live face) shifted that frame right in the middle of the gesture,
            // which mostly rode through unnoticed in the rotation itself (frame-to-frame deltas)
            // but could corrupt the release velocity calculation (which looks at a short window
            // of recent absolute positions) if the resize happened to land inside that window —
            // sign and all, which is exactly the "random" wrong-direction flips this was causing.
            val sizingFace = faceFor(settleRotation.value)
            val cardWidthFraction = when (sizingFace) {
                PlayerFace.PLAYER -> 0.88f
                PlayerFace.LYRICS -> 0.92f
                PlayerFace.INFO -> 0.88f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(cardWidthFraction)
                    .then(
                        when (sizingFace) {
                            PlayerFace.PLAYER -> Modifier.aspectRatio(1f) // square
                            PlayerFace.LYRICS -> Modifier.fillMaxHeight(0.75f)
                            PlayerFace.INFO -> Modifier.wrapContentHeight()
                        }
                    )
                    .onSizeChanged { cardWidthPx = it.width.coerceAtLeast(1) }
                    .graphicsLayer {
                        rotationY = rotationValue
                        cameraDistance = 12f * density
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    // The card itself must not pass taps through to the scrim behind it (which
                    // closes the whole dialog) — without this, tapping anywhere on the card that
                    // isn't its own button would also dismiss.
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { }
                    .pointerInput(Unit) {
                        // A drag starting from Player can go either way (session bounds -180..180);
                        // one starting from Lyrics or Info can only return to Player (never overshoot
                        // into the far side in one continuous gesture) — see the enum doc above. The
                        // finger-to-rotation mapping itself (drag right → rotation increases toward
                        // Info; drag left → decreases toward Lyrics) stays the same sign in both
                        // directions — it's the same physical rotation either way, just clamped to a
                        // different half of the range depending on where the gesture starts.
                        var sessionMin = -180f
                        var sessionMax = 180f
                        var dragSign = 1f
                        var startSettled = 0f
                        var boundaryHapticFired = false
                        val velocityTracker = VelocityTracker()

                        fun settledTargetFor(r: Float) = when (faceFor(r)) {
                            PlayerFace.LYRICS -> -180f
                            PlayerFace.INFO -> 180f
                            PlayerFace.PLAYER -> 0f
                        }

                        // Elastic "give" past a boundary that's not allowed to actually open
                        // anything — e.g. dragging further into Lyrics while already on Lyrics,
                        // or past Player into the far side in one continuous gesture. Damped and
                        // capped, like iOS's over-scroll bounce. Since the max overshoot (12°) is
                        // always well short of the travel needed to commit anywhere (20-90°, see
                        // onDragEnd), this "give" can never itself cause a flip — it's purely
                        // tactile feedback that you've hit the edge.
                        fun withResistance(raw: Float, min: Float, max: Float): Float {
                            val maxOvershoot = 12f
                            val resistance = 0.35f
                            return when {
                                raw < min -> {
                                    val over = min - raw
                                    min - maxOvershoot * (1f - 1f / (1f + over * resistance / maxOvershoot))
                                }
                                raw > max -> {
                                    val over = raw - max
                                    max + maxOvershoot * (1f - 1f / (1f + over * resistance / maxOvershoot))
                                }
                                else -> raw
                            }
                        }

                        detectHorizontalDragGestures(
                            onDragStart = {
                                val startRotation = settleRotation.value
                                startSettled = settledTargetFor(startRotation)
                                liveRotation = startRotation
                                isDragging = true
                                boundaryHapticFired = false
                                when (faceFor(startRotation)) {
                                    // Return sessions get the finger-to-rotation sign flipped —
                                    // confirmed by testing that a Lyrics/Info return swipe should
                                    // go the opposite way from the "open" sessions below, not the
                                    // same way a pure continuous rotation would suggest.
                                    PlayerFace.LYRICS -> { sessionMin = -180f; sessionMax = 0f; dragSign = RETURN_SWIPE_SIGN }
                                    PlayerFace.INFO -> { sessionMin = 0f; sessionMax = 180f; dragSign = RETURN_SWIPE_SIGN }
                                    PlayerFace.PLAYER -> { sessionMin = -180f; sessionMax = 180f; dragSign = 1f }
                                }
                                velocityTracker.resetTracking()
                            },
                            onDragEnd = {
                                // Velocity only ever lowers how far you need to have dragged to
                                // commit — it never picks the direction on its own. Direction
                                // always comes from which way liveRotation actually moved (which
                                // tracks the finger correctly), never from the velocity's sign —
                                // that sign was the source of the "hard swipe opens the wrong
                                // side" bug: a very fast/short gesture can leave VelocityTracker's
                                // estimate noisy enough to occasionally get the sign wrong, even
                                // though the position it settled at is fine.
                                val velocityDegPerSec = velocityTracker.calculateVelocity().x * (180f / cardWidthPx)
                                val fast = kotlin.math.abs(velocityDegPerSec) > FLIP_FLING_VELOCITY
                                val requiredTravel = if (fast) 20f else 90f
                                val traveled = kotlin.math.abs(liveRotation - startSettled)
                                val target = if (traveled < requiredTravel) {
                                    startSettled
                                } else if (startSettled == 0f) {
                                    if (liveRotation < 0f) -180f else 180f
                                } else {
                                    0f
                                }
                                if (target != startSettled && hapticsEnabled) {
                                    HapticUtils.performSubtleHaptic(context)
                                }
                                val fromValue = liveRotation.coerceIn(sessionMin, sessionMax)
                                isDragging = false
                                scope.launch {
                                    settleRotation.snapTo(fromValue)
                                    flipTo(target)
                                }
                            },
                            onDragCancel = {
                                val fromValue = liveRotation.coerceIn(sessionMin, sessionMax)
                                isDragging = false
                                scope.launch {
                                    settleRotation.snapTo(fromValue)
                                    flipTo(startSettled)
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val degreesPerPx = 180f / cardWidthPx
                                val raw = liveRotation + dragAmount * degreesPerPx * dragSign
                                val wasAtBoundary = liveRotation <= sessionMin || liveRotation >= sessionMax
                                liveRotation = withResistance(raw, sessionMin, sessionMax)
                                val atBoundaryNow = raw < sessionMin || raw > sessionMax
                                if (atBoundaryNow && !wasAtBoundary && !boundaryHapticFired && hapticsEnabled) {
                                    boundaryHapticFired = true
                                    HapticUtils.performSubtleHaptic(context)
                                }
                            }
                        )
                    }
            ) {
                when (currentFace) {
                    PlayerFace.PLAYER -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PlayerFrontContent(
                            vm = vm,
                            context = context,
                            hapticsEnabled = hapticsEnabled,
                            song = song,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            position = position,
                            duration = duration,
                            isDarkMode = isDarkMode,
                            textColor = textColor,
                            surfaceColor = surfaceColor,
                            subTextColor = subTextColor,
                            dragPosition = dragPosition,
                            onDragPositionChange = { dragPosition = it },
                            sleepTimerMode = sleepTimerMode,
                            sleepTimerRemaining = sleepTimerRemaining,
                            repeatMode = repeatMode,
                            tempoPitchSpeed = tempoPitchSpeed,
                            tempoPitchPitch = tempoPitchPitch,
                            onDismiss = onDismiss,
                            onNavigateQueue = onNavigateQueue,
                            onShowInfo = { scope.launch { flipTo(180f) } },
                            onShowLyrics = { scope.launch { flipTo(-180f) } },
                            onShowAddToSheet = { showAddToSheet = true },
                            onShowShareChoice = { showShareChoice = true },
                            onShowSleepDialog = { showSleepDialog = true },
                            onShowTempoPitchDialog = { showTempoPitchDialog = true }
                        )
                    }
                    PlayerFace.LYRICS -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationY = 180f }
                    ) {
                        LyricsCardContent(
                            vm = vm,
                            isDarkMode = isDarkMode,
                            onBack = { scope.launch { flipTo(0f) } }
                        )
                    }
                    PlayerFace.INFO -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { rotationY = 180f }
                    ) {
                        if (song != null) {
                            SongInfoCardContent(
                                song = song!!,
                                isDarkMode = isDarkMode,
                                context = context,
                                savedAlbums = savedAlbums,
                                resolvedAlbumCache = resolvedAlbumCache,
                                onCacheResolvedAlbum = { vm.cacheResolvedAlbum(it) },
                                livePlaybackDurationMs = duration,
                                onDismiss = { scope.launch { flipTo(0f) } },
                                onArtistClick = { artistName, artistId ->
                                    onDismiss()
                                    onNavigateArtist(artistName, artistId)
                                },
                                onAlbumClick = { albumTitle ->
                                    onDismiss()
                                    onNavigateAlbum(albumTitle)
                                }
                            )
                        }
                    }
                }
            }

        // ── Add to playlist sheet ──────────────────────────────────────────────
        if (showAddToSheet && song != null) {
            AddToPlaylistSheet(
                song = song!!,
                vm = vm,
                isDarkMode = isDarkMode,
                hapticsEnabled = hapticsEnabled,
                context = context,
                onDismiss = { showAddToSheet = false },
                onCreateNew = { showCreatePlaylist = true }
            )
        }

        // ── Create playlist dialog ─────────────────────────────────────────────
        if (showCreatePlaylist) {
            CreatePlaylistDialog(
                isDarkMode = isDarkMode,
                hapticsEnabled = hapticsEnabled,
                existingNames = customPlaylists.map { it.name },
                onDismiss = { showCreatePlaylist = false },
                onCreate = { name, emoji ->
                    vm.createPlaylist(name, emoji)
                    showCreatePlaylist = false
                }
            )
        }

        // ── Share choice dialog ──────────────────────────────────────────────────
        if (showShareChoice && song != null) {
            ShareChoiceDialog(
                isDarkMode = isDarkMode,
                hapticsEnabled = hapticsEnabled,
                context = context,
                onAudiobasicsLink = {
                    AudiobasicsLinks.shareText(context, AudiobasicsLinks.songLink(song!!.id), "Share song")
                },
                onYoutubeLink = {
                    AudiobasicsLinks.shareText(context, "https://www.youtube.com/watch?v=${song!!.id}", "Share song")
                },
                onDismiss = { showShareChoice = false }
            )
        }

        // ── Sleep timer dialog ─────────────────────────────────────────────────
        // Controlled directly from here — no need to navigate to the queue screen.
        if (showSleepDialog) {
            SleepTimerDialog(
                isDarkMode = isDarkMode,
                hapticsEnabled = hapticsEnabled,
                context = context,
                onDismiss = { showSleepDialog = false },
                onEndOfSong = {
                    showSleepDialog = false
                    vm.startEndOfSongSleepTimer()
                },
                onCustom = { minutes ->
                    showSleepDialog = false
                    vm.startCustomSleepTimer(minutes)
                }
            )
        }

        // ── Tempo/Pitch dialog ───────────────────────────────────────────────────
        if (showTempoPitchDialog) {
            TempoPitchDialog(
                isDarkMode = isDarkMode,
                hapticsEnabled = hapticsEnabled,
                context = context,
                speed = tempoPitchSpeed,
                pitchSemitones = tempoPitchPitch,
                onSpeedChange = { vm.setTempoSpeed(it) },
                onPitchChange = { vm.setTempoPitch(it) },
                onReset = { vm.resetTempoPitch() },
                onDismiss = { showTempoPitchDialog = false }
            )
        }
    }

/**
 * The player face's content — extracted from PlayerDialog's card so the flip `when` above
 * stays readable. [onShowInfo]/[onShowLyrics] flip the card rather than opening anything new.
 */
@Composable
private fun PlayerFrontContent(
    vm: MusicViewModel,
    context: android.content.Context,
    hapticsEnabled: Boolean,
    song: com.rkd.audiobasics.data.Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: Long,
    duration: Long,
    isDarkMode: Boolean,
    textColor: Color,
    surfaceColor: Color,
    subTextColor: Color,
    dragPosition: Long?,
    onDragPositionChange: (Long?) -> Unit,
    sleepTimerMode: Int,
    sleepTimerRemaining: Long,
    repeatMode: Int,
    tempoPitchSpeed: Float,
    tempoPitchPitch: Int,
    onDismiss: () -> Unit,
    onNavigateQueue: () -> Unit,
    onShowInfo: () -> Unit,
    onShowLyrics: () -> Unit,
    onShowAddToSheet: () -> Unit,
    onShowShareChoice: () -> Unit,
    onShowSleepDialog: () -> Unit,
    onShowTempoPitchDialog: () -> Unit
) {
    Column {
        // ── Top bar ─────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speaker/cast (placeholder)
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.SpeakerGroup, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp))
            }

            // Song info — flips the card
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onShowInfo()
            }) {
                Icon(Icons.Default.Info, contentDescription = "Song info", tint = textColor, modifier = Modifier.size(20.dp))
            }

            // Close
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onDismiss()
            }) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = textColor, modifier = Modifier.size(20.dp))
            }
        }

        // ── Artwork + title ──────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            val cachedThumbPath = remember(song?.id) {
                song?.id?.let { com.rkd.audiobasics.cache.CacheManager.getCachedThumbPath(context, it) }
            }
            AsyncImage(
                model = cachedThumbPath ?: song?.thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .size(110.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song?.title ?: "Song Name",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (song?.isExplicit == true) {
                        val explicitBgColor = if (isDarkMode) Color(0xFF444444) else Color(0xFFDDDDDD)
                        val explicitTextColor = if (isDarkMode) Color(0xFFCCCCCC) else Color(0xFF555555)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(explicitBgColor)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "E",
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                color = explicitTextColor
                            )
                        }
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = song?.artist ?: "Artist",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp,
                        color = subTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Progress bar ─────────────────────────────────────
        val displayPosition = dragPosition ?: position

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTime(displayPosition),
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = textColor
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp),
                contentAlignment = Alignment.Center
            ) {
                DashedProgressBar(
                    progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                    onSeek = { seekProgress ->
                        val newPos = (seekProgress * duration).toLong()
                        vm.seekTo(newPos)
                        onDragPositionChange(null)
                    },
                    onDragging = { dragProgress ->
                        onDragPositionChange((dragProgress * duration).toLong())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    hapticsEnabled = hapticsEnabled,
                    context = context,
                    isDarkMode = isDarkMode
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatTime(duration),
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = textColor
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── Playback controls ────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                vm.skipToPrevious()
            }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Previous", tint = textColor, modifier = Modifier.size(36.dp))
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(surfaceColor)
                    .clickable {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        vm.togglePlayPause()
                    }
                    .padding(horizontal = 32.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = textColor)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = textColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isPlaying) "Pause" else "Play",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = textColor
                        )
                    }
                }
            }

            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                vm.skipToNext()
            }) {
                Icon(Icons.Default.ArrowForward, contentDescription = "Next", tint = textColor, modifier = Modifier.size(36.dp))
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Bottom bar ───────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(surfaceColor)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // + Add to playlist
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onShowAddToSheet()
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add to playlist", tint = textColor, modifier = Modifier.size(26.dp))
            }

            // Lyrics — flips the card
            Text(
                text = "LYRICS",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = textColor,
                modifier = Modifier.clickable {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    onShowLyrics()
                }
            )

            // 3-dot dropdown
            val overlay = LocalMorphOverlay.current
            val threeDotAnchor = rememberMorphAnchor()
            IconButton(
                onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    overlay.show(anchor = threeDotAnchor.bounds(), placement = MorphPlacement.Anchored) { close ->
                        // Shadows the outer repeatMode param: repeat toggling deliberately keeps
                        // this menu open for repeated taps (see below), so unlike every other
                        // item here it needs to reflect changes made after the menu was opened,
                        // not just the value that was current the moment it was opened.
                        val repeatMode by vm.repeatMode.collectAsState()
                        MorphMenuColumn {
                            MorphMenuItem(
                                text = "Share",
                                leadingIcon = Icons.Default.Share,
                                onClick = {
                                    close()
                                    song?.let { s ->
                                        if (AudiobasicsLinks.isYoutubeShareOptionEnabled(context)) {
                                            onShowShareChoice()
                                        } else {
                                            AudiobasicsLinks.shareText(
                                                context, AudiobasicsLinks.songLink(s.id), "Share song"
                                            )
                                        }
                                    }
                                }
                            )
                            MorphMenuItem(
                                text = "Queue",
                                leadingIcon = Icons.Default.QueueMusic,
                                onClick = {
                                    close()
                                    onDismiss()
                                    onNavigateQueue()
                                }
                            )
                            MorphMenuItem(
                                text = when (sleepTimerMode) {
                                    MusicViewModel.SLEEP_TIMER_END_OF_SONG -> "End of the song"
                                    MusicViewModel.SLEEP_TIMER_CUSTOM -> formatCountdown(sleepTimerRemaining)
                                    else -> "Sleep timer"
                                },
                                leadingIcon = Icons.Default.Bedtime,
                                iconTint = if (sleepTimerMode != MusicViewModel.SLEEP_TIMER_OFF) Color.Red else Color.Unspecified,
                                textColor = if (sleepTimerMode != MusicViewModel.SLEEP_TIMER_OFF) Color.Red else Color.Unspecified,
                                onClick = {
                                    close()
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    if (song == null) {
                                        Toast.makeText(context, "Nothing is playing", Toast.LENGTH_SHORT).show()
                                    } else if (sleepTimerMode != MusicViewModel.SLEEP_TIMER_OFF) {
                                        vm.cancelSleepTimer()
                                    } else {
                                        onShowSleepDialog()
                                    }
                                }
                            )
                            MorphMenuItem(
                                text = "Tempo and Pitch",
                                leadingIcon = Icons.Default.Speed,
                                iconTint = if (tempoPitchSpeed != 1.0f || tempoPitchPitch != 0) Color.Red else Color.Unspecified,
                                textColor = if (tempoPitchSpeed != 1.0f || tempoPitchPitch != 0) Color.Red else Color.Unspecified,
                                onClick = {
                                    close()
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    onShowTempoPitchDialog()
                                }
                            )
                            MorphMenuItem(
                                text = when (repeatMode) {
                                    1 -> "Repeat list"
                                    2 -> "Repeat one"
                                    else -> "Repeat off"
                                },
                                leadingIcon = if (repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                iconTint = if (repeatMode == 0) Color.Unspecified else Color.Red,
                                textColor = if (repeatMode == 0) Color.Unspecified else Color.Red,
                                onClick = {
                                    // Matches the old menu: toggling repeat does NOT close the
                                    // menu, so tapping repeatedly cycles through its modes.
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    vm.toggleRepeatMode()
                                }
                            )
                        }
                    }
                },
                modifier = Modifier.morphAnchor(threeDotAnchor)
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = textColor, modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
fun DashedProgressBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    onDragging: (Float) -> Unit,
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    isDarkMode: Boolean = true
) {
    val totalDashes = 30
    var barWidthPx by remember { mutableStateOf(0f) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }

    val displayProgress = dragProgress ?: progress
    val displayFilled = (displayProgress * totalDashes).toInt()
    var lastFilled by remember { mutableStateOf(displayFilled) }

    LaunchedEffect(displayFilled) {
        if (dragProgress != null && displayFilled != lastFilled && hapticsEnabled) {
            HapticUtils.performSubtleHaptic(context)
            lastFilled = displayFilled
        }
    }

    val unfilledColor = if (isDarkMode) Color(0xFF333333) else Color(0xFFBDBDBD)

    Row(
        modifier = modifier
            .height(24.dp)
            .onSizeChanged { barWidthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (barWidthPx > 0) {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            dragProgress = (offset.x / barWidthPx).coerceIn(0f, 1f)
                            lastFilled = (dragProgress!! * totalDashes).toInt()
                            onDragging(dragProgress!!)
                        }
                    },
                    onDragEnd = {
                        dragProgress?.let { onSeek(it) }
                        dragProgress = null
                    },
                    onDragCancel = { dragProgress = null },
                    onHorizontalDrag = { _, dragAmount ->
                        if (barWidthPx > 0) {
                            val current = dragProgress ?: progress
                            dragProgress = (current + dragAmount / barWidthPx).coerceIn(0f, 1f)
                            onDragging(dragProgress!!)
                        }
                    }
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(totalDashes) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (index < displayFilled) Color(0xFFFF0000) else unfilledColor
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onSeek((index + 1).toFloat() / totalDashes.toFloat())
                    }
            )
        }
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
