package dev.mototalk.intercom

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class Rider(val endpointId: String, val name: String, val remembered: Boolean)

/** First-time pairing waiting for the user: both phones show the same digits (FR-3). */
data class Pairing(val endpointId: String, val name: String, val digits: String)

data class LinkStats(
    val rttMs: Long?,
    val quality: Int,
    val txKbps: Double,
    val rxKbps: Double,
    val inFlight: Int,
    val txDropped: Long,
    val playout: PlayoutBuffer.Stats,
)

/** What the screen shows about the phone-to-phone link. Written by IntercomSession on the main thread. */
data class IntercomUi(
    val peerName: String? = null,
    val discovered: List<Rider> = emptyList(),
    val pairing: Pairing? = null,
    val stats: LinkStats? = null,
    val transmitting: Boolean = false,
    val partnerSpeaking: Boolean = false,
)

object IntercomStore {

    private val _state = MutableStateFlow(IntercomUi())
    val state: StateFlow<IntercomUi> = _state.asStateFlow()

    fun update(transform: (IntercomUi) -> IntercomUi) = _state.update(transform)

    fun reset() {
        _state.value = IntercomUi()
    }
}

/** The remembered partner (FR-3): reconnects to it need no confirmation. */
data class Peer(val deviceId: UUID, val name: String)

object PeerStore {

    private const val PREFS = "peer"
    private const val KEY_ID = "deviceId"
    private const val KEY_NAME = "name"

    private val _peer = MutableStateFlow<Peer?>(null)
    val peer: StateFlow<Peer?> = _peer.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString(KEY_ID, null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        _peer.value = id?.let { Peer(it, p.getString(KEY_NAME, null).orEmpty()) }
    }

    fun save(context: Context, peer: Peer) {
        _peer.value = peer
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_ID, peer.deviceId.toString())
            putString(KEY_NAME, peer.name)
        }
    }

    fun forget(context: Context) {
        _peer.value = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
    }
}
