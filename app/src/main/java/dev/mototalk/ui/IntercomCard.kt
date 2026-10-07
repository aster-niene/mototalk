package dev.mototalk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mototalk.intercom.IntercomUi
import dev.mototalk.intercom.Pairing
import dev.mototalk.intercom.SessionState
import dev.mototalk.intercom.VoiceSettings
import kotlin.math.roundToInt

/** RIDE: the partner link, riders nearby, voice gate settings (POC requirements FR-3, FR-6, FR-12, FR-13). */
@Composable
fun IntercomCard(state: SessionState, ui: IntercomUi, onConnect: (String) -> Unit) {
    val threshold by VoiceSettings.thresholdDb.collectAsStateWithLifecycle()
    val always by VoiceSettings.alwaysTransmit.collectAsStateWithLifecycle()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Intercom", style = MaterialTheme.typography.titleSmall)
            Text(
                "Partner: ${ui.peerName ?: "—"} · ${state.link}" + if (state.intercomActive) " · ACTIVE" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            val talk = buildList {
                if (ui.transmitting) add("you are talking")
                if (ui.partnerSpeaking) add("partner is talking")
            }
            if (talk.isNotEmpty()) Text(talk.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            ui.stats?.let { s ->
                val q = when (s.quality) { 1 -> "LOW"; 2 -> "MEDIUM"; 3 -> "HIGH"; else -> "?" }
                Text(
                    "RTT ${s.rttMs ?: "—"} ms · link $q · tx %.0f / rx %.0f kbit/s".format(s.txKbps, s.rxKbps),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Underruns ${s.playout.underruns} · lost ${s.playout.gaps} · dropped ${s.txDropped}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (ui.peerName == null && state.link == dev.mototalk.intercom.Link.CONNECTING) {
                Text("Connecting… If asked, compare the code and press Accept on both phones.", style = MaterialTheme.typography.bodyMedium)
            } else if (ui.peerName == null && ui.discovered.isNotEmpty()) {
                Text("Riders nearby:", style = MaterialTheme.typography.bodyMedium)
                for (rider in ui.discovered) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(rider.name, modifier = Modifier.weight(1f))
                        if (rider.remembered) Text("connecting…") else Button(onClick = { onConnect(rider.endpointId) }) { Text("Connect") }
                    }
                }
            } else if (ui.peerName == null) {
                Text("Looking for the other phone… Start MotoTalk there too.", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Transmit always (no voice gate)", modifier = Modifier.weight(1f))
                Switch(checked = always, onCheckedChange = VoiceSettings::setAlwaysTransmit)
            }
            Text("Voice gate: +$threshold dB above background (lower = more sensitive)", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = threshold.toFloat(),
                onValueChange = { VoiceSettings.setThresholdDb(it.roundToInt()) },
                valueRange = VoiceSettings.MIN_THRESHOLD_DB.toFloat()..VoiceSettings.MAX_THRESHOLD_DB.toFloat(),
                steps = VoiceSettings.MAX_THRESHOLD_DB - VoiceSettings.MIN_THRESHOLD_DB - 1,
            )
        }
    }
}

/** First-time pairing: both phones show the same digits (FR-3). */
@Composable
fun PairingDialog(pairing: Pairing, onAccept: (String) -> Unit, onReject: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Connect to ${pairing.name}?") },
        text = { Text("Code: ${pairing.digits}\n\nThe same code must be on both phones. Press Accept on both.") },
        confirmButton = { TextButton(onClick = { onAccept(pairing.endpointId) }) { Text("Accept") } },
        dismissButton = { TextButton(onClick = { onReject(pairing.endpointId) }) { Text("Reject") } },
    )
}
