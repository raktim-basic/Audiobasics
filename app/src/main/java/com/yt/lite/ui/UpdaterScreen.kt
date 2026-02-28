package com.yt.lite.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import com.yt.lite.ui.theme.NothingFont

const val APP_CURRENT_VERSION = "2.0"
const val APP_GITHUB_RELEASES_API =
    "https://api.github.com/repos/raktim-basic/Audiobasics/releases?per_page=5"
const val APP_GITHUB_RELEASES_URL =
    "https://github.com/raktim-basic/Audiobasics/releases/latest"

suspend fun fetchLatestAppVersion(): String? = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient()
        val req = Request.Builder()
            .url(APP_GITHUB_RELEASES_API)
            .header("Accept", "application/vnd.github.v3+json")
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: return@withContext null
        val arr = JSONArray(body)
        if (arr.length() > 0)
            arr.getJSONObject(0).optString("tag_name")
                ?.removePrefix("v")
        else null
    } catch (_: Exception) { null }
}

@Composable
fun UpdaterScreen(
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onEngineInfo: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isChecking by remember { mutableStateOf(false) }
    var latestVersion by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)

    val updateAvailable = checked && latestVersion != null &&
            latestVersion != APP_CURRENT_VERSION

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(60.dp))

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
                    latestVersion != null -> latestVersion!!
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
                text = if (updateAvailable) "An update is available"
                       else "You're currently running the latest version",
                fontFamily = NothingFont,
                fontSize = 13.sp,
                color = subTextColor,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.weight(1f))

        // Check for updates button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Red)
                .clickable(enabled = !isChecking) {
                    scope.launch {
                        isChecking = true
                        latestVersion = fetchLatestAppVersion()
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

        // Download and update button — only when update available
        if (updateAvailable) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Red)
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW,
                            Uri.parse(APP_GITHUB_RELEASES_URL))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
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
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "(we'll notify you if an update is available)",
            fontFamily = NothingFont,
            fontSize = 12.sp,
            color = subTextColor,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Engine info.",
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = Color.Red,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { onEngineInfo() }
        )

        Spacer(Modifier.height(32.dp))
    }
}
