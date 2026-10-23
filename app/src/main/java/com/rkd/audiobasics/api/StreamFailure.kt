package com.rkd.audiobasics.api

/**
 * Why the most recent stream lookup for a song failed. Recorded by [Innertube] and read by the
 * UI (playback + download error dialog).
 *
 * @param loginRequired YouTube told every client to sign in — i.e. this song needs an account.
 * @param log short, human-readable trail of what each client reported, shown to the user so
 *            they can screenshot it for support.
 */
data class StreamFailure(
    val videoId: String,
    val loginRequired: Boolean,
    val log: String,
    val at: Long
)
