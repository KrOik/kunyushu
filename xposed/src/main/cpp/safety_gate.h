/*
 * Copyright (C) 2025-2026 The KunYuShu Project
 * Ported to Portal (tv.kyshu) with path prefix adjustments.
 *
 * Licensed under the Apache License, Version 2.0
 */

#ifndef _SAFETY_GATE_H
#define _SAFETY_GATE_H

#include <atomic>
#include <chrono>
#include <memory>
#include <thread>
#include <utility>

static constexpr int kCrashMeltdownThreshold = 3;

extern char g_app_cache_dir[256];

void set_app_cache_dir(const char* cache_dir);
const char* get_app_cache_dir();

bool is_device_whitelisted_strict();
bool is_device_allowed();
bool is_step_permanently_disabled(const char* step_id);
void mark_step_permanently_disabled(const char* step_id, const char* reason);
bool is_art_version_locked();
void write_art_version_lock();
bool is_terminal_despair_locked();
void write_terminal_despair_lock();
int read_crash_counter();
void write_crash_counter(int count);
bool check_crash_meltdown();
void record_crash_recovery();
void reset_crash_counter();
void log_skip(const char* step_id, const char* gate_failed);
bool is_safe_mode();

// Template A: synchronous fail-fast wrapper
template<typename Func>
bool safe_execute_failfast(const char* step_id, Func&& operation) {
    if (is_terminal_despair_locked()) {
        log_skip(step_id, "DESPAIR_LOCK");
        return false;
    }
    if (is_art_version_locked()) {
        log_skip(step_id, "ART_VERSION_LOCK");
        return false;
    }
    if (!is_device_whitelisted_strict()) {
        log_skip(step_id, "VERSION_GATE");
        return false;
    }
    if (is_safe_mode()) {
        log_skip(step_id, "BOOT_GATE");
        return false;
    }
    if (is_step_permanently_disabled(step_id)) {
        log_skip(step_id, "PERSISTENT_DISABLE");
        return false;
    }

    bool success = false;
    try {
        success = operation();
    } catch (...) {
        success = false;
    }
    if (!success) {
        log_skip(step_id, "EXECUTION_FAILED");
        mark_step_permanently_disabled(step_id, "EXECUTION_FAILED");
    }
    return success;
}

// Template B: bounded watchdog wrapper
template<typename Func>
bool safe_execute_with_watchdog(const char* step_id, Func&& operation, int timeout_ms = 2000) {
    if (is_terminal_despair_locked()) {
        log_skip(step_id, "DESPAIR_LOCK");
        return false;
    }
    if (is_art_version_locked()) {
        log_skip(step_id, "ART_VERSION_LOCK");
        return false;
    }
    if (!is_device_whitelisted_strict()) {
        log_skip(step_id, "VERSION_GATE");
        return false;
    }
    if (is_safe_mode()) {
        log_skip(step_id, "BOOT_GATE");
        return false;
    }
    if (is_step_permanently_disabled(step_id)) {
        log_skip(step_id, "PERSISTENT_DISABLE");
        return false;
    }

    struct WatchdogState {
        std::atomic<bool> done{false};
        std::atomic<bool> success{false};
    };
    auto state = std::make_shared<WatchdogState>();

    auto op_shared = std::make_shared<std::decay_t<Func>>(std::forward<Func>(operation));
    std::thread worker([state, op_shared]() {
        try {
            state->success.store((*op_shared)(), std::memory_order_release);
        } catch (...) {
            state->success.store(false, std::memory_order_release);
        }
        state->done.store(true, std::memory_order_release);
    });

    auto deadline = std::chrono::steady_clock::now() +
                    std::chrono::milliseconds(timeout_ms);

    while (!state->done.load(std::memory_order_acquire)) {
        if (std::chrono::steady_clock::now() > deadline) {
            worker.detach();
            log_skip(step_id, "WATCHDOG_TIMEOUT");
            mark_step_permanently_disabled(step_id, "WATCHDOG_TIMEOUT");
            return false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    worker.join();

    if (!state->success.load(std::memory_order_acquire)) {
        log_skip(step_id, "EXECUTION_FAILED");
        return false;
    }
    return true;
}

#endif // _SAFETY_GATE_H
