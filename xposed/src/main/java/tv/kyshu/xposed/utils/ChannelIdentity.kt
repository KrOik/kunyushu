package tv.kyshu.xposed.utils

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Device-specific IPC channel identity.
 *
 * Derives the provider name from Build.FINGERPRINT so each device
 * uses a unique, non-guessable string for the sendExtraCommand
 * back-channel. Caches the first-boot value so OTA system updates
 * that change FINGERPRINT do not silently break the IPC channel.
 *
 * init(context) MUST be called during module boot before any IPC
 * access — typically from FakeLocation.handleLoadPackage("android").
 */
object ChannelIdentity {
    private const val CACHE_FILE = ".portal_channel_id"
    private const val FALLBACK_NAME = "portal"

    @Volatile
    private var cacheDir: File? = null

    private val cachedProviderName: String by lazy {
        val derived = deriveFromFingerprint()
        val cached = readCached()
        when {
            cached == null -> {
                // First boot: cache and use derived
                writeCached(derived)
                derived
            }
            cached == derived -> {
                // Same fingerprint as before
                derived
            }
            else -> {
                // OTA detected: keep cached name so existing IPC channel stays valid
                Logger.warn("[ChannelIdentity] OTA detected: cached=$cached derived=$derived, keeping cached")
                FlightRecorder.state("IPC", "CHANNEL_OTA",
                    extra = mapOf("cached" to cached, "derived" to derived))
                cached
            }
        }
    }

    val providerName: String by lazy {
        cachedProviderName
    }

    /**
     * MUST be called early in boot, before any IPC access.
     */
    fun init(context: Context) {
        cacheDir = context.filesDir
        // Force initialization while context is available
        // (lazy val will be evaluated on first access, but init ensures
        //  the cache file is resolved while we have context)
        providerName.let { /* eager init */ }
    }

    private fun deriveFromFingerprint(): String {
        val fp = Build.FINGERPRINT.orEmpty()
        if (fp.isBlank()) return FALLBACK_NAME
        val hash = fp.take(8).fold(0) { acc, c ->
            acc * 31 + c.code
        }.toUInt().toString(16).take(8)
        if (hash.isBlank()) return FALLBACK_NAME
        return "cmd_$hash"
    }

    private fun cacheFile(): File? {
        val dir = cacheDir ?: return null
        return File(dir, CACHE_FILE)
    }

    private fun readCached(): String? {
        val file = cacheFile() ?: return null
        return kotlin.runCatching {
            if (!file.isFile) return null
            file.readText().trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun writeCached(name: String) {
        val file = cacheFile() ?: return
        kotlin.runCatching {
            file.parentFile?.mkdirs()
            file.writeText(name)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }.onFailure { e ->
            Logger.warn("[ChannelIdentity] Failed to write cache: ${e.message}")
        }
    }
}
