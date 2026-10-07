package dev.mototalk.transport

import java.util.UUID

/**
 * Phone-to-phone link (POC requirements D8). Nearby Connections today; the interface keeps
 * Wi-Fi Direct + UDP possible later. Listener callbacks arrive on the main thread.
 */
interface PeerTransport {

    interface Listener {
        fun onEndpointFound(endpointId: String, deviceId: UUID, name: String)
        fun onEndpointLost(endpointId: String)

        /** Both sides get this; both must accept. [authDigits] is shown to the user on first pairing. */
        fun onConnectionInitiated(endpointId: String, deviceId: UUID?, name: String, authDigits: String, incoming: Boolean)
        fun onConnected(endpointId: String)
        fun onConnectionFailed(endpointId: String, status: Int)
        fun onDisconnected(endpointId: String)
        fun onReceive(endpointId: String, bytes: ByteArray)

        /** Link quality from the transport (Nearby: 1 LOW, 2 MEDIUM, 3 HIGH). */
        fun onBandwidthChanged(endpointId: String, quality: Int)
        fun onError(what: String, status: Int)
    }

    /** Payloads queued but not yet confirmed sent (FR-4 in-flight limit). */
    val inFlight: Int

    fun startAdvertising()
    fun stopAdvertising()
    fun startDiscovery()
    fun stopDiscovery()
    fun requestConnection(endpointId: String)
    fun accept(endpointId: String)
    fun reject(endpointId: String)

    /** Thread-safe; may be called from the audio I/O thread. */
    fun send(endpointId: String, bytes: ByteArray)
    fun disconnect(endpointId: String)
    fun stopAll()
}
