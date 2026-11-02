package com.rkd.audiobasics.ui

import com.rkd.audiobasics.ui.theme.AmoledBlack
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.api.account.AccountManager
import com.rkd.audiobasics.api.account.AccountState
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils
import com.rkd.audiobasics.utils.MigrationMessageProvider
import com.rkd.audiobasics.utils.NightOwl

@Composable
fun rememberScrollProgress(listState: LazyListState, totalItems: Int): Float {
    return remember(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (totalItems <= 1) return@remember 0f
        val itemProgress = listState.firstVisibleItemIndex.toFloat() / (totalItems - 1).toFloat()
        itemProgress.coerceIn(0f, 1f)
    }
}

@Composable
fun HomeScreen(
    vm: MusicViewModel,
    onNavigateSearch: () -> Unit,
    onNavigateQueue: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateLogin: () -> Unit,
    onNavigateLiked: () -> Unit,
    onNavigateAlbums: () -> Unit,
    onNavigateLibrary: () -> Unit
) {
    val context = LocalContext.current
    val isDarkMode by vm.isDarkMode.collectAsState()
    val updateAvailable by vm.updateAvailable.collectAsState()
    val homeHeaderReady by vm.homeHeaderReady.collectAsState()
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val accountState by AccountManager.state.collectAsState()

    // The built-in header is a time-of-day greeting. Whatever is set remotely
    // (ab-configs/header.json) still takes over from it, line by line.
    val knownProfile = (accountState as? AccountState.LoggedIn)?.profile
        ?: (accountState as? AccountState.SessionExpired)?.profile
    // First name only; falls back to the start of the email if the account has no name.
    // Null when nobody is signed in.
    val firstName = knownProfile?.let { p ->
        p.name.trim().substringBefore(' ')
            .ifBlank { p.email?.substringBefore('@').orEmpty() }
            .ifBlank { "friend" }
    }
    val slot = NightOwl.slot()
    val (greetingLine1, greetingLine2) = when (slot) {
        NightOwl.Slot.LATE_NIGHT ->
            if (NightOwl.monstersNearby) "There aren't any" to "Monsters nearby.."
            else "Up late...." to "${firstName ?: "Musichead"}?"
        NightOwl.Slot.EARLY_MORNING -> "So early!!" to "${firstName ?: "Early bird"}.."
        else -> {
            val hello = when (slot) {
                NightOwl.Slot.MORNING -> "Good morning"
                NightOwl.Slot.AFTERNOON -> "Good afternoon"
                else -> "Good evening"
            }
            if (firstName == null) "$hello, uhh..." to "What's your name?" else hello to firstName
        }
    }

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val bodyColor = if (isDarkMode) AmoledBlack else bgColor
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFF888888) else Color(0xFF999999)
    val cardBg = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

    FloatingBarHost(
        bar = {
            HomeBottomBar(
            isDarkMode = isDarkMode,
            onSettings = onNavigateSettings,
            onSearch = onNavigateSearch,
            onQueue = onNavigateQueue,
            hapticsEnabled = hapticsEnabled,
            context = context
        )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bodyColor)
        ) {
          // Home is entirely pure black in dark mode, slogan header included (bodyColor == bgColor
          // in light mode, so light is unchanged).
          Column(modifier = Modifier.fillMaxWidth().background(bodyColor)) {
            // ── Header / slogan ────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 32.dp)
            ) {
                if (updateAvailable) {
                    Text(
                        text = "An update is available!",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = textColor
                    )
                    Text(
                        text = "Check in settings",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = textColor
                    )
                } else {
                    // Remote header (ab-configs/header.json, via MigrationMessageProvider)
                    // takes over from the static slogan once fetched; the provider's getters
                    // fall back to their hardcoded defaults automatically if the fetch hasn't
                    // resolved yet or failed, so there's never a blank header. Reading
                    // homeHeaderReady here (even though it's unused beyond that) ties this
                    // block to the StateFlow so it recomposes the instant the fetch resolves,
                    // rather than only showing fresh text on the next screen visit.
                    @Suppress("UNUSED_EXPRESSION") homeHeaderReady
                    Text(
                        text = MigrationMessageProvider.remoteHomeHeaderLine1() ?: greetingLine1,
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = textColor
                    )
                    Text(
                        text = MigrationMessageProvider.remoteHomeHeaderLine2() ?: greetingLine2,
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = textColor
                    )
                }
            }

            StaticDashedDivider(modifier = Modifier.fillMaxWidth(), isDarkMode = isDarkMode)
          }

            // ── Sign-in link (signed out, or session expired) ──────────────────
            // Goes straight to the sign-in page. While an account is linked and working,
            // nothing is shown here; the account lives in Settings > Account.
            val needsSignIn = accountState is AccountState.LoggedOut ||
                accountState is AccountState.SessionExpired
            if (needsSignIn) {
                val linkColor = if (isDarkMode) Color(0xFF4A9EFF) else Color(0xFF1A73E8)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (accountState is AccountState.SessionExpired) {
                            "Session expired. Sign in again"
                        } else {
                            "Sign in with your YouTube account"
                        },
                        fontSize = 16.sp,
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                onNavigateLogin()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Discover Music (disabled placeholder) ──────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cardBg)
                    .clickable {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        Toast
                            .makeText(context, "Being developed rn 🔥", Toast.LENGTH_SHORT)
                            .show()
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = subTextColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Discover Music",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = subTextColor
                        )
                        Text(
                            text = "Coming soon",
                            fontFamily = NothingFont,
                            fontSize = 12.sp,
                            color = subTextColor.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = ">",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = subTextColor
                    )
                }
            }

            // ── Your Library ───────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFF0000))
                    .clickable {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onNavigateLibrary()
                    }
                    .padding(horizontal = 16.dp, vertical = 24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LibraryMusic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "Your Library",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = Color.White
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = ">",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                }
            }

            Spacer(Modifier.height(FloatingBarClearance))


        }
    }
}

