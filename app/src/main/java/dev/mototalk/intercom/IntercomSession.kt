package dev.mototalk.intercom

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import dev.mototalk.audio.AudioSession
import dev.mototalk.audio.VadGate
import dev.mototalk.diag.DeviceIdentity
import dev.mototalk.diag.DiagnosticsLog
import dev.mototalk.service.SessionStore
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import dev.mototalk.transport.NearbyTransport
import dev.mototalk.transport.PeerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The Link and RemoteAudio axes of a RIDE session (POC requirements FR-3, FR-4, FR-5, FR-6, FR-7, FR-9, FR-10, §5).
 * Control logic runs on the main thread; [onCapturedFrame] runs on the audio I/O thread.
 *
 * - SEARCHING: advertise + discover. A remembered partner connects by itself (the smaller deviceId asks);
 *   a new one needs Connect on one phone and Accept with matching digits on both.
 * - CONNECTED once the partner's HELLO arrives. PING every second; nothing received for 5 s → partner lost.
 * - RECONNECTING: fixed roles (smaller deviceId discovers, larger advertises), auto-accept by deviceId.
 * - Voice: helmet mic → VAD gate → AUDIO frames (in-flight limit); partner's AUDIO → playout buffer → helmet.
 *   Partner's speech flag ducks the music (FR-7).
 */
