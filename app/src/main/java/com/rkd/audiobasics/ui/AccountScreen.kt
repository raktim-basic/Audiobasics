package com.rkd.audiobasics.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.rkd.audiobasics.api.account.AccountManager
import com.rkd.audiobasics.api.account.AccountProfile
import com.rkd.audiobasics.api.account.AccountState
import com.rkd.audiobasics.api.account.CapturedLogin
import com.rkd.audiobasics.api.account.LibraryFlow
import com.rkd.audiobasics.api.account.LoginResult
import com.rkd.audiobasics.api.account.SapisidAuth
import com.rkd.audiobasics.ui.theme.AmoledBlack
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import timber.log.Timber
import kotlin.coroutines.resume

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

// ─── Account page ────────────────────────────────────────────────────────────

@Composable
internal fun AccountPage(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onNavigateLogin: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AccountManager.state.collectAsState()
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()

    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val bodyColor = if (isDarkMode) AmoledBlack else bgColor
    val surfaceColor = if (isDarkMode) AmoledBlack else Color.White

    var showLogoutConfirm by remember { mutableStateOf(false) }
    val store = remember(vm) { VmLibraryStore(vm) }

    // When the library is (or may be) linked to the account, logging out asks what happens to
    // the copy on this device. With nothing linked there is nothing to decide.
    val askAboutLibrary = state is AccountState.SessionExpired ||
        (state as? AccountState.LoggedIn)?.libraryLinked == true

    // Cheap check each time the page opens; does nothing when no account is linked, and
    // only an explicit rejection from YouTube ever changes the state.
    LaunchedEffect(Unit) { AccountManager.validateSession() }

    FloatingBarHost(
        barAlignment = Alignment.BottomStart,
        bar = {
            BackOnlyPill(
                isDarkMode = isDarkMode,
                onBack = onBack,
                hapticsEnabled = hapticsEnabled,
                context = context
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize().background(bodyColor)) {
            Column(modifier = Modifier.fillMaxWidth().background(bgColor)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Account",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = textColor
                    )
                }
                StaticDashedDivider(modifier = Modifier.fillMaxWidth(), isDarkMode = isDarkMode)
            }

            when (val current = state) {
                is AccountState.LoggedOut -> {
                    SettingsRow(
                        isDarkMode = isDarkMode,
                        title = "Sign in to YouTube Music",
                        subtitle = "Your sign-in is stored encrypted on this device only",
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Filled.Login,
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            onNavigateLogin()
                        }
                    )
                }

                is AccountState.LoggedIn -> {
                    ProfileRow(current.profile, surfaceColor, textColor, subTextColor)
                    SettingsDivider(isDarkMode)
                    SettingsRow(
                        isDarkMode = isDarkMode,
                        title = if (current.libraryLinked) "Sync now" else "Finish setting up your library",
                        subtitle = if (current.libraryLinked) {
                            "Update this device from your account"
                        } else {
                            "Choose how this device's library and your account fit together"
                        },
                        icon = {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            if (current.libraryLinked) LibraryFlow.syncNow(store) else LibraryFlow.begin(store)
                        }
                    )
                    SettingsDivider(isDarkMode)
                    LogoutRow(isDarkMode, textColor) {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        showLogoutConfirm = true
                    }
                }

                is AccountState.SessionExpired -> {
                    ProfileRow(current.profile, surfaceColor, textColor, subTextColor)
                    Text(
                        text = "Your session expired. Sign in again to keep using your account.",
                        fontFamily = NothingFont,
                        fontSize = 13.sp,
                        color = Color(0xFFE57373),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(surfaceColor)
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                    SettingsRow(
                        isDarkMode = isDarkMode,
                        title = "Sign in again",
                        subtitle = "",
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Filled.Login,
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            onNavigateLogin()
                        }
                    )
                    SettingsDivider(isDarkMode)
                    LogoutRow(isDarkMode, textColor) {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        showLogoutConfirm = true
                    }
                }
            }
        }
    }

    LibraryFlowHost(vm = vm, isDarkMode = isDarkMode)

    if (showLogoutConfirm) {
        val dialogColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
        if (askAboutLibrary) {
            AlertDialog(
                onDismissRequest = { showLogoutConfirm = false },
                containerColor = dialogColor,
                title = {
                    Text("Log out?", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = textColor)
                },
                text = {
                    Column {
                        Text(
                            "What should happen to the library on this device?",
                            fontFamily = NothingFont,
                            color = subTextColor
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = {
                            showLogoutConfirm = false
                            LibraryFlow.logout(store, clearLocal = false)
                        }) {
                            Text("Keep library on this device", fontFamily = NothingFont, color = textColor)
                        }
                        TextButton(onClick = {
                            showLogoutConfirm = false
                            LibraryFlow.logout(store, clearLocal = true)
                        }) {
                            Text("Clear library from this device", fontFamily = NothingFont, color = Color(0xFFE57373))
                        }
                        TextButton(onClick = { showLogoutConfirm = false }) {
                            Text("Cancel", fontFamily = NothingFont, color = subTextColor)
                        }
                    }
                },
                confirmButton = {}
            )
        } else {
            AlertDialog(
                onDismissRequest = { showLogoutConfirm = false },
                containerColor = dialogColor,
                title = {
                    Text("Log out?", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = textColor)
                },
                text = {
                    Text(
                        "This removes your account from this device. Nothing in your library on this device is changed.",
                        fontFamily = NothingFont,
                        color = subTextColor
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showLogoutConfirm = false
                        scope.launch { AccountManager.logout() }
                    }) {
                        Text("Log out", fontFamily = NothingFont, color = Color(0xFFE57373))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutConfirm = false }) {
                        Text("Cancel", fontFamily = NothingFont, color = textColor)
                    }
                }
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: AccountProfile,
    surfaceColor: Color,
    textColor: Color,
    subTextColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (profile.photoUrl != null) {
            AsyncImage(
                model = profile.photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(CircleShape)
            )
        } else {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(48.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = textColor
            )
            val secondary = profile.email ?: profile.handle
            if (secondary != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = secondary,
                    fontFamily = NothingFont,
                    fontSize = 12.sp,
                    color = subTextColor
                )
            }
        }
    }
}

