package tv.kyshu.xposed.utils

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object LogRouter {
    private var logDir: File? = null
    private var enabled = false
    private const val MAX_FILE_SIZE = 512L * 1024L
    private const val MAX_FILES = 28
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun setContext(context: Context?) {
        logDir = if (context != null) {
            context.filesDir.resolve("logs").also { it.mkdirs() }
        } else {
            null
        }
        enabled = logDir?.exists() == true
    }

    fun isEnabled(): Boolean = enabled
    fun enable() { enabled = true }
    fun disable() { enabled = false }

    fun write(level: String, tag: String, message: String) {
        if (!enabled) return
        val dir = logDir ?: return
        val today = LocalDate.now().format(dateFormat)
        val file = File(dir, "portal_$today.log")
        if (file.length() > MAX_FILE_SIZE) {
            val rotated = File(dir, "portal_${today}_${System.currentTimeMillis()}.log")
            file.renameTo(rotated)
            cleanup(dir)
        }
        val ts = LocalDate.now().atTime(LocalTime.now()).format(tsFormat)
        file.appendText("$ts [$level] [$tag] $message\n")
    }

    fun info(tag: String, msg: String) = write("INFO", tag, msg)
    fun warn(tag: String, msg: String) = write("WARN", tag, msg)
    fun error(tag: String, msg: String) = write("ERROR", tag, msg)
    fun debug(tag: String, msg: String) = write("DEBUG", tag, msg)

    private fun cleanup(dir: File) {
        val files = dir.listFiles { it.name.startsWith("portal_") }
            ?.sortedByDescending { it.lastModified() }?.toMutableList() ?: return
        while (files.size > MAX_FILES) {
            files.removeLastOrNull()?.delete()
        }
    }
}
