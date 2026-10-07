package dev.mototalk.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.FileProvider
import dev.mototalk.audio.Recordings
import dev.mototalk.diag.DiagnosticsLog
import dev.mototalk.intercom.PeerStore
import dev.mototalk.intercom.ServiceError
import dev.mototalk.intercom.SessionKind
import dev.mototalk.service.RideService
import dev.mototalk.service.SessionStore

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MotoTalkTheme {
                MainScreen(
                    onStart = ::startSession,
                    onStop = { RideService.stop(this) },
                    onDuckTest = { RideService.duckTest(this) },
                    onRecord = { RideService.recordSample(this) },
                    onExport = ::exportFiles,
                    onConnect = { RideService.connect(this, it) },
                    onAcceptPairing = { RideService.acceptPairing(this, it) },
                    onRejectPairing = { RideService.rejectPairing(this, it) },
                    onForgetPartner = {
                        DiagnosticsLog.event("partner_forget")
                        PeerStore.forget(this)
                    },
                )
            }
        }
    }

    private fun startSession(kind: SessionKind) {
        val required = if (kind == SessionKind.RIDE) Permissions.ride else Permissions.loopback
        val missing = Permissions.missing(this, required)
        DiagnosticsLog.event("ui_start", mapOf("kind" to kind, "missingPermissions" to missing))
        if (missing.isNotEmpty()) {
            SessionStore.update("permission_missing") {
                it.copy(error = ServiceError.PERMISSION_MISSING, errorDetail = missing.joinToString())
            }
            return
        }
        RideService.start(this, kind)
    }

    /** Shares all diagnostics logs and "Record 10 s" WAV files. */
    private fun exportFiles() {
        val files = DiagnosticsLog.logFiles() + Recordings.list(this)
        DiagnosticsLog.event("logs_export", mapOf("files" to files.map { it.name }))
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.logs", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Export MotoTalk logs"))
    }
}
