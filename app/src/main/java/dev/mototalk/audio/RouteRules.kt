package dev.mototalk.audio

import android.media.AudioManager

/** Audio modes that mean a cellular call is ringing or active (POC requirements FR-11). */
private val CALL_MODES = setOf(
    AudioManager.MODE_RINGTONE,
    AudioManager.MODE_IN_CALL,
    AudioManager.MODE_CALL_SCREENING,
    AudioManager.MODE_CALL_REDIRECT,
)

fun isCallMode(mode: Int): Boolean = mode in CALL_MODES

/** Backoff for re-requesting the SCO route: 1 → 2 → 4 → 5 s, then every 5 s (FR-1). */
fun retryDelayMs(attempt: Int): Long = when (attempt) {
    0 -> 1_000L
    1 -> 2_000L
    2 -> 4_000L
    else -> 5_000L
}
