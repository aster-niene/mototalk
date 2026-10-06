package dev.mototalk.diag

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

private val RESERVED_KEYS = setOf("wallMs", "monoMs", "phone", "event")

/**
 * One diagnostics line (POC requirements FR-12): `{wallMs, monoMs, phone, event, ...fields}`.
 * A field that collides with a reserved key is kept under `_<key>`.
 */
internal fun logLine(
    wallMs: Long,
    monoMs: Long,
    phone: String,
    event: String,
    fields: Map<String, Any?>,
): Map<String, Any?> {
    val line = linkedMapOf<String, Any?>(
        "wallMs" to wallMs,
        "monoMs" to monoMs,
        "phone" to phone,
        "event" to event,
    )
    for ((k, v) in fields) {
        line[if (k in RESERVED_KEYS) "_$k" else k] = v
    }
    return line
}

internal fun logFileName(date: LocalDate, phone: String): String =
    "mototalk-$date-${phone.replace(Regex("[^A-Za-z0-9_-]"), "_")}.jsonl"

/**
 * Append-only JSONL diagnostics log, one file per day in `filesDir/logs`.
 * Writes happen on a single background thread and are flushed per line,
 * so a crash loses at most the line being written.
 */
object DiagnosticsLog {

    private const val RECENT_LIMIT = 200

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-log").apply { isDaemon = true }
    }

    @Volatile private var logDir: File? = null
    @Volatile var phone: String = "unknown"
        private set

    // Accessed only on the executor thread.
    private var writer: BufferedWriter? = null
    private var writerDate: LocalDate? = null

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    /** Last lines for the on-screen diagnostics view. */
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    fun init(context: Context, phoneLabel: String) {
        phone = phoneLabel
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
    }

    fun event(name: String, fields: Map<String, Any?> = emptyMap()) {
        val wallMs = System.currentTimeMillis()
        val text = Json.encode(logLine(wallMs, SystemClock.elapsedRealtime(), phone, name, fields))
        _recent.update { (it + text).takeLast(RECENT_LIMIT) }
        val dir = logDir ?: return
        executor.execute { append(dir, wallMs, text) }
    }

    fun mark(label: String) = event("mark", mapOf("label" to label))

    fun logFiles(): List<File> =
        logDir?.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.sortedBy { it.name }.orEmpty()

    private fun append(dir: File, wallMs: Long, text: String) {
        try {
            val date = Instant.ofEpochMilli(wallMs).atZone(ZoneId.systemDefault()).toLocalDate()
            val w = writer?.takeIf { writerDate == date } ?: run {
                writer?.close()
                BufferedWriter(FileWriter(File(dir, logFileName(date, phone)), true)).also {
                    writer = it
                    writerDate = date
                }
            }
            w.write(text)
            w.newLine()
            w.flush()
        } catch (e: IOException) {
            val failure = Json.encode(mapOf("event" to "log_write_failed", "error" to e.toString()))
            _recent.update { (it + failure).takeLast(RECENT_LIMIT) }
        }
    }
}
