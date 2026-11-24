/*
 * Copyright (C) 2025 The KunYuShu Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tv.kyshu.xposed.config

import android.os.Binder
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-UID mock policy routing, ported from Kuroko-Teleport's per-UID isolation
 * design (`kuroko_shm.h` policy bits + 32-entry target table).
 *
 * Instead of Kuroko's shared-memory target table (which requires a root daemon),
 * kyushu ships the policy table through its existing HMAC-protected
 * `sendExtraCommand` channel as part of `put_config` — same decision points,
 * different transport.
 *
 * Policy semantics per target UID:
 *  - [POLICY_LOCATION]  : rewrite Location/GNSS/NMEA results for this UID
 *  - [POLICY_STEPS]     : rewrite step counter/detector events for this UID
 *  - [POLICY_IMU]       : rewrite motion sensor events for this UID
 *  - [POLICY_HIDE_WIFI] : hide real Wi-Fi scan results / connection info (return empty/redacted)
 *  - [POLICY_HIDE_CELL] : hide real cell info (return empty/null)
 *  - [POLICY_SUPPRESS_MEAS] : suppress raw GNSS measurements (observation-level detection)
 *
 * UIDs NOT in the table always get untouched real data — including the control
 * app itself and all system components. This is the Kuroko "min-cut" boundary:
 * every spoofing decision is made where the caller UID is natively known
 * (system_server / com.android.phone), never inside the target app.
 *
 * Presets (Kuroko TargetAppSheet semantics):
 *  - "cross-city strict"  = LOCATION or STEPS or IMU or HIDE_WIFI or HIDE_CELL or SUPPRESS_MEAS
 *  - "same-city outdoor"  = like strict but without HIDE_CELL (keep same-city towers)
 */
object UidPolicy {

    const val POLICY_LOCATION = 1 shl 0
    const val POLICY_STEPS = 1 shl 1
    const val POLICY_IMU = 1 shl 2
    const val POLICY_HIDE_WIFI = 1 shl 3
    const val POLICY_HIDE_CELL = 1 shl 4
    const val POLICY_SUPPRESS_MEAS = 1 shl 5

    const val PRESET_STRICT =
        POLICY_LOCATION or POLICY_STEPS or POLICY_IMU or POLICY_HIDE_WIFI or POLICY_HIDE_CELL or POLICY_SUPPRESS_MEAS
    const val PRESET_LOCAL_OUTDOOR =
        POLICY_LOCATION or POLICY_STEPS or POLICY_IMU or POLICY_HIDE_WIFI or POLICY_SUPPRESS_MEAS

    const val MAX_TARGETS = 32

    private class Entry(val uid: Int, val packageName: String?, val policies: Int)

    private val lock = Any()
    private val targets = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    @Volatile
    private var epoch = AtomicInteger(0)

    /** Last update time; hooks use it for stale-table diagnostics. */
    val lastUpdateElapsedRealtime = AtomicLong(0)

    /**
     * Replace the whole policy table. Called from [tv.kyshu.xposed.RemoteCommandHandler]
     * when the control app pushes `put_config` (or a dedicated `set_uid_targets` command).
     *
     * @param table map of UID → policy bitmask. Invalid entries (negative uid, policies
     *  out of range, table too large) are dropped rather than throwing — a malformed
     *  push must never crash system_server.
     */
    fun setTargets(table: Map<Int, Int>): Int {
        val cleaned = table.entries
            .filter { (uid, policies) ->
                uid > 0 && policies in 1..(POLICY_LOCATION or POLICY_STEPS or POLICY_IMU or
                    POLICY_HIDE_WIFI or POLICY_HIDE_CELL or POLICY_SUPPRESS_MEAS)
            }
            .take(MAX_TARGETS)
            .associate { it.key to it.value }
        synchronized(lock) {
            targets.clear()
            targets.putAll(cleaned)
            epoch.incrementAndGet()
        }
        return cleaned.size
    }

    fun clearTargets() {
        synchronized(lock) {
            targets.clear()
            epoch.incrementAndGet()
        }
    }

    fun snapshot(): Map<Int, Int> = Collections.unmodifiableMap(HashMap(targets))

    fun targetCount(): Int = targets.size

    fun epochValue(): Int = epoch.get()

    /** True if [uid] has any target entry (regardless of policy bits). */
    fun isTargetUid(uid: Int): Boolean = targets.containsKey(uid)

    /** True if [uid] is targeted AND has [policyFlag] set. */
    fun hasPolicy(uid: Int, policyFlag: Int): Boolean =
        targets[uid]?.let { (it and policyFlag) != 0 } == true

    /** True if ANY target has [policyFlag] set (Kuroko hasAnyTargetWithPolicy). */
    fun hasAnyTargetWithPolicy(policyFlag: Int): Boolean =
        targets.values.any { (it and policyFlag) != 0 }

    // ------------------------------------------------------------------
    // Caller-side convenience: resolve the UID at the decision point.
    // ------------------------------------------------------------------

    /** Check policy for the current binder caller. Only valid in system services. */
    fun callerHasPolicy(policyFlag: Int): Boolean = hasPolicy(Binder.getCallingUid(), policyFlag)

    /**
     * The master mock switch from a UID perspective: does this caller get mocked
     * location data? Falls back to the legacy global switch when no UID table has
     * ever been pushed (backward compatibility — old configs keep working).
     */
    fun shouldMockLocationForCaller(globalEnabled: Boolean): Boolean {
        if (epoch.get() == 0) return globalEnabled
        return callerHasPolicy(POLICY_LOCATION)
    }
}
