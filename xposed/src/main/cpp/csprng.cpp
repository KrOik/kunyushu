/*
 * Copyright (C) 2025-2026 The KunYuShu Project
 *
 * Licensed under the Apache License, Version 2.0
 */

#include "csprng.h"

#include <chrono>

namespace {

// Mersenne Twister seeded from system entropy.
// Thread-safe: each call acquires a short-lived lock.
std::mt19937 g_mt;
std::mutex g_mt_mutex;
bool g_seeded = false;

} // namespace

void csprng_init() {
    std::lock_guard<std::mutex> lock(g_mt_mutex);
    if (g_seeded) return;

    // Seed from std::random_device (on Linux/Android → /dev/urandom).
    // Fallback: mix high-resolution clock + address entropy.
    try {
        std::random_device rd;
        // Seed with 8 × 32-bit entropy words (256 bits).
        std::seed_seq seq{
            rd(), rd(), rd(), rd(),
            rd(), rd(), rd(), rd(),
        };
        g_mt.seed(seq);
    } catch (...) {
        // random_device may fail in constrained environments.
        // Fallback: high-resolution clock ticks mixed with address ASLR entropy.
        auto now = std::chrono::high_resolution_clock::now()
                       .time_since_epoch()
                       .count();
        std::seed_seq seq{
            static_cast<unsigned>(now),
            static_cast<unsigned>(now >> 32),
            static_cast<unsigned>(reinterpret_cast<uintptr_t>(&g_mt)),
            static_cast<unsigned>(reinterpret_cast<uintptr_t>(&g_mt) >> 32),
        };
        g_mt.seed(seq);
    }
    g_seeded = true;
}

int csprng_int(int low, int high) {
    if (low > high) {
        int tmp = low;
        low = high;
        high = tmp;
    }
    std::lock_guard<std::mutex> lock(g_mt_mutex);
    std::uniform_int_distribution<int> dist(low, high);
    return dist(g_mt);
}

float csprng_float() {
    std::lock_guard<std::mutex> lock(g_mt_mutex);
    std::uniform_real_distribution<float> dist(0.0f, 1.0f);
    return dist(g_mt);
}

float csprng_noise(float amplitude) {
    std::lock_guard<std::mutex> lock(g_mt_mutex);
    std::uniform_real_distribution<float> dist(-amplitude, amplitude);
    return dist(g_mt);
}