class IntercomSession(
    private val context: Context,
    private val audio: AudioSession,
) : PeerTransport.Listener {

    companion object {
        private const val PING_MS = 1_000L
        private const val DEAD_MS = 5_000L
        private const val STATS_MS = 10_000L
        private const val DUCK_TICK_MS = 200L
        private const val PARTNER_SPEECH_HANG_MS = 800L
        private const val PARTNER_SPEECH_FRAMES = 2
        private const val MAX_IN_FLIGHT = 10 // ≈ 200 ms of audio
        private const val CONNECT_WATCHDOG_MS = 20_000L
        private const val BYE_FLUSH_MS = 300L
        private val RETRY_MS = longArrayOf(500, 1_000, 2_000, 5_000)
        private const val U32 = 0xFFFF_FFFFL
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val myId: UUID = DeviceIdentity.deviceId(context)
    private val myName: String = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
    private val transport: PeerTransport =
        NearbyTransport(context, context.packageName, Protocol.encodeEndpointInfo(myId, myName), this)
    private val playout = PlayoutBuffer()
    private val gate = VadGate()

    private var stopped = false
    private var link = Link.IDLE
    @Volatile private var peerEndpoint: String? = null
    private var connectingEndpoint: String? = null
    private var helloReceived = false
    private var lastRxAt = 0L
    private var lostAt = 0L
    private var connectedAt = 0L
    private var retryAttempt = 0
    private var ctrlSeq = 0L
    private var lastSentLocal: LocalAudio? = null
    private val discovered = LinkedHashMap<String, Rider>()

    // Audio I/O thread
    @Volatile private var txEnabled = false
    @Volatile private var resetTx = false
    private var audioSeq = 0L
    private var txWasEnabled = false
    private var gateWasActive = false

    // Partner speech → ducking
    private var partnerSpeechRun = 0
    private var lastPartnerSpeechAt = 0L
    @Volatile private var ducking = false
    private var shownTransmitting = false

    // Stats
    private val txFrames = AtomicLong()
    private val txBytes = AtomicLong()
    private val txDropped = AtomicLong()
    private var rxFrames = 0L
    private var rxBytes = 0L
    private var lastAudioAt = 0L
    private var lastAudioTs = 0L
    private val interArrivalMs = ArrayList<Long>()
    private var rttMs: Long? = null
    private var quality = 0
    private var statsTxBytes = 0L
    private var statsRxBytes = 0L

    private val pingRunnable = object : Runnable {
        override fun run() {
            val ep = peerEndpoint
            if (ep != null) sendControl(ep, MessageType.PING)
            handler.postDelayed(this, PING_MS)
        }
    }
    private val deadRunnable = object : Runnable {
        override fun run() {
            val ep = peerEndpoint
            if (ep != null && SystemClock.elapsedRealtime() - lastRxAt > DEAD_MS) {
                transport.disconnect(ep)
                peerLost("timeout")
            }
            handler.postDelayed(this, PING_MS)
        }
    }
    private val duckRunnable = object : Runnable {
        override fun run() {
            if (ducking && SystemClock.elapsedRealtime() - lastPartnerSpeechAt > PARTNER_SPEECH_HANG_MS) setDucking(false)
            val transmitting = gate.active && txEnabled && peerEndpoint != null
            if (transmitting != shownTransmitting) {
                shownTransmitting = transmitting
                IntercomStore.update { it.copy(transmitting = transmitting) }
            }
            handler.postDelayed(this, DUCK_TICK_MS)
        }
    }
    private val statsRunnable = object : Runnable {
        override fun run() {
            publishStats(log = true)
            handler.postDelayed(this, STATS_MS)
        }
    }
    private val retryRunnable = Runnable {
        if (peerEndpoint != null || link == Link.CONNECTING) return@Runnable
        if (reconnecting()) startReconnect() else startSearching()
    }

    // Auto-accepted handshakes that never complete would otherwise sit in CONNECTING forever.
    private val connectWatchdog = object : Runnable {
        override fun run() {
            if (stopped || peerEndpoint != null || link != Link.CONNECTING) return
            if (IntercomStore.state.value.pairing != null) {
                handler.postDelayed(this, CONNECT_WATCHDOG_MS) // the user is still comparing digits
                return
            }
            DiagnosticsLog.event("connect_timeout", mapOf("endpointId" to connectingEndpoint))
            connectingEndpoint?.let(transport::disconnect)
            connectingEndpoint = null
            setLink(if (reconnecting()) Link.RECONNECTING else Link.SEARCHING, "connect_timeout")
            scheduleRetry()
        }
    }

    fun start() {
        audio.setFrameSink(::onCapturedFrame)
        audio.setPlayoutSource(playout::poll)
        scope.launch {
            SessionStore.state.collect { s ->
                txEnabled = s.intercomActive
                val ep = peerEndpoint
                if (ep != null && helloReceived && s.localAudio != lastSentLocal) sendState(ep, s.localAudio)
            }
        }
        scope.launch { VoiceSettings.thresholdDb.collect { gate.thresholdDb = it.toDouble() } }
        scope.launch { VoiceSettings.alwaysTransmit.collect { gate.alwaysTransmit = it } }
        handler.post(pingRunnable)
        handler.post(deadRunnable)
        handler.post(duckRunnable)
        handler.postDelayed(statsRunnable, STATS_MS)
        DiagnosticsLog.event("intercom_start", mapOf("deviceId" to myId.toString(), "name" to myName, "peer" to PeerStore.peer.value?.name))
        startSearching()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        audio.setFrameSink(null)
        audio.setPlayoutSource(null)
        setDucking(false)
        val ep = peerEndpoint
        if (ep != null) {
            sendControl(ep, MessageType.BYE)
            // Give the BYE a moment to leave before the connection is torn down.
            Handler(Looper.getMainLooper()).postDelayed({ transport.stopAll() }, BYE_FLUSH_MS)
        } else {
            transport.stopAll()
        }
        peerEndpoint = null
        DiagnosticsLog.event("intercom_stop", mapOf("link" to link))
        setLink(Link.IDLE, "stop")
        SessionStore.update("intercom_stop") { it.copy(remoteAudio = RemoteAudio.UNKNOWN) }
        IntercomStore.reset()
    }

    // --- Commands from the UI ---

    fun connect(endpointId: String) {
        if (stopped || peerEndpoint != null || connectingEndpoint != null) return
        DiagnosticsLog.event("connect_request", mapOf("endpointId" to endpointId, "name" to discovered[endpointId]?.name))
        enterConnecting(endpointId, "user_connect")
        transport.requestConnection(endpointId)
    }

    fun acceptPairing(endpointId: String) {
        DiagnosticsLog.event("pairing_accept", mapOf("endpointId" to endpointId))
        IntercomStore.update { it.copy(pairing = null) }
        transport.accept(endpointId)
    }

    fun rejectPairing(endpointId: String) {
        DiagnosticsLog.event("pairing_reject", mapOf("endpointId" to endpointId))
        IntercomStore.update { it.copy(pairing = null) }
        transport.reject(endpointId)
    }

    // --- Searching / reconnecting (FR-3, FR-10) ---

    private fun startSearching() {
        if (stopped || peerEndpoint != null) return
        setLink(Link.SEARCHING, "search")
        discovered.clear()
        publishDiscovered()
        transport.stopAdvertising()
        transport.stopDiscovery()
        transport.startAdvertising()
        transport.startDiscovery()
    }

    private fun startReconnect() {
        if (stopped || peerEndpoint != null) return
        val peer = PeerStore.peer.value ?: return startSearching()
        setLink(Link.RECONNECTING, "reconnect")
        discovered.clear()
        publishDiscovered()
        transport.stopAdvertising()
        transport.stopDiscovery()
        val discoverer = myId.toString() < peer.deviceId.toString()
        DiagnosticsLog.event("reconnect_role", mapOf("role" to if (discoverer) "discover" else "advertise", "attempt" to retryAttempt))
        if (discoverer) transport.startDiscovery() else transport.startAdvertising()
    }

    private fun scheduleRetry() {
        if (stopped) return
        val delay = RETRY_MS[retryAttempt.coerceAtMost(RETRY_MS.size - 1)]
        retryAttempt++
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delay)
    }

    // --- PeerTransport.Listener (main thread) ---

    override fun onEndpointFound(endpointId: String, deviceId: UUID, name: String) {
        if (stopped) return
        val peer = PeerStore.peer.value
        val remembered = peer?.deviceId == deviceId
        discovered[endpointId] = Rider(endpointId, name, remembered)
        publishDiscovered()
        DiagnosticsLog.event("endpoint_found", mapOf("endpointId" to endpointId, "name" to name, "remembered" to remembered))
        if (!remembered || peerEndpoint != null || connectingEndpoint != null) return
        // Remembered partner: the phone with the smaller deviceId asks, so requests never cross.
        if (link == Link.RECONNECTING || myId.toString() < deviceId.toString()) {
            enterConnecting(endpointId, "auto_connect")
            transport.requestConnection(endpointId)
        }
    }

    override fun onEndpointLost(endpointId: String) {
        if (discovered.remove(endpointId) != null) publishDiscovered()
    }

    override fun onConnectionInitiated(endpointId: String, deviceId: UUID?, name: String, authDigits: String, incoming: Boolean) {
        if (stopped) return
        enterConnecting(endpointId, if (incoming) "incoming" else "outgoing")
        val remembered = deviceId != null && deviceId == PeerStore.peer.value?.deviceId
        DiagnosticsLog.event(
            "connection_initiated",
            mapOf("endpointId" to endpointId, "name" to name, "incoming" to incoming, "remembered" to remembered),
        )
        if (remembered) {
            transport.accept(endpointId)
        } else {
            IntercomStore.update { it.copy(pairing = Pairing(endpointId, name, authDigits)) }
        }
    }

    override fun onConnected(endpointId: String) {
        if (stopped) return
        peerEndpoint = endpointId
        connectingEndpoint = null
        retryAttempt = 0
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(connectWatchdog)
        transport.stopAdvertising()
        transport.stopDiscovery()
        discovered.clear()
        publishDiscovered()
        IntercomStore.update { it.copy(pairing = null) }
        helloReceived = false
        playout.reset()
        resetTx = true
        ctrlSeq = 0L
        lastSentLocal = null
        lastRxAt = SystemClock.elapsedRealtime()
        connectedAt = lastRxAt
        lastAudioAt = 0L
        interArrivalMs.clear()
        DiagnosticsLog.event(
            "link_connected",
            mapOf("endpointId" to endpointId, "reconnectMs" to if (lostAt > 0) lastRxAt - lostAt else null),
        )
        val local = SessionStore.state.value.localAudio
        sendControl(endpointId, MessageType.HELLO, Protocol.encodeHello(Hello(myId, myName, local)))
        lastSentLocal = local
    }

    override fun onConnectionFailed(endpointId: String, status: Int) {
        if (stopped) return
        DiagnosticsLog.event("connection_failed", mapOf("endpointId" to endpointId, "status" to status))
        if (connectingEndpoint == endpointId) connectingEndpoint = null
        handler.removeCallbacks(connectWatchdog)
        IntercomStore.update { if (it.pairing?.endpointId == endpointId) it.copy(pairing = null) else it }
        if (peerEndpoint == null) {
            setLink(if (reconnecting()) Link.RECONNECTING else Link.SEARCHING, "connect_failed")
            scheduleRetry()
        }
    }

    override fun onDisconnected(endpointId: String) {
        if (stopped) return
        if (endpointId == peerEndpoint) peerLost("disconnected")
        else if (endpointId == connectingEndpoint) onConnectionFailed(endpointId, -1)
    }

    override fun onReceive(endpointId: String, bytes: ByteArray) {
        if (stopped || endpointId != peerEndpoint) return
        val now = SystemClock.elapsedRealtime()
        lastRxAt = now
        val p = Protocol.decode(bytes) ?: return
        when (p.type) {
            MessageType.AUDIO -> onAudio(p, bytes.size, now)
            MessageType.PING -> transport.send(endpointId, Protocol.encode(MessageType.PONG, p.seq, p.timestampMs))
            MessageType.PONG -> rttMs = Protocol.elapsedU32(now32(), p.timestampMs)
            MessageType.HELLO -> onHello(p)
            MessageType.STATE -> Protocol.decodeState(p.payload)?.let { setRemote(remoteOf(it), "partner_state") }
            MessageType.BYE -> {
                DiagnosticsLog.event("partner_bye")
                transport.disconnect(endpointId)
                peerLost("bye")
            }
        }
    }

    override fun onBandwidthChanged(endpointId: String, quality: Int) {
        this.quality = quality
        DiagnosticsLog.event(
            "nearby_bandwidth",
            mapOf("quality" to quality, "sinceConnectMs" to if (connectedAt > 0) SystemClock.elapsedRealtime() - connectedAt else null),
        )
    }

    override fun onError(what: String, status: Int) {
        DiagnosticsLog.event("nearby_error", mapOf("what" to what, "status" to status))
        if (stopped || peerEndpoint != null) return
        when (what) {
            "start_advertising", "start_discovery" -> {
                if (status == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING ||
                    status == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING
                ) return
                if (connectingEndpoint != null) return // the handshake result drives the retry
            }
            // A request is already pending for this endpoint: wait for its onConnectionResult.
            "request_connection" -> if (status == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) return
            "accept" -> Unit
            else -> return // send / reject
        }
        if (status == ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL) transport.stopAll()
        connectingEndpoint = null
        handler.removeCallbacks(connectWatchdog)
        setLink(if (reconnecting()) Link.RECONNECTING else Link.SEARCHING, "nearby_error_$what")
        scheduleRetry()
    }

    // --- Messages ---

    private fun onHello(p: Packet) {
        val hello = Protocol.decodeHello(p.payload) ?: return
        helloReceived = true
        PeerStore.save(context, Peer(hello.deviceId, hello.name))
        IntercomStore.update { it.copy(peerName = hello.name) }
        DiagnosticsLog.event("partner_hello", mapOf("name" to hello.name, "localAudio" to hello.localAudio))
        lostAt = 0L
        setLink(Link.CONNECTED, "hello")
        setRemote(remoteOf(hello.localAudio), "hello")
    }

    private fun onAudio(p: Packet, size: Int, now: Long) {
        if (!helloReceived) return
        playout.offer(p.seq, Protocol.bytesToPcm(p.payload), p.speech)
        rxFrames++
        rxBytes += size
        // Only consecutive real-time frames: excludes pauses between phrases and pre-roll bursts.
        if (lastAudioAt > 0 && Protocol.elapsedU32(p.timestampMs, lastAudioTs) in 10L..40L) {
            interArrivalMs.add(now - lastAudioAt)
            if (interArrivalMs.size > 1_000) interArrivalMs.subList(0, 500).clear()
        }
        lastAudioAt = now
        lastAudioTs = p.timestampMs
        if (p.speech) {
            partnerSpeechRun++
            if (partnerSpeechRun >= PARTNER_SPEECH_FRAMES) {
                lastPartnerSpeechAt = now
                if (!ducking) setDucking(true)
            }
        } else {
            partnerSpeechRun = 0
        }
    }

    /** Audio I/O thread: every 20 ms helmet-mic frame while the route is up. */
    private fun onCapturedFrame(frame: ShortArray, count: Int) {
        if (!txEnabled) {
            txWasEnabled = false
            return
        }
        val ep = peerEndpoint ?: return
        if (resetTx || !txWasEnabled) {
            if (resetTx) {
                resetTx = false
                audioSeq = 0L
            }
            gate.reset() // after a pause (call, helmet lost) the old noise floor is meaningless
            gateWasActive = false
            txWasEnabled = true
        }
        val outs = gate.process(frame, count)
        if (gate.active != gateWasActive) {
            gateWasActive = gate.active
            DiagnosticsLog.event(
                if (gate.active) "gate_open" else "gate_close",
                mapOf("levelDb" to gate.lastLevelDb, "floorDb" to gate.floorDb, "partnerSpeaking" to ducking),
            )
        }
        if (outs.isEmpty()) return
        // A pre-roll burst goes out whole or not at all, so a phrase never starts in the middle.
        if (transport.inFlight >= MAX_IN_FLIGHT) {
            txDropped.addAndGet(outs.size.toLong())
            return
        }
        for (out in outs) {
            val bytes = Protocol.encode(
                MessageType.AUDIO, audioSeq++, now32(),
                if (out.speech) Protocol.FLAG_SPEECH else 0, Protocol.pcmToBytes(out.samples),
            )
            transport.send(ep, bytes)
            txFrames.incrementAndGet()
            txBytes.addAndGet(bytes.size.toLong())
        }
    }

    private fun sendControl(endpointId: String, type: MessageType, payload: ByteArray = ByteArray(0)) {
        transport.send(endpointId, Protocol.encode(type, ctrlSeq++, now32(), 0, payload))
    }

    private fun sendState(endpointId: String, local: LocalAudio) {
        lastSentLocal = local
        sendControl(endpointId, MessageType.STATE, Protocol.encodeState(local))
    }

    // --- State ---

    private fun peerLost(reason: String) {
        if (stopped) return
        val now = SystemClock.elapsedRealtime()
        DiagnosticsLog.event("peer_lost", mapOf("reason" to reason, "silentMs" to now - lastRxAt))
        lostAt = now
        peerEndpoint = null
        connectedAt = 0L
        rttMs = null
        quality = 0
        lastAudioAt = 0L
        interArrivalMs.clear()
        helloReceived = false
        playout.reset()
        setDucking(false)
        IntercomStore.update { it.copy(peerName = null) }
        setRemote(if (reason == "bye") RemoteAudio.STOPPED else RemoteAudio.UNKNOWN, reason)
        retryAttempt = 0
        if (PeerStore.peer.value != null) startReconnect() else startSearching()
    }

    private fun setDucking(on: Boolean) {
        if (ducking == on) return
        ducking = on
        if (!on) partnerSpeechRun = 0
        audio.setPartnerSpeech(on)
        IntercomStore.update { it.copy(partnerSpeaking = on) }
    }

    /** A remembered partner was connected before and got lost: reconnect with fixed roles (FR-10). */
    private fun reconnecting() = PeerStore.peer.value != null && lostAt > 0

    private fun enterConnecting(endpointId: String, reason: String) {
        connectingEndpoint = endpointId
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(connectWatchdog)
        handler.postDelayed(connectWatchdog, CONNECT_WATCHDOG_MS)
        if (link != Link.CONNECTING) setLink(Link.CONNECTING, reason)
    }

    private fun setLink(next: Link, reason: String) {
        link = next
        SessionStore.update(reason) { it.copy(link = next) }
    }

    private fun setRemote(next: RemoteAudio, reason: String) {
        SessionStore.update(reason) { it.copy(remoteAudio = next) }
    }

    private fun remoteOf(local: LocalAudio): RemoteAudio = when (local) {
        LocalAudio.READY -> RemoteAudio.READY
        LocalAudio.PAUSED_BY_CALL -> RemoteAudio.IN_CALL
        LocalAudio.OFF -> RemoteAudio.STOPPED
        LocalAudio.ROUTING, LocalAudio.DEVICE_LOST -> RemoteAudio.DEVICE_LOST
    }

    private fun publishDiscovered() {
        IntercomStore.update { it.copy(discovered = discovered.values.toList()) }
    }

    private fun publishStats(log: Boolean) {
        val tx = txBytes.get()
        val txKbps = (tx - statsTxBytes) * 8.0 / STATS_MS
        val rxKbps = (rxBytes - statsRxBytes) * 8.0 / STATS_MS
        statsTxBytes = tx
        statsRxBytes = rxBytes
        val stats = LinkStats(rttMs, quality, txKbps, rxKbps, transport.inFlight, txDropped.get(), playout.stats())
        IntercomStore.update { it.copy(stats = stats) }
        if (!log || peerEndpoint == null) return
        val sorted = interArrivalMs.sorted()
        fun pct(p: Double) = sorted.getOrNull(((sorted.size - 1) * p).toInt())
        DiagnosticsLog.event(
            "link_stats",
            mapOf(
                "rttMs" to rttMs, "quality" to quality, "txKbps" to txKbps, "rxKbps" to rxKbps,
                "txFrames" to txFrames.get(), "rxFrames" to rxFrames, "inFlight" to transport.inFlight,
                "txDropped" to txDropped.get(), "playout" to stats.playout.toString(),
                "interArrivalP50" to pct(0.5), "interArrivalP95" to pct(0.95), "interArrivalMax" to sorted.lastOrNull(),
            ),
        )
        interArrivalMs.clear()
    }

    private fun now32(): Long = SystemClock.elapsedRealtime() and U32
}
