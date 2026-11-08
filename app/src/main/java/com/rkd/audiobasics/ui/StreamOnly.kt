package com.rkd.audiobasics.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True while Stream-only mode is on (General settings). Provided once by MainActivity; screens
 * read it to hide everything that is about downloads — the 💔 "not downloaded" indicators,
 * the "Download them?" banners and the retry-download dialogs — since nothing is downloaded.
 */
val LocalStreamOnly = staticCompositionLocalOf { false }
