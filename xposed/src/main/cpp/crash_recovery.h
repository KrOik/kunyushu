/*
 * Copyright (C) 2025-2026 The KunYuShu Project
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

#ifndef _CRASH_RECOVERY_H
#define _CRASH_RECOVERY_H

#include <signal.h>
#include <setjmp.h>
#include <cstdint>

// ==========================================================================
// Layer 2: Process-level crash recovery via SIGSEGV/SIGBUS/SIGILL handler.
//
// Architecture:
//   crash_recovery.h — public API (declarations + macros)
//   crash_recovery.cpp — all global state, signal handler, and implementation
//
// All state lives in a single translation unit. Every includer of this header
// shares the SAME handler, code ranges, and per-step recovery state.
//
// Mechanism:
//   1. At hook init, install_crash_handlers() discovers our .so's loaded
//      address range via dl_iterate_phdr and installs signal handlers.
//   2. Before each risky hook body, call CRASH_RECOVERY_BEGIN(id).
//      This sets a sigsetjmp recovery point.
//   3. If the hook body causes SIGSEGV/SIGBUS/SIGILL within our code:
//      - The signal handler siglongjmps back to the recovery point
//      - crash_recovery_post_action() writes disable markers + despair lock
//      - A safe fallback path is taken (passthrough / syscall)
//   4. Crashes OUTSIDE our code range are NOT intercepted — they pass
//      through to the original handler (typically crash + tombstone).
//
// Safety properties (N5 fix):
//   - Signal handler is TRULY async-signal-safe: only volatile flags,
//     frozen read-only memory, and siglongjmp. No file I/O, no snprintf,
//     no std::atomic.
//   - Fault location checked via ucontext_t PC (instruction pointer), NOT
//     si_addr (data access address).
//   - All file I/O deferred to crash_recovery_post_action() in normal context.
//   - After 3 recoveries on same boot → terminal despair lock.
// ==========================================================================

// ---- Code range tracking ----
struct CodeRange {
    uintptr_t start;
    uintptr_t end;
};

void init_our_code_ranges();
bool is_our_address(uintptr_t pc);

// ---- Per-step recovery state ----
struct CrashRecoveryState {
    sigjmp_buf jmp_buf;
    const char* step_id;
    volatile sig_atomic_t active{false};
    volatile sig_atomic_t recovered{false};
    volatile int recovery_count{0};
};

// ---- Installation ----
bool install_crash_handlers();

// ---- Post-recovery action (call AFTER siglongjmp) ----
void crash_recovery_post_action(CrashRecoveryState* state);

// ---- Recovery macros ----

#define CRASH_RECOVERY_DECL(state_var) \
    static thread_local CrashRecoveryState state_var

#define CRASH_RECOVERY_BEGIN(state, step_id_str) \
    do { \
        (state).step_id = (step_id_str); \
        (state).recovered = false; \
        (state).active = true; \
        g_current_recovery_ptr() = &(state); \
        int _cr_sig = sigsetjmp((state).jmp_buf, 1); \
        if (_cr_sig == 0) {

#define CRASH_RECOVERY_END(state) \
        } else { \
            crash_recovery_post_action(&(state)); \
        } \
        (state).active = false; \
        g_current_recovery_ptr() = nullptr; \
    } while(0)

CrashRecoveryState*& g_current_recovery_ptr();

#endif // _CRASH_RECOVERY_H
