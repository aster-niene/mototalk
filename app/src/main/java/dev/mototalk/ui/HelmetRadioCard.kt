package dev.mototalk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mototalk.helmet.H0Test
import dev.mototalk.helmet.H0Test.Phase
import dev.mototalk.helmet.StepResult

/** H0: does a voice-sized data stream to the ESP32 make the music stutter? (MotoTalk_Helmet_Unit.md) */
@Composable
fun HelmetRadioCard(canRun: Boolean) {
    val context = LocalContext.current
    val ui by H0Test.ui.collectAsStateWithLifecycle()
    val busy = ui.phase == Phase.CONNECTING || ui.phase == Phase.RUNNING

    // The test runs on the bench with the app open: keep the screen from sleeping meanwhile.
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Проверка радио шлема (H0)", style = MaterialTheme.typography.titleSmall)
            val body = MaterialTheme.typography.bodyMedium
            when (ui.phase) {
                Phase.IDLE, Phase.DONE, Phase.FAILED -> {
                    ui.message?.let { Text(it, style = body) }
                    if (ui.results.isEmpty()) {
                        Text(
                            "1. Соедини телефон с ${H0Test.DEVICE_NAME} в настройках Bluetooth.\n" +
                                "2. Включи музыку в Spotify: она пойдёт на плату.\n" +
                                "3. Нажми «Старт». Тест идёт 2,5 минуты.",
                            style = body,
                        )
                    }
                }
                Phase.CONNECTING -> Text("Подключаюсь к ${H0Test.DEVICE_NAME}…", style = body)
                Phase.RUNNING -> {
                    val kbps = H0Test.LADDER.getOrNull(ui.step)
                    val what = if (kbps == 0) "только музыка" else "голос $kbps кбит/с"
                    Text("Шаг ${ui.step + 1} из ${H0Test.LADDER.size}: $what · осталось ${ui.secondsLeft} с", style = body)
                    val music = when (ui.esp["play"]) {
                        1L -> "музыка играет, перебоев ${ui.esp["under"] ?: 0}"
                        0L -> "музыка не играет: включи Spotify"
                        else -> "ждём данные от платы"
                    }
                    Text("Плата: $music · задержка ${ui.lastRttMs ?: "—"} мс", style = body)
                }
            }
            for (r in ui.results) Text(describe(r), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) {
                    Button(onClick = H0Test::heardStutter, enabled = ui.phase == Phase.RUNNING) { Text("Слышу заикание") }
                    OutlinedButton(onClick = H0Test::stop) { Text("Стоп") }
                } else {
                    Button(onClick = { H0Test.start(context) }, enabled = canRun) {
                        Text(if (ui.results.isEmpty()) "Старт" else "Ещё раз")
                    }
                }
            }
            if (!canRun && !busy) Text("Нужен включённый Bluetooth и разрешение: нажми Grant permissions.", style = body)
        }
    }
}

private fun describe(r: StepResult): String {
    val head = if (r.kbps == 0) "Только музыка" else "${r.kbps} кбит/с"
    val music = when {
        !r.musicPlayed -> "музыка не играла"
        r.musicClean -> "музыка без перебоев"
        else -> "перебоев: ${r.underflows + r.drops}" + if (r.heardStutter > 0) ", на слух: ${r.heardStutter}" else ""
    }
    if (r.kbps == 0) return "$head: $music"
    val rtt = r.rttP50Ms?.let { "задержка $it / ${r.rttP95Ms} мс" } ?: "ответов нет"
    return "$head: $music · потери %.1f%% · $rtt".format(r.lossPct) + (if (r.passed) " ✓" else " ✗")
}
