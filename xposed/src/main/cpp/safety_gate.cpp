/*
 * Copyright (C) 2025-2026 The KunYuShu Project
 * Ported to Portal (tv.kyshu) with .portal_ prefix and relaxed device gates.
 *
 * Licensed under the Apache License, Version 2.0
 */

#include "safety_gate.h"

#include <sys/system_properties.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <unistd.h>

namespace {


void build_cache_path(char* out, size_t out_size, const char* suffix) {
    if (out_size == 0) return;
    if (g_app_cache_dir[0] == '\0') {
        out[0] = '\0';
        return;
    }
    snprintf(out, out_size, "%s/%s", g_app_cache_dir, suffix);
}

} // namespace
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
    // Portal: always allow all devices (no fingerprint whitelist)
    return true;
}

bool is_device_allowed() {
    return true;
}

bool is_step_permanently_disabled(const char* step_id) {
    if (g_app_cache_dir[0] == '\0' || step_id == nullptr) return false;
    char path[320];
    snprintf(path, sizeof(path), "%s/.portal_disable_%s", g_app_cache_dir, step_id);
    if (access(path, F_OK) != 0) return false;
    FILE* check = fopen(path, "r");
    if (!check) return false;
    fseek(check, 0, SEEK_END);
    long sz = ftell(check);
    fclose(check);
    return sz > 0;
}

void mark_step_permanently_disabled(const char* step_id, const char* reason) {
    if (g_app_cache_dir[0] == '\0' || step_id == nullptr) return;
    char path[320];
    char tmp[340];
    snprintf(path, sizeof(path), "%s/.portal_disable_%s", g_app_cache_dir, step_id);
    snprintf(tmp, sizeof(tmp), "%s/.portal_disable_%s.tmp.%d",
             g_app_cache_dir, step_id, getpid());
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
        rename(tmp, path);
    }
}

bool is_art_version_locked() {
    if (g_app_cache_dir[0] == '\0') return true;
    char path[320];
    build_cache_path(path, sizeof(path), ".portal_art_lock");
    return access(path, F_OK) == 0;
}

void write_art_version_lock() {
    if (g_app_cache_dir[0] == '\0') return;
    char path[320];
    char tmp[340];
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
    build_cache_path(path, sizeof(path), ".portal_art_lock");
    snprintf(tmp, sizeof(tmp), "%s/.portal_art_lock.tmp.%d", g_app_cache_dir, getpid());
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
        rename(tmp, path);
    }
}

bool is_terminal_despair_locked() {
    if (g_app_cache_dir[0] == '\0') return false;
    char path[320];
    build_cache_path(path, sizeof(path), ".portal_despair");
    return access(path, F_OK) == 0;
}

void write_terminal_despair_lock() {
    if (g_app_cache_dir[0] == '\0') return;
    char path[320];
    char tmp[340];
    build_cache_path(path, sizeof(path), ".portal_despair");
    snprintf(tmp, sizeof(tmp), "%s/.portal_despair.tmp.%d", g_app_cache_dir, getpid());
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
        rename(tmp, path);
    }
}

int read_crash_counter() {
    if (g_app_cache_dir[0] == '\0') return 0;
    char path[320];
    build_cache_path(path, sizeof(path), ".portal_crash_count");
    FILE* f = fopen(path, "r");
    if (!f) return 0;
    int count = 0;
    fscanf(f, "%d", &count);
    fclose(f);
    return (count > 0) ? count : 0;
}

void write_crash_counter(int count) {
    if (g_app_cache_dir[0] == '\0') return;
    char path[320];
    char tmp[340];
    build_cache_path(path, sizeof(path), ".portal_crash_count");
    snprintf(tmp, sizeof(tmp), "%s/.portal_crash_count.tmp.%d", g_app_cache_dir, getpid());
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
        rename(tmp, path);
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
    build_cache_path(path, sizeof(path), ".portal_skip.log");
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
