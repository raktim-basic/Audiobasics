package com.rkd.audiobasics.api.account

import android.icu.text.BreakIterator

/**
 * YouTube Music has no playlist icon, so Audiobasics keeps the emoji inside the playlist name:
 *
 *     "🔥; fire playlist"   ->   icon 🔥, name "fire playlist"
 *
 * Reading is strict on purpose. Only text before the first ";" that is exactly ONE emoji counts
 * as an icon, so an ordinary playlist called "Chill; beats" keeps its whole name.
 * An empty or missing prefix means "no icon", and the app falls back to its default emoji.
 */
internal object PlaylistNameCodec {

    data class Parsed(val emoji: String?, val name: String)

    fun parse(cloudTitle: String): Parsed {
        val semicolon = cloudTitle.indexOf(';')
        if (semicolon < 0) return Parsed(null, cloudTitle.trim())

        val head = cloudTitle.substring(0, semicolon).trim()
        val rest = cloudTitle.substring(semicolon + 1).trim()

        // "; name" - an empty prefix: no icon, and the prefix marker is not part of the name.
        if (head.isEmpty() && rest.isNotEmpty()) return Parsed(null, rest)

        // Not a lone emoji in front (e.g. "Chill; beats") or nothing after it: keep the title as is.
        if (rest.isEmpty() || !isSingleEmoji(head)) return Parsed(null, cloudTitle.trim())

        return Parsed(head, rest)
    }

    /** The name to store in the cloud for a playlist with this icon. */
    fun compose(emoji: String?, name: String): String {
        val cleanName = name.trim()
        val icon = emoji?.trim().orEmpty()
        return if (icon.isEmpty()) cleanName else "$icon; $cleanName"
    }

    /**
     * True when [text] is one visible emoji: a single grapheme cluster (so skin tones, flags and
     * joined sequences like a family count as one) that isn't a plain letter, digit or symbol
     * from basic ASCII. Keycap emoji (1\uFE0F\u20E3) are accepted too.
     */
    private fun isSingleEmoji(text: String): Boolean {
        if (text.isEmpty() || text.length > 32) return false

        val clusters = BreakIterator.getCharacterInstance()
        clusters.setText(text)
        var count = 0
        var end = clusters.next()
        while (end != BreakIterator.DONE) {
            count++
            end = clusters.next()
        }
        if (count != 1) return false

        if (text.contains('\u20E3')) return true
        val first = text.codePointAt(0)
        if (first < 0x80) return false
        return !Character.isLetterOrDigit(first) && !Character.isWhitespace(first)
    }
}
