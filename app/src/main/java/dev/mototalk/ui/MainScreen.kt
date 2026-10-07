package dev.mototalk.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.mototalk.audio.AudioStats
import dev.mototalk.audio.AudioStatsStore
import dev.mototalk.diag.DeviceInfo
import dev.mototalk.intercom.IntercomStore
import dev.mototalk.intercom.PeerStore
import dev.mototalk.diag.DiagnosticsLog
import dev.mototalk.diag.Preflight
import dev.mototalk.intercom.LocalAudio
import dev.mototalk.intercom.SessionKind
import dev.mototalk.service.SessionStore
import kotlinx.coroutines.delay

private const val RECENT_SHOWN = 50

@Composable
fun MainScreen(
    onStart: (SessionKind) -> Unit,
    onStop: () -> Unit,
    onDuckTest: () -> Unit,
    onRecord: () -> Unit,
    onExport: () -> Unit,
    onConnect: (String) -> Unit,
    onAcceptPairing: (String) -> Unit,
    onRejectPairing: (String) -> Unit,
    onForgetPartner: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by SessionStore.state.collectAsStateWithLifecycle()
    val audio by AudioStatsStore.state.collectAsStateWithLifecycle()
    val intercom by IntercomStore.state.collectAsStateWithLifecycle()
    val partner by PeerStore.peer.collectAsStateWithLifecycle()
    val recent by DiagnosticsLog.recent.collectAsStateWithLifecycle()
    val device = remember { DeviceInfo.describe() }

    var missingRide by remember { mutableStateOf(Permissions.missing(context, Permissions.ride)) }
    var missingLoopback by remember { mutableStateOf(Permissions.missing(context, Permissions.loopback)) }
    var missingOptional by remember { mutableStateOf(Permissions.missing(context, Permissions.optional)) }
    var preflight by remember { mutableStateOf(Preflight.read(context)) }

    fun refresh() {
        missingRide = Permissions.missing(context, Permissions.ride)
        missingLoopback = Permissions.missing(context, Permissions.loopback)
        missingOptional = Permissions.missing(context, Permissions.optional)
        preflight = Preflight.read(context)
    }

    // Radios can be toggled from the quick settings shade without pausing the Activity, so poll.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                refresh()
                delay(2_000)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        DiagnosticsLog.event(
            "permissions_result",
            mapOf("granted" to result.mapKeys { it.key.substringAfterLast('.') }),
        )
        refresh()
    }

    val warnings = buildList {
        state.error?.let { add("Session did not start: $it ${state.errorDetail.orEmpty()}") }
        if (state.running && state.kind == SessionKind.LOOPBACK) {
            add("Loopback: keep the helmet on your head or the volume low, otherwise it howls.")
        }
        if (!preflight.bluetoothOn) add("Bluetooth is off — the helmet cannot connect.")
        if (!preflight.wifiOn) add("Wi-Fi is off — Nearby needs it for a fast phone-to-phone link.")
        if (preflight.wifiNetworkConnected) add("Joined a Wi-Fi network — disconnect for P2P tests (§8.7).")
        if (missingRide.isNotEmpty()) add("Missing: ${missingRide.joinToString { it.substringAfterLast('.') }}")
        if (missingOptional.isNotEmpty()) add("Notifications denied — the session notice is only in Task Manager.")
    }

    intercom.pairing?.let { PairingDialog(it, onAcceptPairing, onRejectPairing) }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("MotoTalk POC · 0.1.0", style = MaterialTheme.typography.titleLarge)
            }
            item {
                InfoCard(
                    "Device",
                    "${device["manufacturer"]} ${device["model"]}",
                    "Android ${device["android"]} · One UI ${device["oneUi"] ?: "?"}",
                    "Log label: ${DiagnosticsLog.phone}",
                )
            }
            item {
                InfoCard(
                    "Session",
                    if (state.running) "Running: ${state.kind}" else "Stopped",
                    "Link: ${state.link}",
                    "Local audio: ${state.localAudio}",
                    "Remote audio: ${state.remoteAudio}",
                    "Intercom active: ${state.intercomActive}",
                )
            }
            if (state.running && state.kind == SessionKind.RIDE) {
                item { IntercomCard(state, intercom, onConnect) }
            }
            if (state.running) {
                item { AudioCard(audio) }
            }
            if (warnings.isNotEmpty()) {
                item { InfoCard("Check", *warnings.toTypedArray()) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onStart(SessionKind.RIDE) },
                        enabled = !state.running && missingRide.isEmpty() && preflight.bluetoothOn,
                    ) { Text("START RIDE") }
                    OutlinedButton(
                        onClick = { onStart(SessionKind.LOOPBACK) },
                        enabled = !state.running && missingLoopback.isEmpty() && preflight.bluetoothOn,
                    ) { Text("Loopback") }
                    OutlinedButton(onClick = onStop, enabled = state.running) { Text("Stop") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRecord,
                        // Only on the helmet route: elsewhere it would record the phone's own mic (checklist M2).
                        enabled = state.localAudio == LocalAudio.READY && audio.recordingSecondsLeft == 0,
                    ) { Text(if (audio.recordingSecondsLeft > 0) "Recording… ${audio.recordingSecondsLeft}" else "Record 10 s") }
                    OutlinedButton(
                        onClick = onDuckTest,
                        enabled = state.running && !audio.duckActive,
                    ) { Text(if (audio.duckActive) "Ducking…" else "Duck test") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { permissionLauncher.launch(Permissions.all.toTypedArray()) },
                        enabled = missingRide.isNotEmpty() || missingOptional.isNotEmpty(),
                    ) { Text("Grant permissions") }
                    OutlinedButton(onClick = onExport) { Text("Export") }
                }
            }
            partner?.let { p ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Partner: ${p.name}", modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = onForgetPartner, enabled = !state.running) { Text("Forget") }
                    }
                }
            }
            item { TestStepper() }
            item {
                Text("Recent log", style = MaterialTheme.typography.titleMedium)
            }
            items(recent.asReversed().take(RECENT_SHOWN)) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp)
            }
        }
    }
}

@Composable
private fun AudioCard(audio: AudioStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Audio", style = MaterialTheme.typography.titleSmall)
            Text("Comm device: ${audio.commDevice ?: "—"}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Mic → ${audio.recordRouted ?: "—"} · Play → ${audio.trackRouted ?: "—"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Buffers: rec ${audio.recordBufferMs ?: "—"} ms · play ${audio.trackBufferMs ?: "—"} ms",
                style = MaterialTheme.typography.bodyMedium,
            )
            val db = audio.micDbfs
            Text(
                "Mic level: ${db?.let { "%.0f dBFS".format(it) } ?: "—"}" + if (audio.micSilenced) " · SILENCED" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { db?.let { ((it + 80.0) / 80.0).coerceIn(0.0, 1.0).toFloat() } ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            audio.ioError?.let { Text("I/O error: $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun InfoCard(title: String, vararg lines: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            for (line in lines) Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
