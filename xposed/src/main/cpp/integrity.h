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

#ifndef PORTAL_INTEGRITY_H
#define PORTAL_INTEGRITY_H

#include <jni.h>
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

// FNV-1a 64-bit hash over a string's UTF-8 bytes.
uint64_t portal_fnv1a(const char* str, uint64_t seed);

// Avalanche mixing step (from GhostMapX NativeGuard pattern).
// Takes a 64-bit value through multiple rounds of multiplication
// and XOR to produce a non-linear hash chain output.
uint64_t portal_hash_mix(uint64_t val, uint64_t salt);

// Compute hash chain: f(version, f(pkgName2, f(pkgName, seed))) → 4-round mix.
// Used to verify the caller (app module) hasn't been tampered with.
uint64_t portal_compute_integrity(
    const char* pkgName,
    const char* pkgName2,
    const char* version,
    int versionCode);

#ifdef __cplusplus
}
#endif

#endif // PORTAL_INTEGRITY_H
