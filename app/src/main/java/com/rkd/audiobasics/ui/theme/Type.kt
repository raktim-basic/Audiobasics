package com.rkd.audiobasics.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.R
import com.rkd.audiobasics.utils.EasterEggUtils

private val NothingFontRegular = FontFamily(
    Font(R.font.nothing_font, FontWeight.Normal),
    Font(R.font.nothing_font, FontWeight.Bold),
    Font(R.font.nothing_font, FontWeight.Medium),
)

// April Fools' Day easter egg: Swanky and Moo Moo stands in for NothingFont for one day only.
// Every screen references NothingFont directly by name (not just via MaterialTheme.typography),
// so swapping this one value flips the whole app.
private val SwankyMooMooFont = FontFamily(
    Font(R.font.temu_papyrus, FontWeight.Normal),
    Font(R.font.temu_papyrus, FontWeight.Bold),
    Font(R.font.temu_papyrus, FontWeight.Medium),
)

val NothingFont: FontFamily =
    if (EasterEggUtils.isAprilFoolsDay()) SwankyMooMooFont else NothingFontRegular

// Body font: Roboto Flex, weight axis only (res/font/roboto_flex.ttf). One variable file covers every
// weight from Thin to Black, so one Font entry per weight just sets that weight on the same file.
// minSdk is 26, so variable font axes are always supported (no static fallback needed).
@OptIn(ExperimentalTextApi::class)
private val RobotoFlexBody = FontFamily(
    (100..900 step 100).map { w ->
        Font(
            resId = R.font.roboto_flex,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    }
)

// NothingFont stays on headers and titles; BodyFont is for body text, lists, rows, buttons and
// settings text. The April 1st egg replaces BOTH, so the whole app still flips to one font that day.
val BodyFont: FontFamily =
    if (EasterEggUtils.isAprilFoolsDay()) SwankyMooMooFont else RobotoFlexBody

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = NothingFont,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp
    ),
    displayMedium = TextStyle(
        fontFamily = NothingFont,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp
    ),
    displaySmall = TextStyle(
        fontFamily = NothingFont,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = NothingFont,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = NothingFont,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = NothingFont,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = NothingFont,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = NothingFont,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp
    ),
    titleSmall = TextStyle(
        fontFamily = NothingFont,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    ),
    labelLarge = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp
    ),
    labelSmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    )
)
