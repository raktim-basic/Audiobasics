package com.rkd.audiobasics.ui

import android.os.SystemClock
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Global typography response to the currently pressed item. */
@Stable
class PlayPulseState {
    var songId by mutableStateOf<String?>(null)
        private set
    var active by mutableStateOf(false)
        private set

    private var generation = 0L
    private var pressStartedAt = 0L

    fun press(id: String): Long {
        generation += 1
        songId = id
        active = true
        pressStartedAt = SystemClock.uptimeMillis()
        return generation
    }

    /** A short tap gets enough active time for the 150ms typography transition to appear. */
    fun remainingVisibleTime(token: Long, minimumActiveMs: Long = 300L): Long {
        if (token != generation) return 0L
        return (minimumActiveMs - (SystemClock.uptimeMillis() - pressStartedAt)).coerceAtLeast(0L)
    }

    fun release(id: String, token: Long) {
        if (songId == id && token == generation) active = false
    }
}

/** Shares each clickable's press lifecycle with the global typography animation. */
@Composable
fun rememberPlayPulseInteractionSource(id: String, enabled: Boolean = true): MutableInteractionSource {
    val source = androidx.compose.runtime.remember { MutableInteractionSource() }
    val pulse = LocalPlayPulse.current
    LaunchedEffect(source, id, pulse, enabled) {
        var pressToken = -1L
        var releaseJob: Job? = null
        try {
            source.interactions.collect { interaction: Interaction ->
                when (interaction) {
                    is PressInteraction.Press -> if (enabled) {
                        releaseJob?.cancel()
                        pressToken = pulse.press(id)
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        if (pressToken >= 0L) {
                            val token = pressToken
                            releaseJob?.cancel()
                            releaseJob = launch {
                                val remaining = pulse.remainingVisibleTime(token)
                                if (remaining > 0L) delay(remaining)
                                pulse.release(id, token)
                            }
                        }
                    }
                }
            }
        } finally {
            releaseJob?.cancel()
            if (pressToken >= 0L) pulse.release(id, pressToken)
        }
    }
    return source
}

val LocalPlayPulse = staticCompositionLocalOf { PlayPulseState() }
