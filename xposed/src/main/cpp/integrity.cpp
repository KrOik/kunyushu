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
// FNV-1a hash chain integrity verification.
//
// GhostMapX's NativeGuard uses a hash chain pattern:
//   d(str, str2, i, z, i2, i3, j) = e(e(e(...), j), ...)
// where f(str, seed) is FNV-1a over the string's bytes
// and e(j1, j2) is an 8-byte-step avalanche mix.
//
// This implementation follows the same pattern with package/version
// specific parameters for the Portal control app.
// ==========================================================================

#include "integrity.h"
#include "logging.h"
#include "safety_gate.h"
#include <cstring>

// ============================================================================
// Expected hash value — compiled-in integrity fingerprint.
// Compute this from trusted package + version combination.
// ============================================================================
static const uint64_t kExpectedHash = 0xEF7E395CE15CA27AULL;
static const uint64_t kHashSeed = 0x811C9DC5ULL;  // FNV-1a initial seed

// ============================================================================
// FNV-1a 64-bit hash
// ============================================================================
uint64_t portal_fnv1a(const char* str, uint64_t seed) {
    if (str == nullptr) return seed;
    uint64_t hash = seed;
    for (const uint8_t* p = reinterpret_cast<const uint8_t*>(str); *p; ++p) {
        hash ^= static_cast<uint64_t>(*p);
        hash *= 0x100000001B3ULL;  // FNV-1a prime
    }
    return hash;
}

// ============================================================================
// Avalanche mixing — 8-byte step with 4 rounds
// ============================================================================
uint64_t portal_hash_mix(uint64_t val, uint64_t salt) {
    // Round 1: multiply by salt, XOR high bits
    uint64_t x = val * salt;
    x ^= (x >> 33);
    x *= 0xFF51AFD7ED558CCDULL;
    x ^= (x >> 33);

    // Round 2: byte-rotate and remix
    x = ((x << 17) | (x >> 47));
    x *= 0xC4CEB9FE1A85EC53ULL;
    x ^= (x >> 33);

    // Round 3: add salt, re-multiply  
    x += salt;
    x *= 0x94D049BB133111EBULL;
    x ^= (x >> 31);

    // Round 4: final diffusion
    x *= 0xBF58476D1CE4E5B9ULL;
    x ^= (x >> 31);

    // Final mixer constant (GhostMapX uses 7872180487048247529L)
    x *= 0x6D4A8F2E1B9C3D5EULL;

    return x;
}

// ============================================================================
// Hash chain: f(version, f(pkgName2, f(pkgName, seed)))
// ============================================================================
uint64_t portal_compute_integrity(
    const char* pkgName,
    const char* pkgName2,
    const char* version,
    int versionCode)
{
    // Step 1: hash package name with seed
    uint64_t h1 = portal_fnv1a(pkgName, kHashSeed);

    // Step 2: fold in second package name
    uint64_t h2 = portal_fnv1a(pkgName2, h1);

    // Step 3: fold in version string
    uint64_t h3 = portal_fnv1a(version, h2);

    // Step 4: mix in version code as salt
    uint64_t h4 = portal_hash_mix(h3, static_cast<uint64_t>(versionCode));

    return h4;
}

// ============================================================================
// JNI Export: verify integrity from Java side
// Returns JNI_TRUE if the computed hash matches the expected value.
// ============================================================================
extern "C" JNIEXPORT jboolean JNICALL
Java_tv_kyshu_dobby_Dobby_kitIntegrityCheck(
    JNIEnv* env, jobject /*thiz*/,
    jstring pkgName, jstring version, jint versionCode)
{
    if (pkgName == nullptr || version == nullptr) return JNI_FALSE;
    const char* pkg = env->GetStringUTFChars(pkgName, nullptr);
    const char* ver = env->GetStringUTFChars(version, nullptr);
    if (pkg == nullptr || ver == nullptr) {
        if (pkg) env->ReleaseStringUTFChars(pkgName, pkg);
        if (ver) env->ReleaseStringUTFChars(version, ver);
        return JNI_FALSE;
    }
    uint64_t hash = portal_compute_integrity(pkg, pkg, ver, versionCode);
    env->ReleaseStringUTFChars(pkgName, pkg);
    env->ReleaseStringUTFChars(version, ver);

    bool match = (hash == kExpectedHash);
    if (!match) {
        LOGE("kitIntegrityCheck FAILED: computed=0x%016llX, expected=0x%016llX",
             (unsigned long long)hash, (unsigned long long)kExpectedHash);
        write_terminal_despair_lock();
    } else {
        LOGI("kitIntegrityCheck PASSED");
    }

    return match ? JNI_TRUE : JNI_FALSE;
}
