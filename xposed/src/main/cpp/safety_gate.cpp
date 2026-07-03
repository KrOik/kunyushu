/*
 * Copyright (C) 2025-2026 The KunYuShu Project
 * Ported to tv.kyshu with project-local persistence and relaxed device gates.
 *
 * Licensed under the Apache License, Version 2.0
 */

#include "safety_gate.h"

#include <sys/system_properties.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <cerrno>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>

#include "logging.h"

// ============================================================================
// QEMU / Emulator Detection
// ============================================================================
static bool detect_qemu() {
    // Check /proc/cpuinfo for virtualization signatures
    FILE* fp = fopen("/proc/cpuinfo", "r");
    if (fp) {
        char buf[256];
        while (fgets(buf, sizeof(buf), fp)) {
            if (strstr(buf, "QEMU") || strstr(buf, "KVM")) {
                fclose(fp);
                LOGE("QEMU detected via /proc/cpuinfo");
                return true;
            }
        }
        fclose(fp);
    }

    // Check for virtio devices (common in QEMU/KVM)
    if (access("/sys/bus/virtio/devices", F_OK) == 0) {
        LOGE("QEMU detected via /sys/bus/virtio/devices");
        return true;
    }

    // Check device tree model
    fp = fopen("/proc/device-tree/model", "r");
    if (fp) {
        char buf[128] = {};
        fgets(buf, sizeof(buf), fp);
        fclose(fp);
        if (strstr(buf, "QEMU") || strstr(buf, "qemu")) {
            LOGE("QEMU detected via /proc/device-tree/model");
            return true;
        }
    }

    return false;
}

// ============================================================================
// Fingerprint whitelist: known-allowed device builds
// ============================================================================
static bool is_fingerprint_whitelisted() {
    char fingerprint[PROP_VALUE_MAX] = {};
    __system_property_get("ro.build.fingerprint", fingerprint);
    if (fingerprint[0] == '\0') return false;

    // Known-allowed build patterns (partial match)
    // These are device/ROM combinations that the project supports.
    static const char* allowed_patterns[] = {
        // Xiaomi devices
        "Xiaomi/",
        "Redmi/",
        // OPPO devices  
        "OPPO/",
        "OnePlus/",
        // Samsung devices
        "samsung/",
        // vivo devices
        "vivo/",
        // Huawei devices
        "HUAWEI/",
        // Device-vendor whitelist: only known real-device patterns pass.
        // Emulator/AOSP builds are blocked.
        nullptr
    };

    for (int i = 0; allowed_patterns[i] != nullptr; i++) {
        if (strstr(fingerprint, allowed_patterns[i]) != nullptr) {
            LOGI("Device fingerprint whitelisted: %s (matched %s)", fingerprint, allowed_patterns[i]);
            return true;
        }
    }

    LOGE("Device fingerprint NOT whitelisted: %s", fingerprint);
    return false;
}

