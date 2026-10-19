package id.fhorizon.mobile.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Launcher diagnostics: logcat, an in-memory ring shown in the Log screen,
 * and FHorizon/Logs/launcher.log when shared storage is writable.
 */
object AppLog {
    private const val TAG = "FHorizon"
    private const val MAX_LINES = 2000
    private val lines = CopyOnWriteArrayList<String>()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    fun attachFile(target: File) {
        try {
            target.parentFile?.mkdirs()
            if (target.length() > 4L * 1024 * 1024) {
                val old = File(target.parentFile, target.name + ".1")
                old.delete()
                target.renameTo(old)
            }
            file = target
        } catch (e: Exception) {
            Log.w(TAG, "launcher log file unavailable", e)
        }
    }

    fun i(message: String) = add("I", message, null)
    fun w(message: String, error: Throwable? = null) = add("W", message, error)
    fun e(message: String, error: Throwable? = null) = add("E", message, error)

    fun snapshot(): List<String> = lines.toList()

    fun clearMemory() = lines.clear()

    @Synchronized
    private fun add(level: String, message: String, error: Throwable?) {
        when (level) {
            "E" -> Log.e(TAG, message, error)
            "W" -> Log.w(TAG, message, error)
            else -> Log.i(TAG, message)
        }
        val text = buildString {
            append(stamp.format(Date())).append(' ').append(level).append(' ').append(message)
            if (error != null) append(" | ").append(error.javaClass.simpleName).append(": ")
                .append(error.message ?: "")
        }
        lines.add(text)
        while (lines.size > MAX_LINES) lines.removeAt(0)
        val target = file ?: return
        try {
            target.appendText(text + "\n")
        } catch (_: Exception) {
            file = null
        }
    }
}
