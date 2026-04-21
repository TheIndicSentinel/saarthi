package com.saarthi.core.inference

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {

    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val candidates = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "saarthi_debug.log"),
            File(context.getExternalFilesDir(null), "saarthi_debug.log"),
            File(context.filesDir, "saarthi_debug.log"),
        )
        file = candidates.firstOrNull {
            runCatching { it.parentFile?.mkdirs(); it.createNewFile() || it.exists() }.getOrDefault(false)
        } ?: candidates.last()

        runCatching { file?.writeText("") } // clear on each app start
        log("APP", "=== Saarthi debug log ===  path=${file?.absolutePath}")
        log("APP", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})  device=${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun path(): String = file?.absolutePath ?: "(not initialized — call DebugLogger.init first)"

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        Log.d("SaarthiDbg", line)
        runCatching { file?.appendText(line + "\n") }
    }
}
