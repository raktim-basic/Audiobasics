package com.rkd.audiobasics.cache

import android.content.Context

/**
 * Per-song loudness as reported by YouTube (`loudnessDb`: how many dB louder than YouTube's
 * reference level the track is; negative = quieter). Kept in its own tiny preferences file,
 * keyed by song id, so it works for streamed and downloaded songs alike and survives clearing
 * downloads. A stored NaN means "YouTube was asked and has no value for this song", so the
 * one-time backfill doesn't keep asking about it.
 */
object LoudnessStore {
    private const val FILE = "audiobasics_loudness"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The loudness in dB, or null if unknown (never looked up, or YouTube has none). */
    fun get(context: Context, songId: String): Float? {
        val p = prefs(context)
        if (!p.contains(songId)) return null
        return p.getFloat(songId, Float.NaN).takeUnless { it.isNaN() }
    }

    /** True once a lookup has been recorded for the song, even an empty one. */
    fun isKnown(context: Context, songId: String): Boolean = prefs(context).contains(songId)

    fun put(context: Context, songId: String, loudnessDb: Float) {
        prefs(context).edit().putFloat(songId, loudnessDb).apply()
    }

    fun markMissing(context: Context, songId: String) {
        prefs(context).edit().putFloat(songId, Float.NaN).apply()
    }
}
