package tv.kyshu.xposed.stability

import android.content.Context
import android.net.Uri
import tv.kyshu.xposed.utils.Log
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fail-closed, bounded provider read for explicit cross-reboot safe-mode recovery. */
object SafeModeRecovery {
    private const val AUTHORITY = "tv.kyshu.xposed.config"
    private const val MAX_AGE_MS = 5 * 60_000L

    fun tryApply(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "portal-safe-recovery").apply { isDaemon = true }
        }
        return try {
            val request = executor.submit<RecoveryRequest?> { readRequest(context) }.get(500, TimeUnit.MILLISECONDS)
                ?: return false
            if (request.nonce.length !in 32..128 || request.issuedAtMs > nowMs + 30_000L ||
                nowMs - request.issuedAtMs !in 0..MAX_AGE_MS || request.expiresAtMs !in nowMs..(nowMs + MAX_AGE_MS)) {
                Log.telemetry("SAFE_MODE", "RECOVERY_REJECTED", "invalid or expired request")
                return false
            }
            val applied = DomainGuard.applyManualRecovery(hash(request.nonce))
            Log.telemetry("SAFE_MODE", if (applied) "RECOVERY_APPLIED" else "RECOVERY_REJECTED",
                if (applied) "one-time recovery consumed" else "replayed recovery request")
            applied
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            Log.telemetry("SAFE_MODE", "RECOVERY_REJECTED", "provider unavailable or timed out")
            false
        } finally {
            executor.shutdownNow()
        }
    }

    private fun readRequest(context: Context): RecoveryRequest? {
        val values = mutableMapOf<String, String>()
        context.contentResolver.query(Uri.parse("content://$AUTHORITY/config"), null, null, null, null)?.use { cursor ->
            val keyIndex = cursor.getColumnIndex("key")
            val valueIndex = cursor.getColumnIndex("value")
            while (cursor.moveToNext()) values[cursor.getString(keyIndex)] = cursor.getString(valueIndex)
        } ?: return null
        return RecoveryRequest(
            nonce = values["safe_recovery_nonce"].orEmpty(),
            issuedAtMs = values["safe_recovery_issued_at"]?.toLongOrNull() ?: return null,
            expiresAtMs = values["safe_recovery_expires_at"]?.toLongOrNull() ?: return null,
        )
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class RecoveryRequest(val nonce: String, val issuedAtMs: Long, val expiresAtMs: Long)
}