@Composable
fun HomeBottomBar(
    isDarkMode: Boolean,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onQueue: () -> Unit,
    hapticsEnabled: Boolean,
    context: android.content.Context
) {
    HomeNavPill(
        isDarkMode = isDarkMode,
        onSettings = onSettings,
        onSearch = onSearch,
        onQueue = onQueue,
        hapticsEnabled = hapticsEnabled,
        context = context
    )
}

@Composable
fun StaticDashedDivider(
    modifier: Modifier = Modifier,
    isDarkMode: Boolean = false,
    scrollProgress: Float? = null
) {
    val secondColor = if (isDarkMode) Color.White else Color.Black

    Canvas(modifier = modifier.height(12.dp)) {
        val totalWidth = size.width
        val y = size.height / 2f
        val dashWidth = 16f
        val dashGap = 8f

        if (scrollProgress == null) {
            drawLine(
                color = Color.Red,
                start = Offset(0f, y), end = Offset(totalWidth / 2f, y),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashWidth, dashGap), 0f)
            )
            drawLine(
                color = secondColor,
                start = Offset(totalWidth / 2f, y), end = Offset(totalWidth, y),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashWidth, dashGap), 0f)
            )
        } else {
            val redEnd = totalWidth * scrollProgress.coerceIn(0f, 1f)
            if (redEnd > 0f) {
                drawLine(
                    color = Color.Red,
                    start = Offset(0f, y), end = Offset(redEnd, y),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashWidth, dashGap), 0f)
                )
            }
            if (redEnd < totalWidth) {
                drawLine(
                    color = secondColor.copy(alpha = 0.3f),
                    start = Offset(redEnd, y), end = Offset(totalWidth, y),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashWidth, dashGap), 0f)
                )
            }
        }
    }
}
