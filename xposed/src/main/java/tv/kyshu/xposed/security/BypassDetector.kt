package tv.kyshu.xposed.security

import android.os.Binder
import android.os.Process
import tv.kyshu.xposed.utils.FakeLoc
import tv.kyshu.xposed.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Identifies probing apps (anti-mock detectors, Xposed scanners)
 * and applies bypass actions to conceal the mock system.
 *
 * Probe signatures:
 * - LOCATION_SCAN: rapid getLastKnownLocation + requestSingleUpdate (<200ms)
 * - MOCK_CHECK: reads Settings.Secure "mock_location" or checks isFromMockProvider
 * - XP_DETECT: reads /proc/self/maps or ClassLoader.getResource("xposed")
 * - BINDER_REFLECT: calls ILocationManager via reflection instead of SDK API
 * - REPEAT_PROBE: same UID probes >5 times in 60s → HONEYPOT_HOSTILE
 */
object BypassDetector {

    enum class BypassAction {
        PASSTHROUGH,  // forward call untouched
        RETURN_REAL,  // return real location (even if mock active)
        RETURN_NULL,  // return null/empty result
        DELAY         // add artificial delay + return real location
    }

    private data class ProbeRecord(
        var count: Int = 0,
        var firstSeenMs: Long = System.currentTimeMillis(),
        var hostile: Boolean = false
    )

    private val probeRecords = ConcurrentHashMap<Int, ProbeRecord>()
    private const val PROBE_WINDOW_MS = 60_000L
    private const val HOSTILE_THRESHOLD = 5

    /**
     * Classify a location request based on the calling UID and probe history.
     * Returns the appropriate BypassAction for this request.
     *
     * Probe detection is done via [recordProbe] — callers that detect specific
     * probe signatures (MOCK_CHECK, XP_DETECT, LOCATION_SCAN, BINDER_REFLECT)
     * should call recordProbe directly. This method only checks the UID's
     * accumulated hostile status.
     */
    fun classify(uid: Int, packageName: String?, contextHint: String = ""): BypassAction {
        if (!FakeLoc.enable) return BypassAction.PASSTHROUGH

        // Skip bypass for system server and trusted UIDs
        if (uid == Process.SYSTEM_UID || uid == Process.PHONE_UID || uid == 1000) {
            return BypassAction.PASSTHROUGH
        }

        val record = probeRecords.computeIfAbsent(uid) { ProbeRecord() }

        // Already flagged as hostile → silently return real location
        if (record.hostile) return BypassAction.RETURN_REAL

        return BypassAction.PASSTHROUGH
    }

    /**
     * Record a probe detection from the given UID.
     * If the threshold is exceeded within the window, flag as hostile.
     */
    fun recordProbe(uid: Int, packageName: String?, reason: String) {
        val record = probeRecords.computeIfAbsent(uid) { ProbeRecord() }
        recordProbe(record, uid, reason)
    }

    private fun recordProbe(record: ProbeRecord, uid: Int, reason: String) {
        val now = System.currentTimeMillis()

        // Reset window if expired
        if (now - record.firstSeenMs > PROBE_WINDOW_MS) {
            record.count = 0
            record.firstSeenMs = now
        }

        record.count++

        if (record.count >= HOSTILE_THRESHOLD) {
            record.hostile = true
            Logger.warn("[BypassDetector] UID $uid flagged HONEYPOT_HOSTILE after $record.count probes: $reason")
        } else {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("[BypassDetector] Probe #${record.count} from UID $uid: $reason")
            }
        }
    }

    /**
     * Reset probe state for a UID (e.g., when app restarts).
     */
    fun resetProbe(uid: Int) {
        probeRecords.remove(uid)
    }

    /**
     * Clean up expired probe records. Should be called periodically.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val it = probeRecords.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.firstSeenMs > PROBE_WINDOW_MS * 2) {
                it.remove()
            }
        }
    }
}
