package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Turns a full-width, edge-docked bar into a floating pill: inset from the screen edges and
 * from whatever sits above/below it, fully rounded, with a soft shadow and a hairline border so
 * it still reads against both the light background and the pure-black AMOLED one (where a
 * shadow alone is invisible).
 *
 * Apply it right after `.fillMaxWidth()` and BEFORE the bar's own inner padding, e.g.
 * `Modifier.fillMaxWidth().floatingPill(barColor).padding(vertical = 4.dp, horizontal = 8.dp)`.
 * The outer margin comes first, so the shape/background cover only the pill itself.
 */
fun Modifier.floatingPill(color: Color): Modifier {
    val shape = RoundedCornerShape(percent = 50)
    val hairline = if (color.luminance() < 0.5f) Color.White.copy(alpha = 0.08f)
    else Color.Black.copy(alpha = 0.08f)
    return this
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .shadow(elevation = 8.dp, shape = shape, clip = false)
        .clip(shape)
        .background(color)
        .border(width = 1.dp, color = hairline, shape = shape)
}
