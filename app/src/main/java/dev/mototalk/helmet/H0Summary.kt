package dev.mototalk.helmet

/** Result of one rung of the H0 bitrate ladder (MotoTalk_Helmet_Unit.md). [kbps] 0 = music only. */
data class StepResult(
    val kbps: Int,
    val sent: Int,
    /** Frames the phone could not send on time because the link was busy. */
    val late: Int,
    val echoed: Int,
    val rttP50Ms: Long?,
    val rttP95Ms: Long?,
    val rttMaxMs: Long?,
    /** Seconds the unit reported music playing. */
    val playSeconds: Int,
    /** The unit's music buffer ran dry: an audible stutter each. */
    val underflows: Long,
    val drops: Long,
    val maxGapMs: Long?,
    val minFillMs: Long?,
    val espRx: Long,
    val espRxGaps: Long,
    val espTxDropped: Long,
    val heardStutter: Int,
) {
    val lossPct: Double get() = if (sent == 0) 0.0 else 100.0 * (sent - echoed) / sent
    val musicPlayed: Boolean get() = playSeconds > 0
    val musicClean: Boolean get() = underflows == 0L && drops == 0L && heardStutter == 0

    /** Good enough for voice: music kept playing cleanly and almost every frame came back. */
    val passed: Boolean get() = musicPlayed && musicClean && lossPct < MAX_LOSS_PCT && late * 100 < sent.coerceAtLeast(1)

    fun toLog(): Map<String, Any?> = mapOf(
        "kbps" to kbps, "sent" to sent, "late" to late, "echoed" to echoed, "lossPct" to lossPct,
        "rttP50Ms" to rttP50Ms, "rttP95Ms" to rttP95Ms, "rttMaxMs" to rttMaxMs,
        "playSeconds" to playSeconds, "underflows" to underflows, "drops" to drops,
        "maxGapMs" to maxGapMs, "minFillMs" to minFillMs,
        "espRx" to espRx, "espRxGaps" to espRxGaps, "espTxDropped" to espTxDropped,
        "heardStutter" to heardStutter, "passed" to passed,
    )

    companion object {
        const val MAX_LOSS_PCT = 1.0
    }
}

/** Highest voice bitrate that passed, or null when none did. */
fun bestKbps(results: List<StepResult>): Int? = results.filter { it.kbps > 0 && it.passed }.maxOfOrNull { it.kbps }

/** Nearest-rank percentile; null for no samples. */
fun percentile(sorted: List<Long>, p: Int): Long? =
    if (sorted.isEmpty()) null else sorted[((p * sorted.size + 99) / 100 - 1).coerceIn(0, sorted.size - 1)]

/**
 * Collects one rung: the phone's sent/echoed frames and the unit's counters. The unit's counters
 * are cumulative, so the rung's share is the last snapshot minus the one before the rung began.
 * Called from the sender and the reader threads.
 */
class StepCollector(val kbps: Int, val startSeq: Long, private val baseline: Map<String, Long>?) {

    private var sent = 0
    private var late = 0
    private val rtts = ArrayList<Long>()
    private var first: Map<String, Long>? = null
    private var last: Map<String, Long>? = null
    private var playSeconds = 0
    private var maxGapMs: Long? = null
    private var minFillMs: Long? = null
    private var heard = 0

    @Synchronized fun onSent() { sent++ }
    @Synchronized fun onLate(frames: Int) { late += frames }
    @Synchronized fun onEcho(rttMs: Long) { rtts += rttMs }
    @Synchronized fun heardStutter() { heard++ }

    @Synchronized fun onEsp(stats: Map<String, Long>) {
        if (first == null) first = stats
        last = stats
        if (stats["play"] == 1L) {
            playSeconds++
            stats["gap_ms"]?.let { g -> maxGapMs = maxOf(maxGapMs ?: g, g) }
            stats["fill_ms"]?.takeIf { it >= 0 }?.let { f -> minFillMs = minOf(minFillMs ?: f, f) }
        }
    }

    @Synchronized fun result(): StepResult {
        val sorted = rtts.sorted()
        val base = baseline ?: first
        fun delta(key: String): Long {
            val end = last?.get(key) ?: return 0
            val start = base?.get(key) ?: 0
            return if (end >= start) end - start else end // the unit restarted in between
        }
        return StepResult(
            kbps = kbps, sent = sent, late = late, echoed = rtts.size,
            rttP50Ms = percentile(sorted, 50), rttP95Ms = percentile(sorted, 95), rttMaxMs = sorted.lastOrNull(),
            playSeconds = playSeconds, underflows = delta("under"), drops = delta("drop"),
            maxGapMs = maxGapMs, minFillMs = minFillMs,
            espRx = delta("rx"), espRxGaps = delta("rx_gap"), espTxDropped = delta("tx_drop"),
            heardStutter = heard,
        )
    }
}
