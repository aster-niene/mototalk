package dev.mototalk.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import dev.mototalk.diag.DiagnosticsLog

/** Test cases in order: two-phone intercom (MotoTalk_Intercom_Test_Cases.md), then Phase 0 (MotoTalk_M1_Test_Cases.md). */
private val TEST_CASES = listOf(
    "I-01" to "Первое соединение",
    "I-02" to "Разговор",
    "I-03" to "Музыка в разговоре",
    "I-04" to "Соединение без кнопок",
    "I-05" to "Обрыв и возврат",
    "I-06" to "Дальность",
    "I-07" to "Звонок",
    "I-08" to "15 минут в карманах",
    "TC-01" to "Шлем как устройство",
    "TC-02" to "Слышу себя",
    "TC-03" to "Запись 10 с",
    "TC-04" to "Задержка",
    "TC-05" to "Старт/стоп ×10",
    "TC-06" to "Spotify в сессии",
    "TC-07" to "Duck test",
    "TC-08" to "Google Maps",
    "TC-09" to "Звонок",
    "TC-10" to "Шлем выкл/вкл",
    "TC-11" to "Старт без шлема",
    "TC-12" to "Старт во время звонка",
    "TC-13" to "Кнопки шлема",
    "TC-14" to "30 минут",
)

private const val PREFS = "test_stepper_v2" // new test list in 0.1.0: start from scratch
private const val KEY_INDEX = "index"

/**
 * One tap per test instead of typing marks: ▶ logs `test_step` for the next test case.
 * The position survives app restarts.
 */
@Composable
fun TestStepper() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    // -1 = no test started yet
    var index by remember { mutableIntStateOf(prefs.getInt(KEY_INDEX, -1)) }

    fun go(next: Int) {
        index = next
        prefs.edit { putInt(KEY_INDEX, next) }
        val (id, title) = TEST_CASES.getOrNull(next) ?: ("done" to "all tests done")
        DiagnosticsLog.event("test_step", mapOf("tc" to id, "title" to title))
    }

    val current = TEST_CASES.getOrNull(index)
    val next = TEST_CASES.getOrNull(index + 1)
    Text(current?.let { "Сейчас: ${it.first} ${it.second}" } ?: "Тест не выбран")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { go(index - 1) }, enabled = index > 0) { Text("◀") }
        Button(onClick = { go(index + 1) }, enabled = index < TEST_CASES.size) {
            Text(next?.let { "▶ ${it.first} ${it.second}" } ?: "▶ Готово")
        }
    }
}