@Composable
private fun LogoutRow(isDarkMode: Boolean, textColor: Color, onClick: () -> Unit) {
    SettingsRow(
        isDarkMode = isDarkMode,
        title = "Log out",
        subtitle = "Remove this account from the app",
        icon = {
            Icon(
                Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        },
        onClick = onClick
    )
}

// ─── Login page ──────────────────────────────────────────────────────────────

private sealed interface CaptureResult {
    /** The page loaded but there is no signed-in session (yet). Not an error. */
    data object NotSignedIn : CaptureResult

    /** Signed in, but the page never exposed the data we need. */
    data object Incomplete : CaptureResult

    data class Captured(val login: CapturedLogin) : CaptureResult
}

private data class PageConfig(val visitorData: String, val dataSyncId: String, val authUser: String)

// Read through evaluateJavascript, so no JavaScript bridge is ever exposed to the page.
private const val PAGE_CONFIG_SCRIPT =
    "(function(){try{var c=(window.yt&&window.yt.config_)||{};" +
        "return JSON.stringify({v:c.VISITOR_DATA||'',d:c.DATASYNC_ID||'',s:String(c.SESSION_INDEX||0)});" +
        "}catch(e){return null;}})()"

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LoginPage(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onLoggedIn: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()

    val textColor = if (isDarkMode) Color.White else Color.Black
    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val bodyColor = if (isDarkMode) AmoledBlack else bgColor

    var checking by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun startFreshLogin(view: WebView) {
        errorMessage = null
        // Always begin from a clean browser session so the user can pick any Google account,
        // not whichever one an earlier sign-in left behind.
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies { view.loadUrl(LOGIN_URL) }
    }

    fun attemptCapture(view: WebView) {
        if (checking) return
        checking = true
        scope.launch {
            when (val capture = captureLogin(view)) {
                is CaptureResult.NotSignedIn -> checking = false
                is CaptureResult.Incomplete -> {
                    Timber.tag("Account").w("Login: signed in, but the page data never became available")
                    errorMessage = "Couldn't read your session from YouTube Music."
                    checking = false
                }
                is CaptureResult.Captured -> {
                    when (val result = AccountManager.completeLogin(capture.login)) {
                        is LoginResult.Success -> {
                            // The session now lives in encrypted storage; don't leave a second
                            // copy (and a still-rotating browser session) behind in the WebView.
                            AccountManager.clearWebSession()
                            checking = false
                            // Compare the account with this device's library; the questions (if
                            // any) appear on the Account page we're about to show.
                            LibraryFlow.begin(VmLibraryStore(vm))
                            onLoggedIn()
                        }
                        is LoginResult.Failed -> {
                            errorMessage = result.message
                            checking = false
                        }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(bodyColor)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onBack()
            }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
            }
            Text(
                text = "Sign in",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = textColor
            )
        }
        StaticDashedDivider(modifier = Modifier.fillMaxWidth(), isDarkMode = isDarkMode)

        errorMessage?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isDarkMode) Color(0xFF2A1A1A) else Color(0xFFFDECEA))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message,
                    fontFamily = NothingFont,
                    fontSize = 13.sp,
                    color = Color(0xFFE57373),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { webViewRef?.let { startFreshLogin(it) } }) {
                    Text("Try again", fontFamily = NothingFont, color = textColor)
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Google refuses sign-in in embedded browsers that announce themselves
                        // as a WebView ("; wv" in the user agent).
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val scheme = request.url.scheme
                                return !(scheme == "http" || scheme == "https")
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                val uri = url?.let(Uri::parse)
                                if (uri?.scheme == "https" && uri.host == "music.youtube.com") {
                                    attemptCapture(view)
                                }
                            }
                        }
                        webViewRef = this
                        startFreshLogin(this)
                    }
                },
                onRelease = { view ->
                    view.stopLoading()
                    view.destroy()
                }
            )

            if (checking) {
                Box(
                    modifier = Modifier.fillMaxSize().background(bodyColor),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = textColor)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Signing you in…",
                            fontFamily = NothingFont,
                            fontSize = 14.sp,
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reads the signed-in session out of the login WebView. Returns [CaptureResult.NotSignedIn]
 * immediately when there's no auth cookie, so an intermediate page never shows an error;
 * only waits (up to ~10s) when the cookie is there but the page's config isn't ready yet.
 */
private suspend fun captureLogin(view: WebView): CaptureResult {
    repeat(20) {
        val cookie = CookieManager.getInstance().getCookie(SapisidAuth.ORIGIN).orEmpty()
        if (!SapisidAuth.hasAuthCookie(cookie)) return CaptureResult.NotSignedIn

        val page = readPageConfig(view)
        if (page != null && page.visitorData.isNotBlank()) {
            return CaptureResult.Captured(
                CapturedLogin(
                    cookie = cookie,
                    visitorData = page.visitorData,
                    dataSyncId = page.dataSyncId,
                    authUser = page.authUser
                )
            )
        }
        delay(500)
    }
    return CaptureResult.Incomplete
}

private suspend fun readPageConfig(view: WebView): PageConfig? =
    withTimeoutOrNull(2_000) {
        suspendCancellableCoroutine<PageConfig?> { cont ->
            view.evaluateJavascript(PAGE_CONFIG_SCRIPT) { raw ->
                val config = try {
                    // evaluateJavascript hands back a JSON-encoded string literal (or "null").
                    val text = JSONTokener(raw ?: "null").nextValue() as? String
                    text?.let {
                        val o = JSONObject(it)
                        PageConfig(
                            visitorData = o.optString("v"),
                            dataSyncId = o.optString("d"),
                            authUser = o.optString("s").filter(Char::isDigit).ifBlank { "0" }
                        )
                    }
                } catch (_: Exception) {
                    null
                }
                if (cont.isActive) cont.resume(config)
            }
        }
    }
