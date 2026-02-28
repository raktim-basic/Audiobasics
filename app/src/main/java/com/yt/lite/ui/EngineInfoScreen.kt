package com.yt.lite.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import com.yt.lite.ui.theme.NothingFont

// Hardcoded current versions — update these when you update dependencies
private const val NEWPIPE_CURRENT = "v0.26.0"
private const val YTM_CURRENT = "1.20240101.01.00"

private suspend fun fetchLatestNewPipeVersion(): String? = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient()
        val req = Request.Builder()
            .url("https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases?per_page=5")
            .header("Accept", "application/vnd.github.v3+json")
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: return@withContext null
        val arr = JSONArray(body)
        if (arr.length() > 0) arr.getJSONObject(0).optString("tag_name") else null
    } catch (_: Exception) { null }
}

@Composable
fun EngineInfoScreen(
    isDarkMode: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var isChecking by remember { mutableStateOf(false) }
    var latestNewPipe by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val cardColor = if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFE0E0E0)
    val subTextColor = if (isDarkMode) Color(0xFFCCCCCC) else Color(0xFF444444)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(60.dp))

        Text(
            text = "Engine info.",
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            color = Color.Red
        )

        Spacer(Modifier.height(40.dp))

        // NewPipe card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(cardColor)
                .padding(16.dp)
        ) {
            Text(
                text = "Newpipe extractor",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = textColor
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Current version : $NEWPIPE_CURRENT",
                fontFamily = NothingFont,
                fontSize = 13.sp,
                color = subTextColor
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Latest version : ${
                    when {
                        !checked -> NEWPIPE_CURRENT
                        latestNewPipe != null -> latestNewPipe!!
                        else -> "Unknown"
                    }
                }",
                fontFamily = NothingFont,
                fontSize = 13.sp,
                color = subTextColor
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Current version status : Active",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = subTextColor
            )
        }

        Spacer(Modifier.height(16.dp))

        // YTM card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(cardColor)
                .padding(16.dp)
        ) {
            Text(
                text = "YTM web remix",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = textColor
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Current version : $YTM_CURRENT",
                fontFamily = NothingFont,
                fontSize = 13.sp,
                color = subTextColor
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Latest version : $YTM_CURRENT",
                fontFamily = NothingFont,
                fontSize = 13.sp,
                color = subTextColor
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Currently version status : Active",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = subTextColor
            )
        }

        Spacer(Modifier.weight(1f))

        // Check latest version button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Red)
                .clickable(enabled = !isChecking) {
                    scope.launch {
                        isChecking = true
                        latestNewPipe = fetchLatestNewPipeVersion()
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
                    text = "Check latest version",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}
