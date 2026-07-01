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

// ==========================================================================
// /proc/maps filtering for all Xposed-injected processes.
//
// Installed from the xposed module via Dobby.invoke. Intercepts
// open/openat calls to /proc/self/{maps,smaps,status}, reads the
// original content, removes Xposed/LSPosed marker lines and portal
// library entries, and returns a clean memfd instead.
//
// Ported from VoxLoction's proc_maps_filter.cpp for tv.kyshu.
// ==========================================================================

#include "proc_maps_filter.h"
#include "maps_filter.h"
#include "logging.h"
#include "safety_gate.h"
#include "crash_recovery.h"
#include "dobby_hook.h"

#include <cstdio>
#include <jni.h>
#include <cstring>
#include <cstdarg>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <atomic>
#include <mutex>
// TODO: Add a close() hook to remove tracked FDs from g_memfd_set when
// they are closed, preventing fd exhaustion from leaked memfd descriptors.
// Hook '__NR_close' via Dobby and call g_memfd_set.erase(fd) before
// invoking the original close.
#include <unordered_set>

// ============================================================================
// Original function pointers
// ============================================================================
static int (*orig_open)(const char*, int, mode_t) = nullptr;
static int (*orig_openat)(int, const char*, int, ...) = nullptr;
static ssize_t (*orig_readlink)(const char*, char*, size_t) = nullptr;
static ssize_t (*orig_readlinkat)(int, const char*, char*, size_t) = nullptr;

// ============================================================================
// Syscall fallbacks (bypass our own hooks)
// ============================================================================
static int call_unhooked_open(const char* pathname, int flags, mode_t mode) {
    return static_cast<int>(syscall(__NR_openat, AT_FDCWD, pathname, flags, mode));
}

static int call_unhooked_openat(int dirfd, const char* pathname, int flags, mode_t mode) {
    return static_cast<int>(syscall(__NR_openat, dirfd, pathname, flags, mode));
}

// ============================================================================
// Memfd tracking: remember our filtered memfd FDs so readlink/readlinkat
// hooks can return a virtual path instead of the anonymous memfd name.
// ============================================================================
static std::unordered_set<int> g_memfd_set;
static std::mutex g_memfd_mutex;

static bool parse_proc_self_fd(const char* path, int& out_fd) {
    if (!path) return false;
    static const char prefix[] = "/proc/self/fd/";
    if (strncmp(path, prefix, sizeof(prefix) - 1) != 0) return false;
    const char* num_str = path + sizeof(prefix) - 1;
    char* end = nullptr;
    long fd = strtol(num_str, &end, 10);
    if (end == num_str || *end != '\0' || fd < 0) return false;
    out_fd = static_cast<int>(fd);
    return true;
}

// ============================================================================
// Hook: new_open
// ============================================================================
static int new_open(const char* pathname, int flags, mode_t mode) {
    if (try_filter_proc_open(pathname, flags)) {
        int real_fd = orig_open
            ? orig_open(pathname, O_RDONLY, 0)
            : call_unhooked_open(pathname, O_RDONLY, 0);
        if (real_fd < 0) return real_fd;

        int memfd = filter_proc_file(real_fd);
        close(real_fd);
        if (memfd >= 0) {
            std::lock_guard<std::mutex> lock(g_memfd_mutex);
            g_memfd_set.insert(memfd);
            return memfd;
        }
        return orig_open
            ? orig_open(pathname, flags, mode)
            : call_unhooked_open(pathname, flags, mode);
    }

    if (orig_open == nullptr) {
        return call_unhooked_open(pathname, flags, mode);
    }
    return orig_open(pathname, flags, mode);
}

// ============================================================================
// Hook: new_openat
// ============================================================================
static int new_openat(int dirfd, const char* pathname, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = 0;
    if (flags & O_CREAT) mode = va_arg(args, mode_t);
    va_end(args);

    if (try_filter_proc_open(pathname, flags)) {
        int real_fd = orig_openat
            ? orig_openat(dirfd, pathname, O_RDONLY)
            : call_unhooked_openat(dirfd, pathname, O_RDONLY, 0);
        if (real_fd < 0) return real_fd;

        int memfd = filter_proc_file(real_fd);
        close(real_fd);
        if (memfd >= 0) {
            std::lock_guard<std::mutex> lock(g_memfd_mutex);
            g_memfd_set.insert(memfd);
            return memfd;
        }
        return orig_openat
            ? orig_openat(dirfd, pathname, flags, mode)
            : call_unhooked_openat(dirfd, pathname, flags, mode);
    }

    if (orig_openat == nullptr) {
        return call_unhooked_openat(dirfd, pathname, flags, mode);
    }
    return orig_openat(dirfd, pathname, flags, mode);
}

