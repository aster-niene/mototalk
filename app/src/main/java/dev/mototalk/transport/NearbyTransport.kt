package dev.mototalk.transport

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dev.mototalk.diag.DiagnosticsLog
import dev.mototalk.intercom.Protocol
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Nearby Connections, Strategy.P2P_POINT_TO_POINT (POC requirements D8, FR-3, FR-4).
 * Created inside RideService with the application context, so it keeps working with the screen locked.
 */
class NearbyTransport(
    context: Context,
    private val serviceId: String,
    private val endpointInfo: ByteArray,
    private val listener: PeerTransport.Listener,
) : PeerTransport {

    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val pending = ConcurrentHashMap.newKeySet<Long>()

    override val inFlight: Int get() = pending.size

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { listener.onReceive(endpointId, it) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Outgoing payloads leave the in-flight set once Nearby reports a final status.
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS,
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED,
                -> pending.remove(update.payloadId)
            }
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val decoded = Protocol.decodeEndpointInfo(info.endpointInfo)
            listener.onConnectionInitiated(
                endpointId,
                decoded?.first,
                decoded?.second ?: info.endpointName,
                info.authenticationDigits,
                info.isIncomingConnection,
            )
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val code = resolution.status.statusCode
            if (code == ConnectionsStatusCodes.STATUS_OK) listener.onConnected(endpointId) else listener.onConnectionFailed(endpointId, code)
        }

        override fun onDisconnected(endpointId: String) {
            pending.clear()
            listener.onDisconnected(endpointId)
        }

        override fun onBandwidthChanged(endpointId: String, info: BandwidthInfo) {
            listener.onBandwidthChanged(endpointId, info.quality)
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val decoded = Protocol.decodeEndpointInfo(info.endpointInfo)
            if (decoded == null) {
                DiagnosticsLog.event("nearby_foreign_endpoint", mapOf("endpointId" to endpointId, "name" to info.endpointName))
                return
            }
            listener.onEndpointFound(endpointId, decoded.first, decoded.second)
        }

        override fun onEndpointLost(endpointId: String) = listener.onEndpointLost(endpointId)
    }

    override fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(endpointInfo, serviceId, connectionCallback, options)
            .addOnFailureListener { fail("start_advertising", it) }
    }

    override fun stopAdvertising() = client.stopAdvertising()

    override fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discoveryCallback, options)
            .addOnFailureListener { fail("start_discovery", it) }
    }

    override fun stopDiscovery() = client.stopDiscovery()

    override fun requestConnection(endpointId: String) {
        client.requestConnection(endpointInfo, endpointId, connectionCallback, ConnectionOptions.Builder().build())
            .addOnFailureListener { fail("request_connection", it) }
    }

    override fun accept(endpointId: String) {
        client.acceptConnection(endpointId, payloadCallback).addOnFailureListener { fail("accept", it) }
    }

    override fun reject(endpointId: String) {
        client.rejectConnection(endpointId).addOnFailureListener { fail("reject", it) }
    }

    override fun send(endpointId: String, bytes: ByteArray) {
        val payload = Payload.fromBytes(bytes)
        pending.add(payload.id)
        client.sendPayload(endpointId, payload).addOnFailureListener {
            pending.remove(payload.id)
            fail("send", it)
        }
    }

    override fun disconnect(endpointId: String) {
        pending.clear()
        client.disconnectFromEndpoint(endpointId)
    }

    override fun stopAll() {
        pending.clear()
        client.stopAllEndpoints()
    }

    private fun fail(what: String, e: Exception) {
        val status = (e as? ApiException)?.statusCode ?: -1
        listener.onError(what, status)
    }
}
