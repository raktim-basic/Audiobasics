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
// weight from Thin to Black. minSdk is 26, so variable font axes are always supported.
//
// BODY_WEIGHT_OFFSET makes every body weight render a bit thinner than the weight that was asked for
// (Normal 400 renders as 350, Bold 700 as 650). Change this one number to tune the whole app.
const val BODY_WEIGHT_OFFSET = -50

@OptIn(ExperimentalTextApi::class)
private fun flexFont(weight: Int): Font = Font(
    resId = R.font.roboto_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight((weight + BODY_WEIGHT_OFFSET).coerceIn(1, 1000))
    ),
)

// Standard weights (100..900), used by normal Text(fontWeight = FontWeight.Bold) calls.
private val RobotoFlexBody = FontFamily((100..900 step 100).map { flexFont(it) })

// Any exact weight, for animated weights (tabs, lyrics). Cached per weight so a transition that passes
// through many values only builds each typeface once. Callers should round weights to a multiple of 10.
// Use together with fontWeight = FontWeight(weight) so Compose never fakes a bold on top.
private val exactWeightCache = java.util.concurrent.ConcurrentHashMap<Int, FontFamily>()

fun bodyFontAt(weight: Int): FontFamily {
    if (EasterEggUtils.isAprilFoolsDay()) return SwankyMooMooFont
    val w = weight.coerceIn(100, 900)
    return exactWeightCache.getOrPut(w) { FontFamily(flexFont(w)) }
}

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