namespace {

static constexpr const char* kPersistDir = "/data/system/.svc-cache";
static constexpr const char* kStepDisablePrefix = ".disable_";

static bool g_logged_persist_primary_unavailable = false;
static bool g_logged_persist_fallback = false;
static bool g_logged_persist_read_fallback = false;
static bool g_logged_persist_unavailable = false;

bool is_safe_filename_char(char c) {
    return (c >= 'a' && c <= 'z') ||
           (c >= 'A' && c <= 'Z') ||
           (c >= '0' && c <= '9') ||
           c == '.' || c == '_' || c == '-';
}

bool is_safe_persist_filename(const char* filename) {
    if (filename == nullptr || filename[0] == '\0') return false;
    for (const char* p = filename; *p; ++p) {
        if (!is_safe_filename_char(*p)) return false;
    }
    return true;
}

bool build_path_from_dir(char* out, size_t out_size, const char* dir, const char* filename) {
    if (out == nullptr || out_size == 0) return false;
    out[0] = '\0';
    if (dir == nullptr || dir[0] == '\0' || filename == nullptr || filename[0] == '\0') {
        return false;
    }
    int n = snprintf(out, out_size, "%s/%s", dir, filename);
    if (n <= 0 || n >= static_cast<int>(out_size)) {
        out[0] = '\0';
        LOGE("PERSIST_PATH_TRUNCATED filename=%s", filename);
        return false;
    }
    return true;
}

bool build_system_persist_path(char* out, size_t out_size, const char* filename) {
    return build_path_from_dir(out, out_size, kPersistDir, filename);
}

bool build_cache_persist_path(char* out, size_t out_size, const char* filename) {
    return build_path_from_dir(out, out_size, g_app_cache_dir, filename);
}

void build_cache_path(char* out, size_t out_size, const char* suffix) {
    (void)build_path_from_dir(out, out_size, g_app_cache_dir, suffix);
}

void log_primary_unavailable(int err) {
    if (!g_logged_persist_primary_unavailable) {
        LOGW("SECURITY_PERSIST_PRIMARY_UNAVAILABLE dir=%s errno=%d", kPersistDir, err);
        g_logged_persist_primary_unavailable = true;
    }
}

bool ensure_system_persist_dir() {
    errno = 0;
    if (mkdir(kPersistDir, 0700) != 0 && errno != EEXIST) {
        log_primary_unavailable(errno);
        return false;
    }
    errno = 0;
    if (access(kPersistDir, W_OK | X_OK) != 0) {
        log_primary_unavailable(errno);
        return false;
    }
    return true;
}

bool get_existing_persist_path(char* buf, size_t size, const char* filename) {
    if (!is_safe_persist_filename(filename)) {
        LOGE("PERSIST_REJECT_UNSAFE_FILENAME filename=%s", filename ? filename : "null");
        return false;
    }
    if (build_system_persist_path(buf, size, filename) && access(buf, F_OK) == 0) {
        return true;
    }
    if (build_cache_persist_path(buf, size, filename) && access(buf, F_OK) == 0) {
        if (!g_logged_persist_read_fallback) {
            LOGW("SECURITY_PERSIST_READ_FALLBACK file=%s", filename);
            g_logged_persist_read_fallback = true;
        }
        return true;
    }
    if (buf != nullptr && size > 0) buf[0] = '\0';
    return false;
}

void log_write_failure(const char* op, const char* path, int err) {
    LOGW("SECURITY_PERSIST_WRITE_FAILED op=%s errno=%d path=%s",
         op ? op : "unknown", err, path ? path : "null");
}

void commit_tmp_file(const char* tmp, const char* path, const char* label) {
    errno = 0;
    if (rename(tmp, path) != 0) {
        log_write_failure(label, path, errno);
        unlink(tmp);
    }
}
} // namespace

// Primary persistent storage: system-protected dir.
// survives app cache clears. Falls back to g_app_cache_dir if unavailable.
bool get_persist_path(char* buf, size_t size, const char* filename) {
    if (!is_safe_persist_filename(filename)) {
        LOGE("PERSIST_REJECT_UNSAFE_FILENAME filename=%s", filename ? filename : "null");
        return false;
    }
    if (ensure_system_persist_dir() && build_system_persist_path(buf, size, filename)) {
        return true;
    }
    if (build_cache_persist_path(buf, size, filename)) {
        if (!g_logged_persist_fallback) {
            LOGW("SECURITY_PERSIST_FALLBACK dir=%s", g_app_cache_dir);
            g_logged_persist_fallback = true;
        }
        return true;
    }
    if (!g_logged_persist_unavailable) {
        LOGE("SECURITY_PERSIST_UNAVAILABLE filename=%s", filename);
        g_logged_persist_unavailable = true;
    }
    return false;
}

