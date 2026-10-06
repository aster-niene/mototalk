package dev.mototalk.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live audio diagnostics for the screen (POC requirements FR-2, FR-12). */
data class AudioStats(
    val micDbfs: Double? = null,
    val commDevice: String? = null,
    val recordRouted: String? = null,
    val trackRouted: String? = null,
    val recordBufferMs: Int? = null,
    val trackBufferMs: Int? = null,
    val micSilenced: Boolean = false,
    val recordingSecondsLeft: Int = 0,
    val duckActive: Boolean = false,
    val ioError: String? = null,
)

object AudioStatsStore {

    private val _state = MutableStateFlow(AudioStats())
    val state: StateFlow<AudioStats> = _state.asStateFlow()

    fun update(transform: (AudioStats) -> AudioStats) = _state.update(transform)

    fun reset() {
        _state.value = AudioStats()
    }
}
