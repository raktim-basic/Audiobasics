package com.rkd.audiobasics.ui

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
import kotlinx.coroutines.flow.collect

/** Global typography response to the currently held item press. */
@Stable
class PlayPulseState {
    var songId by mutableStateOf<String?>(null)
        private set
    var active by mutableStateOf(false)
        private set

    fun press(id: String) {
        songId = id
        active = true
    }

    fun release(id: String) {
        if (songId == id) active = false
    }
}

/** Shares each clickable's press lifecycle with the global typography animation. */
@Composable
fun rememberPlayPulseInteractionSource(id: String, enabled: Boolean = true): MutableInteractionSource {
    val source = androidx.compose.runtime.remember { MutableInteractionSource() }
    val pulse = LocalPlayPulse.current
    LaunchedEffect(source, id, pulse, enabled) {
        source.interactions.collect { interaction: Interaction ->
            when (interaction) {
                is PressInteraction.Press -> if (enabled) pulse.press(id)
                is PressInteraction.Release, is PressInteraction.Cancel -> pulse.release(id)
            }
        }
    }
    return source
}

val LocalPlayPulse = staticCompositionLocalOf { PlayPulseState() }
