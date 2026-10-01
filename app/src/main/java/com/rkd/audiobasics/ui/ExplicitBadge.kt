package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.ui.theme.NothingFont

/** The small "E" tag shown before an explicit song's or album's artist line. Same look as the
 *  one SongItem draws inline. */
@Composable
fun ExplicitBadge(isDarkMode: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (isDarkMode) Color(0xFF444444) else Color(0xFFDDDDDD))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "E",
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = if (isDarkMode) Color(0xFFCCCCCC) else Color(0xFF555555)
        )
    }
}