bool build_step_disable_filename(char* buf, size_t size, const char* step_id) {
    if (buf == nullptr || size == 0 || step_id == nullptr || step_id[0] == '\0') {
        return false;
    }
    const size_t prefix_len = strlen(kStepDisablePrefix);
    if (size <= prefix_len + 1) {
        buf[0] = '\0';
        return false;
    }
    memcpy(buf, kStepDisablePrefix, prefix_len);
    size_t pos = prefix_len;
    bool truncated = false;
    for (const char* p = step_id; *p; ++p) {
        if (pos + 1 >= size) {
            truncated = true;
            break;
        }
        buf[pos++] = is_safe_filename_char(*p) ? *p : '_';
    }
    buf[pos] = '\0';
    if (pos == prefix_len) return false;
    if (truncated) {
        LOGW("PERSIST_STEP_ID_TRUNCATED step=%s", step_id);
    }
    return true;
}
char g_app_cache_dir[256] = {};


void set_app_cache_dir(const char* cache_dir) {
    if (cache_dir == nullptr) {
        g_app_cache_dir[0] = '\0';
        return;
    }
    strncpy(g_app_cache_dir, cache_dir, sizeof(g_app_cache_dir) - 1);
    g_app_cache_dir[sizeof(g_app_cache_dir) - 1] = '\0';
}

const char* get_app_cache_dir() {
    return g_app_cache_dir;
}

bool is_device_whitelisted_strict() {
    // Honeypot countermeasure: refuse to load hooks in emulators
    if (detect_qemu()) {
        LOGE("QEMU/emulator detected — hooks disabled");
        return false;
    }
    // Strict fingerprint check — only allowed device builds
    if (!is_fingerprint_whitelisted()) {
        return false;
    }
    return true;
}

bool is_device_allowed() {
#ifdef KUNYUSHU_DEBUG
    return true;
#else
    return is_device_whitelisted_strict();
#endif
}

bool is_step_permanently_disabled(const char* step_id) {
    if (step_id == nullptr) return false;
    char path[320];
    char filename[64];
    if (!build_step_disable_filename(filename, sizeof(filename), step_id)) return false;
    if (!get_existing_persist_path(path, sizeof(path), filename)) return false;
    if (access(path, F_OK) != 0) return false;
    FILE* check = fopen(path, "r");
    if (!check) return false;
    fseek(check, 0, SEEK_END);
    long sz = ftell(check);
    fclose(check);
    return sz > 0;
}

void mark_step_permanently_disabled(const char* step_id, const char* reason) {
    if (step_id == nullptr) return;
    char path[320];
    char tmp[340];
    char filename[64];
    if (!build_step_disable_filename(filename, sizeof(filename), step_id)) return;
    if (!get_persist_path(path, sizeof(path), filename)) return;
    snprintf(tmp, sizeof(tmp), "%s.tmp.%d", path, getpid());
    FILE* f = fopen(tmp, "w");
    if (f) {
        char boot_id[64] = {};
        FILE* bid = fopen("/proc/sys/kernel/random/boot_id", "r");
        if (bid) {
            fgets(boot_id, sizeof(boot_id), bid);
            fclose(bid);
        }
        fprintf(f, "%s %lld %s\n", boot_id,
                static_cast<long long>(time(nullptr)),
                reason ? reason : "unknown");
        fflush(f);
        fclose(f);
        commit_tmp_file(tmp, path, "mark_step_permanently_disabled");
    } else {
        log_write_failure("mark_step_permanently_disabled", tmp, errno);
    }
}

bool is_art_version_locked() {
    char path[320];
    if (get_existing_persist_path(path, sizeof(path), ".art_lock")) return true;
    if (!get_persist_path(path, sizeof(path), ".art_lock")) return true;
    return false;
}

