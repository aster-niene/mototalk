package dev.mototalk

import android.app.Application
import dev.mototalk.diag.DeviceIdentity
import dev.mototalk.diag.DeviceInfo
import dev.mototalk.diag.DiagnosticsLog
import dev.mototalk.intercom.PeerStore
import dev.mototalk.intercom.VoiceSettings
import dev.mototalk.service.RideNotification

class MotoTalkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLog.init(this, DeviceIdentity.phoneLabel(this))
        RideNotification.createChannel(this)
        VoiceSettings.init(this)
        PeerStore.init(this)
        DiagnosticsLog.event(
            "app_start",
            mapOf("deviceId" to DeviceIdentity.deviceId(this).toString(), "device" to DeviceInfo.describe()),
        )
    }
}
