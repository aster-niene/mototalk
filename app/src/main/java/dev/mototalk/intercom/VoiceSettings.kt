package dev.mototalk.intercom

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Voice gate settings (POC requirements FR-6), persisted, changeable during a ride. */
object VoiceSettings {

    const val MIN_THRESHOLD_DB = 6
    const val MAX_THRESHOLD_DB = 24
    private const val DEFAULT_THRESHOLD_DB = 12

    private const val PREFS = "voice_settings"
    private const val KEY_THRESHOLD = "gateThresholdDb"
    private const val KEY_ALWAYS = "alwaysTransmit"

    private var prefs: SharedPreferences? = null
    private val _thresholdDb = MutableStateFlow(DEFAULT_THRESHOLD_DB)
    private val _alwaysTransmit = MutableStateFlow(false)

    /** How far above the background the helmet mic must rise to count as speech. Lower = more sensitive. */
    val thresholdDb: StateFlow<Int> = _thresholdDb.asStateFlow()

    /** Send the microphone all the time instead of only while speaking. */
    val alwaysTransmit: StateFlow<Boolean> = _alwaysTransmit.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _thresholdDb.value = p.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD_DB)
        _alwaysTransmit.value = p.getBoolean(KEY_ALWAYS, false)
    }

    fun setThresholdDb(value: Int) {
        val v = value.coerceIn(MIN_THRESHOLD_DB, MAX_THRESHOLD_DB)
        _thresholdDb.value = v
        prefs?.edit { putInt(KEY_THRESHOLD, v) }
    }

    fun setAlwaysTransmit(value: Boolean) {
        _alwaysTransmit.value = value
        prefs?.edit { putBoolean(KEY_ALWAYS, value) }
    }
}
