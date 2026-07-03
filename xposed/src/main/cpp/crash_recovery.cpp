/*
 * Copyright (C) 2025-2026 The KunYuShu Project
 * Ported to Portal (tv.kyshu) with library name and path adjustments.
 *
 * Licensed under the Apache License, Version 2.0
 */

#include "crash_recovery.h"
#include <atomic>

#include <signal.h>
#include <setjmp.h>
#include <cstring>
#include <dlfcn.h>
#include <link.h>
#include <vector>
#include <algorithm>
#include <unistd.h>
#include <fcntl.h>
#include "logging.h"
#include "safety_gate.h"

// ---- Code range tracking ----
// Async-signal-safe: fixed-size C array, no STL containers or algorithms.
// Typical count is 1-2 ranges (libportal), so linear scan is fine.
static constexpr int kMaxCodeRanges = 8;
static CodeRange g_our_code_ranges[kMaxCodeRanges];
static volatile sig_atomic_t g_code_range_count = 0;
static volatile sig_atomic_t g_ranges_initialized = 0;

void init_our_code_ranges() {
    if (g_ranges_initialized) return;
    dl_iterate_phdr([](struct dl_phdr_info* info, size_t size, void*) -> int {
        (void)size;
        if (info->dlpi_name == nullptr || info->dlpi_name[0] == '\0') return 0;
        const char* name = info->dlpi_name;
        if (strstr(name, "libportal")) {
            uintptr_t end = info->dlpi_addr;
            for (int i = 0; i < info->dlpi_phnum; i++) {
                if (info->dlpi_phdr[i].p_type == PT_LOAD) {
                    uintptr_t seg_end = info->dlpi_addr +
                        info->dlpi_phdr[i].p_memsz;
                    if (seg_end > end) end = seg_end;
                }
            }
            if (end > info->dlpi_addr && g_code_range_count < kMaxCodeRanges) {
                int index = g_code_range_count;
                g_our_code_ranges[index] = {info->dlpi_addr, end};
                g_code_range_count = index + 1;
            }
        }
        return 0;
    }, nullptr);
    std::atomic_signal_fence(std::memory_order_release);
    g_ranges_initialized = 1;
}

// Async-signal-safe: linear scan, no STL, no heap allocations.
bool is_our_address(uintptr_t pc) {
    if (!g_ranges_initialized) return false;
    std::atomic_signal_fence(std::memory_order_acquire);
    for (int i = 0; i < g_code_range_count; i++) {
        if (pc >= g_our_code_ranges[i].start && pc < g_our_code_ranges[i].end) {
            return true;
        }
    }
    return false;
}

// ---- Per-step recovery state ----

static thread_local CrashRecoveryState* g_current_recovery = nullptr;

static volatile sig_atomic_t g_total_recoveries = 0;
static constexpr int kMaxRecoveriesPerBoot = 3;

CrashRecoveryState*& g_current_recovery_ptr() {
    return g_current_recovery;
}

// ---- Signal handler state ----
static struct sigaction g_old_sigsegv;
static struct sigaction g_old_sigbus;
static struct sigaction g_old_sigill;
static volatile sig_atomic_t g_signal_handlers_installed = 0;

// ---- Helper: extract PC from ucontext_t ----
static uintptr_t get_pc_from_ucontext(void* ctx) {
#if defined(__aarch64__)
    return static_cast<uintptr_t>(
        reinterpret_cast<ucontext_t*>(ctx)->uc_mcontext.pc);
#elif defined(__arm__)
    return static_cast<uintptr_t>(
        reinterpret_cast<ucontext_t*>(ctx)->uc_mcontext.arm_pc);
#elif defined(__x86_64__)
    return static_cast<uintptr_t>(
        reinterpret_cast<ucontext_t*>(ctx)->uc_mcontext.gregs[REG_RIP]);
#elif defined(__i386__)
    return static_cast<uintptr_t>(
        reinterpret_cast<ucontext_t*>(ctx)->uc_mcontext.gregs[REG_EIP]);
#else
    (void)ctx;
    return 0;
#endif
}

// ---- Signal handler (async-signal-safe) ----
static void crash_recovery_handler(int signo, siginfo_t* info, void* ctx) {
    (void)info;

    CrashRecoveryState* recovery = g_current_recovery;
    if (recovery == nullptr || !recovery->active) {
        goto chain;
    }

    {
        uintptr_t pc = get_pc_from_ucontext(ctx);
        if (pc == 0 || !is_our_address(pc)) {
            recovery->active = false;
            goto chain;
        }
    }

    recovery->recovery_count = recovery->recovery_count + 1;
    recovery->recovered = true;
    g_total_recoveries = g_total_recoveries + 1;

    siglongjmp(recovery->jmp_buf, signo);
    return;

chain:
    {
        struct sigaction* old = nullptr;
        if (signo == SIGSEGV) old = &g_old_sigsegv;
        else if (signo == SIGBUS) old = &g_old_sigbus;
        else if (signo == SIGILL) old = &g_old_sigill;
        if (old && old->sa_handler != SIG_DFL && old->sa_handler != SIG_IGN) {
            if (old->sa_flags & SA_SIGINFO) {
                old->sa_sigaction(signo, info, ctx);
            } else {
                old->sa_handler(signo);
            }
        } else {
            signal(signo, SIG_DFL);
            raise(signo);
        }
    }
}

// ---- Post-recovery file I/O ----
void crash_recovery_post_action(CrashRecoveryState* state) {
    if (state->step_id && state->step_id[0] != '\0') {
        char path[320];
        char disable_file[64];
        if (build_step_disable_filename(disable_file, sizeof(disable_file), state->step_id) &&
            get_persist_path(path, sizeof(path), disable_file)) {
            int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
            if (fd >= 0) {
                char buf[128];
                int blen = snprintf(buf, sizeof(buf),
                    "RECOVERY:%d:count=%d:total=%d\n",
                    state->recovery_count,
                    static_cast<int>(g_total_recoveries),
                    kMaxRecoveriesPerBoot);
                (void)!write(fd, buf, blen);
                close(fd);
            } else {
                LOGW("Crash recovery persist open failed: %s", path);
            }
        } else {
            LOGW("Crash recovery skipped persist write for step id: %s", state->step_id);
        }
    }

    mark_step_permanently_disabled(
        state->step_id ? state->step_id : "unknown",
        "CRASH_RECOVERY");

    if (g_total_recoveries > kMaxRecoveriesPerBoot) {
        write_terminal_despair_lock();
    }
}

// ---- Signal handler installation ----
bool install_crash_handlers() {
    if (g_signal_handlers_installed) return true;
    g_signal_handlers_installed = 1;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_recovery_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    sigaction(SIGSEGV, nullptr, &g_old_sigsegv);
    sigaction(SIGBUS, nullptr, &g_old_sigbus);
    sigaction(SIGILL, nullptr, &g_old_sigill);

    init_our_code_ranges();

    if (sigaction(SIGSEGV, &sa, nullptr) != 0) {
        LOGE("Failed to install SIGSEGV handler");
        g_signal_handlers_installed = 0;
        return false;
    }
    if (sigaction(SIGBUS, &sa, nullptr) != 0) {
        LOGE("Failed to install SIGBUS handler");
    }
    if (sigaction(SIGILL, &sa, nullptr) != 0) {
        LOGE("Failed to install SIGILL handler");
    }

    LOGI("Crash recovery handlers installed, %d code ranges",
         static_cast<int>(g_code_range_count));

    return true;
}
