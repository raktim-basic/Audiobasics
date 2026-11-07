package com.rkd.audiobasics.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** How long the "just played" emphasis is held before every row eases back to normal. */
const val PLAY_PULSE_HOLD_MS = 550L

/**
 * A short, global "you just played this" pulse. Tapping a song calls [trigger]; while [active]
 * is true the tapped song's text is heavy and every other song's is thin (same weights as
 * reorder mode), then MainActivity clears [active] after [PLAY_PULSE_HOLD_MS] and everything
 * glides back to normal. [token] changes on every trigger so a fast second tap restarts the timer.
 */
@Stable
class PlayPulseState {
    var songId by mutableStateOf<String?>(null)
        private set
    var active by mutableStateOf(false)
    var token by mutableIntStateOf(0)
        private set

    fun trigger(id: String) {
        songId = id
        active = true
        token++
    }
}

val LocalPlayPulse = staticCompositionLocalOf { PlayPulseState() }