// ============================================================================
// Hook: new_readlink — hide filtered memfd paths
// ============================================================================
static ssize_t new_readlink(const char* path, char* buf, size_t bufsiz) {
    int fd = -1;
    if (parse_proc_self_fd(path, fd)) {
        std::lock_guard<std::mutex> lock(g_memfd_mutex);
        if (g_memfd_set.count(fd)) {
            static const char virtual_path[] = "/memfd:maps (deleted)";
            size_t len = sizeof(virtual_path) - 1;
            if (buf && bufsiz > 0) {
                size_t copy_len = (len < bufsiz) ? len : (bufsiz - 1);
                memcpy(buf, virtual_path, copy_len);
                buf[copy_len] = '\0';
                return static_cast<ssize_t>(copy_len);
            }
            return static_cast<ssize_t>(len);
        }
    }
    if (orig_readlink) return orig_readlink(path, buf, bufsiz);
    return syscall(__NR_readlinkat, AT_FDCWD, path, buf, bufsiz);
}

// ============================================================================
// Hook: new_readlinkat — hide filtered memfd paths
// ============================================================================
static ssize_t new_readlinkat(int dirfd, const char* path, char* buf, size_t bufsiz) {
    int fd = -1;
    if (path && (dirfd == AT_FDCWD || dirfd == -100) && parse_proc_self_fd(path, fd)) {
        std::lock_guard<std::mutex> lock(g_memfd_mutex);
        if (g_memfd_set.count(fd)) {
            static const char virtual_path[] = "/memfd:maps (deleted)";
            size_t len = sizeof(virtual_path) - 1;
            if (buf && bufsiz > 0) {
                size_t copy_len = (len < bufsiz) ? len : (bufsiz - 1);
                memcpy(buf, virtual_path, copy_len);
                buf[copy_len] = '\0';
                return static_cast<ssize_t>(copy_len);
            }
            return static_cast<ssize_t>(len);
        }
    }
    if (orig_readlinkat) return orig_readlinkat(dirfd, path, buf, bufsiz);
    return syscall(__NR_readlinkat, dirfd, path, buf, bufsiz);
}

// ============================================================================
// Crash recovery state for hook installation
// ============================================================================
CRASH_RECOVERY_DECL(g_maps_open_recovery);
CRASH_RECOVERY_DECL(g_maps_openat_recovery);

// ============================================================================
// install_proc_maps_hooks — install all G2 hooks safely
// ============================================================================
bool portal_install_proc_maps_hooks_jni(void) {
    static std::atomic_bool installed{false};
    if (installed.exchange(true)) return true;

    bool ok = true;
    safe_execute_failfast("G2_MAPS", [&ok]() -> bool {
        void* addr = dlsym(RTLD_DEFAULT, "open");
        if (addr) {
            CRASH_RECOVERY_BEGIN(g_maps_open_recovery, "G2_OPEN") {
                if (DobbyHook(addr, (void*)new_open, (void**)&orig_open) != 0) {
                    orig_open = nullptr;
                    ok = false;
                    LOGD("G2_MAPS: DobbyHook open failed");
                }
            }
            CRASH_RECOVERY_END(g_maps_open_recovery);
        }

        addr = dlsym(RTLD_DEFAULT, "openat");
        if (addr) {
            CRASH_RECOVERY_BEGIN(g_maps_openat_recovery, "G2_OPENAT") {
                if (DobbyHook(addr, (void*)new_openat, (void**)&orig_openat) != 0) {
                    orig_openat = nullptr;
                    ok = false;
                    LOGD("G2_MAPS: DobbyHook openat failed");
                }
            }
            CRASH_RECOVERY_END(g_maps_openat_recovery);
        }

        addr = dlsym(RTLD_DEFAULT, "readlink");
        if (addr) {
            if (DobbyHook(addr, (void*)new_readlink, (void**)&orig_readlink) != 0) {
                orig_readlink = nullptr;
                ok = false;
                LOGD("G2_MAPS: DobbyHook readlink failed");
            }
        }

        addr = dlsym(RTLD_DEFAULT, "readlinkat");
        if (addr) {
            if (DobbyHook(addr, (void*)new_readlinkat, (void**)&orig_readlinkat) != 0) {
                orig_readlinkat = nullptr;
                ok = false;
                LOGD("G2_MAPS: DobbyHook readlinkat failed");
            }
        }

        return ok;
    });

    if (ok) {
    LOGI("G2_MAPS: hooks installed successfully");
    } else {
    LOGI("G2_MAPS: hooks partially installed");
    }
    return ok;
}

// ============================================================================
// JNI Export
// ============================================================================
extern "C" JNIEXPORT jboolean JNICALL
Java_tv_kyshu_dobby_Dobby_installProcMapsHooks(JNIEnv*, jobject) {
    return portal_install_proc_maps_hooks_jni() ? JNI_TRUE : JNI_FALSE;
}
