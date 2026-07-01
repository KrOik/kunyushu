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

package tv.kyshu.xposed.utils

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory ring-buffer audit trail for mock decisions and injections.
 *
 * Captures the last [MAX_EVENTS] events across all hooks so that failure
 * paths and successful mock injections can be reconstructed post-hoc.
 *
 * Events are emitted to XposedBridge log immediately AND buffered for later dump.
 */
object MockAuditTrail {
    private const val MAX_EVENTS = 256

    enum class Category {
        CONFIG,      // Config loading / reload
        HOOK_INIT,   // Hook installation decision
        SKIP,        // Mock skipped with reason
        INJECT,      // Mock injected (location/sensor/wifi)
        STATE,       // State transition (enable/disable/silentMode)
        HEARTBEAT,   // Periodic "still working" pulse
    }

    data class Event(
        val seq: Int,
        val ts: Long,
        val category: Category,
        val tag: String,
        val message: String,
    ) {
        override fun toString(): String = "[#$seq ${formatTs(ts)}] [$category] [$tag] $message"

        companion object {
            private fun formatTs(ts: Long): String {
                val sb = StringBuilder(23)
                val t = ts
                sb.append(t / 1000)
                sb.append('.')
                sb.append(String.format("%03d", t % 1000))
                return sb.toString()
            }
        }
    }

    private val seq = AtomicInteger(0)
    private val buffer = ConcurrentLinkedQueue<Event>()

    // ---- record ----

    fun config(tag: String, message: String) = record(Category.CONFIG, tag, message)
    fun hookInit(tag: String, message: String) = record(Category.HOOK_INIT, tag, message)
    fun skip(tag: String, reason: String) = record(Category.SKIP, tag, reason)
    fun inject(tag: String, detail: String) = record(Category.INJECT, tag, detail)
    fun state(tag: String, message: String) = record(Category.STATE, tag, message)
    fun heartbeat(tag: String, message: String) = record(Category.HEARTBEAT, tag, message)

    fun record(category: Category, tag: String, message: String) {
        val ev = Event(seq.incrementAndGet(), System.currentTimeMillis(), category, tag, message)
        buffer.add(ev)
        while (buffer.size > MAX_EVENTS) {
            buffer.poll()
        }
        Logger.debug("[Audit] [$category] [$tag] $message")
    }

    // ---- dump ----

    /** Snapshots all buffered events into a list (oldest first). */
    fun snapshot(): List<Event> = buffer.toList()

    /** Dumps the full audit trail as a single multi-line string. */
    fun dump(): String {
        val events = snapshot()
        if (events.isEmpty()) return "[MockAuditTrail] No events recorded."
        val sb = StringBuilder()
        sb.appendLine("[MockAuditTrail] Dump — ${events.size} events")
        events.forEach { sb.appendLine(it.toString()) }
        return sb.toString().trimEnd()
    }

    /** Dumps events of a specific category. */
    fun dump(category: Category): String {
        val events = snapshot().filter { it.category == category }
        if (events.isEmpty()) return "[MockAuditTrail] No $category events."
        val sb = StringBuilder()
        sb.appendLine("[MockAuditTrail] $category — ${events.size} events")
        events.forEach { sb.appendLine(it.toString()) }
        return sb.toString().trimEnd()
    }

    /** Dumps the last N events. */
    fun dumpLast(n: Int): String {
        val events = snapshot().takeLast(n.coerceAtMost(MAX_EVENTS))
        if (events.isEmpty()) return "[MockAuditTrail] No events recorded."
        val sb = StringBuilder()
        sb.appendLine("[MockAuditTrail] Last ${events.size} events")
        events.forEach { sb.appendLine(it.toString()) }
        return sb.toString().trimEnd()
    }

    /** Clears the buffer. */
    fun clear() {
        buffer.clear()
        seq.set(0)
    }
}