void write_art_version_lock() {
    char fingerprint[PROP_VALUE_MAX] = {};
    __system_property_get("ro.build.fingerprint", fingerprint);
    if (fingerprint[0] == '\0') return;
    for (char* p = fingerprint; *p; p++) {
        char c = *p;
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '_' || c == '.' ||
              c == '-' || c == '/' || c == ':')) {
            *p = '_';
        }
    }
    char path[320];
    char tmp[340];
    if (!get_persist_path(path, sizeof(path), ".art_lock")) return;
    snprintf(tmp, sizeof(tmp), "%s.tmp.%d", path, getpid());
    FILE* f = fopen(tmp, "w");
    if (f) {
        char boot_id[64] = {};
        FILE* bid = fopen("/proc/sys/kernel/random/boot_id", "r");
        if (bid) {
            fgets(boot_id, sizeof(boot_id), bid);
            fclose(bid);
        }
        fprintf(f, "%s\n%s\n", boot_id, fingerprint);
        fflush(f);
        fclose(f);
        commit_tmp_file(tmp, path, "write_art_version_lock");
    } else {
        log_write_failure("write_art_version_lock", tmp, errno);
    }
}

bool is_terminal_despair_locked() {
    char path[320];
    return get_existing_persist_path(path, sizeof(path), ".despair");
}

void write_terminal_despair_lock() {
    char path[320];
    char tmp[340];
    if (!get_persist_path(path, sizeof(path), ".despair")) return;
    snprintf(tmp, sizeof(tmp), "%s.tmp.%d", path, getpid());
    FILE* f = fopen(tmp, "w");
    if (f) {
        char boot_id[64] = {};
        FILE* bid = fopen("/proc/sys/kernel/random/boot_id", "r");
        if (bid) {
            fgets(boot_id, sizeof(boot_id), bid);
            fclose(bid);
        }
        fprintf(f, "%s %lld DESPAIR_LOCK\n", boot_id,
                static_cast<long long>(time(nullptr)));
        fflush(f);
        fclose(f);
        commit_tmp_file(tmp, path, "write_terminal_despair_lock");
    } else {
        log_write_failure("write_terminal_despair_lock", tmp, errno);
    }
}

int read_crash_counter() {
    char path[320];
    if (!get_existing_persist_path(path, sizeof(path), ".crash_count")) return 0;
    FILE* f = fopen(path, "r");
    if (!f) return 0;
    int count = 0;
    fscanf(f, "%d", &count);
    fclose(f);
    return (count > 0) ? count : 0;
}

void write_crash_counter(int count) {
    char path[320];
    char tmp[340];
    if (!get_persist_path(path, sizeof(path), ".crash_count")) return;
    snprintf(tmp, sizeof(tmp), "%s.tmp.%d", path, getpid());
    FILE* f = fopen(tmp, "w");
    if (f) {
        char boot_id[64] = {};
        FILE* bid = fopen("/proc/sys/kernel/random/boot_id", "r");
        if (bid) {
            fgets(boot_id, sizeof(boot_id), bid);
            fclose(bid);
        }
        fprintf(f, "%d\n%s\n", count, boot_id);
        fflush(f);
        fclose(f);
        commit_tmp_file(tmp, path, "write_crash_counter");
    } else {
        log_write_failure("write_crash_counter", tmp, errno);
    }
}

bool check_crash_meltdown() {
    int count = read_crash_counter();
    if (count >= kCrashMeltdownThreshold) {
        write_terminal_despair_lock();
        return false;
    }
    return true;
}

void record_crash_recovery() {
    int count = read_crash_counter();
    write_crash_counter(count + 1);
}

void reset_crash_counter() {
    write_crash_counter(0);
}

void log_skip(const char* step_id, const char* gate_failed) {
    if (g_app_cache_dir[0] == '\0') return;
    char path[320];
    build_cache_path(path, sizeof(path), ".skip.log");
    FILE* f = fopen(path, "a");
    if (f) {
        fprintf(f, "%lld SKIP:%s:%s\n",
                static_cast<long long>(time(nullptr)),
                step_id ? step_id : "unknown",
                gate_failed ? gate_failed : "unknown");
        fclose(f);
    }
}

bool is_safe_mode() {
    char safemode[PROP_VALUE_MAX] = {};
    __system_property_get("persist.sys.safemode", safemode);
    if (safemode[0] == '1') return true;
    __system_property_get("ro.boot.safemode", safemode);
    return safemode[0] == '1';
}
