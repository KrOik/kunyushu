package tv.kyshu.xposed.utils

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object CrashForensics {
    private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    private val lock = ReentrantLock()
    private var crashFile: File? = null

    fun setContext(context: Context?) {
        val cacheDir = context?.cacheDir ?: return
        lock.withLock {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            crashFile = File(cacheDir, ".portal_crash_${fingerprintHash()}.log")
            crashFile?.apply {
                if (!exists()) createNewFile()
                setReadable(true, true)
                setWritable(true, true)
                setExecutable(false, false)
            }
            cleanupLocked(cacheDir)
        }
    }

    fun skip(stepId: String, gateFailed: String) {
        appendLine("SKIP:$stepId:$gateFailed:${fingerprintHash()}:${System.currentTimeMillis()}")
    }

    fun crash(scope: String, reason: String, error: Throwable? = null) {
        val extra = JSONObject().apply {
            put("error", error?.javaClass?.simpleName ?: "unknown")
            put("message", error?.message ?: "")
        }
        appendLine("CRASH:$scope:$reason:${fingerprintHash()}:${System.currentTimeMillis()}:$extra")
    }

    fun clearForTest() {
        lock.withLock {
            crashFile = null
        }
    }

    private fun appendLine(line: String) {
        lock.withLock {
            crashFile?.appendText(line + "\n")
        }
    }

    private fun cleanupLocked(cacheDir: File) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith(".portal_crash_") && file.lastModified() < cutoff
        }?.forEach { it.delete() }
    }

    private fun fingerprintHash(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Build.FINGERPRINT.orEmpty().toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    /**
     * 读取当前 crash 日志内容，用于统一日志导出。
     * 返回空字符串表示无 crash 记录。
     */
    fun readCrashLog(): String {
        return lock.withLock {
            try {
                crashFile?.takeIf { it.exists() && it.length() > 0L }?.readText()?.trim() ?: ""
            } catch (_: Exception) { "" }
        }
    }
}
