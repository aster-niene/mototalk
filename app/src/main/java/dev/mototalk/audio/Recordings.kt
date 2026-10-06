package dev.mototalk.audio

import android.content.Context
import java.io.File

/** WAV files from "Record 10 s", exported together with the logs. */
object Recordings {

    fun dir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
}
