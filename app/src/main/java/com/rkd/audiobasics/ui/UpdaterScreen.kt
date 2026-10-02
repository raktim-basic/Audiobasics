package com.rkd.audiobasics.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rkd.audiobasics.utils.AppUpdater
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

const val APP_CURRENT_VERSION = "2.6"
const val APP_UPDATE_APK_NAME = "Audiobasics.apk"
const val APP_GITHUB_RELEASES_API =
    "https://api.github.com/repos/raktim-basic/Audiobasics/releases?per_page=5"
const val APP_GITHUB_RELEASES_URL =
    "https://github.com/raktim-basic/Audiobasics/releases/latest"

/** The latest GitHub release: tag (without "v"), notes, and the first attached .apk asset. */
data class AppRelease(
    val version: String,
    val body: String,
    val apkUrl: String?,
    val apkSize: Long
)

suspend fun fetchLatestRelease(): AppRelease? = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient()
        val req = Request.Builder()
            .url(APP_GITHUB_RELEASES_API)
            .header("Accept", "application/vnd.github.v3+json")
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: return@withContext null
        val arr = JSONArray(body)
        if (arr.length() == 0) return@withContext null
        val release = arr.getJSONObject(0)
        val version = release.optString("tag_name").removePrefix("v")
        if (version.isBlank()) return@withContext null

        var apkUrl: String? = null
        var apkSize = -1L
        val assets = release.optJSONArray("assets")
        if (assets != null) {
            // The release carries more than one APK (Audiobasics.apk and Audiobasics_store.apk),
            // so pick the in-app update APK by exact name rather than "the first .apk".
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").equals(APP_UPDATE_APK_NAME, ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                    apkSize = asset.optLong("size", -1L)
                    break
                }
            }
        }
        AppRelease(version, release.optString("body"), apkUrl, apkSize)
    } catch (_: Exception) { null }
}

suspend fun fetchLatestAppVersion(): String? = fetchLatestRelease()?.version

@Composable
fun UpdaterScreen(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onEngineInfo: () -> Unit,
    onNavigateLibrary: () -> Unit
) {
    val context = LocalContext.current
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val scope = rememberCoroutineScope()

    var isChecking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var checked by remember { mutableStateOf(false) }
    val latestVersion = release?.version

    // null = no download in progress; otherwise whole-percent progress for the dialog.
    var downloadPercent by remember { mutableStateOf<Int?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var showChangelog by remember { mutableStateOf(false) }
    // Set when we sent the user to "Install unknown apps"; checked on return (see below).
    var awaitingInstallPermission by remember { mutableStateOf(false) }

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)

    val updateAvailable = checked && latestVersion != null &&
            latestVersion != APP_CURRENT_VERSION
    val linkColor = if (isDarkMode) Color(0xFF4A9EFF) else Color(0xFF1A73E8)

    fun openReleasesPage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(APP_GITHUB_RELEASES_URL))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun startUpdate() {
        val rel = release ?: return
        val apkUrl = rel.apkUrl
        if (apkUrl == null) {
            Toast.makeText(context, "No APK is attached to this release", Toast.LENGTH_SHORT).show()
            return
        }
        // One-time permission: Android requires "Install unknown apps" to be allowed for us.
        if (!context.packageManager.canRequestPackageInstalls()) {
            awaitingInstallPermission = true
            Toast.makeText(context, "Allow Audiobasics to install updates", Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            )
            return
        }
        downloadJob = scope.launch {
            downloadPercent = 0
            try {
                val file = AppUpdater.downloadApk(context, apkUrl, rel.apkSize) { downloadPercent = it }
                downloadPercent = null
                // Once the download is done, the install hand-off always runs to completion,
                // even if the dialog's dismissal cancels this job in the meantime.
                withContext(NonCancellable) { AppUpdater.installApk(context, file) }
            } catch (e: CancellationException) {
                downloadPercent = null
                throw e
            } catch (_: Exception) {
                downloadPercent = null
                Toast.makeText(
                    context,
                    "Download failed. Try again, or use Update via GitHub",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Coming back from the "Install unknown apps" screen: if the user allowed it, carry on
    // with the update automatically; if not, just stop waiting.
    val startUpdateState = rememberUpdatedState(newValue = { startUpdate() })
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingInstallPermission) {
                awaitingInstallPermission = false
                if (context.packageManager.canRequestPackageInstalls()) startUpdateState.value()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (downloadPercent != null) {
        DownloadProgressDialog(
            percent = { downloadPercent ?: 0 },
            isDarkMode = isDarkMode,
            hapticsEnabled = hapticsEnabled,
            context = context,
            onCancel = {
                downloadJob?.cancel()
                downloadPercent = null
            }
        )
    }

    if (showChangelog) {
        ChangelogDialog(
            version = latestVersion ?: "",
            body = release?.body ?: "",
            isDarkMode = isDarkMode,
            hapticsEnabled = hapticsEnabled,
            context = context,
            onDismiss = { showChangelog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 4.dp)
        ) {
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onBack()
            }) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = textColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Updater",
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            color = textColor
        )

        Spacer(Modifier.height(48.dp))

        Text(
            text = "Current app version : $APP_CURRENT_VERSION",
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = textColor
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Latest app version : ${
                when {
                    !checked -> "—"
                    latestVersion != null -> latestVersion
                    else -> "Error"
                }
            }",
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = textColor
        )

        Spacer(Modifier.height(12.dp))

        if (checked) {
            Text(
                text = if (updateAvailable) "An update is available. Update asap"
                       else "You're currently running the absolute latest version",
                fontFamily = NothingFont,
                fontSize = 13.sp,
                color = subTextColor,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(28.dp))

        Spacer(Modifier.height(36.dp))

        Column(modifier = Modifier.padding(horizontal = 28.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Red)
                    .clickable(enabled = !isChecking) {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        scope.launch {
                            isChecking = true
                            release = fetchLatestRelease()
                            checked = true
                            isChecking = false
                        }
                    }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Text(
                        text = "check for updates",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                }
            }

            if (updateAvailable) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Red)
                        .clickable {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            startUpdate()
                        }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Download and update",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                }

                // Only shown while an update is available.
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "View changelogs",
                        fontFamily = NothingFont,
                        fontSize = 14.sp,
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                showChangelog = true
                            }
                            .padding(vertical = 6.dp)
                    )
                    Text(
                        text = "Update via GitHub",
                        fontFamily = NothingFont,
                        fontSize = 14.sp,
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                openReleasesPage()
                            }
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "we'll notify you if an update is available :D",
            fontFamily = NothingFont,
            fontSize = 12.sp,
            color = subTextColor,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))
    }
}
