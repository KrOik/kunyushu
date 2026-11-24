/*
 * Copyright (C) 2025-2026 The KunYuShu Project
 *
 * Licensed under the Apache License, Version 2.0
 *
 * Thread-safe CSPRNG replacement for srand(time())/rand().
 *
 * DuckDetector risk model R7: srand(time(nullptr)) and rand() produce
 * low-entropy, statistically fingerprintable noise. This module provides
 * a per-session seeded, thread-safe PRNG backed by the system entropy
 * source (std::random_device → /dev/urandom).
 *
 * Usage:
 *   csprng_init();                 // once per process (JNI_OnLoad)
 *   int dice = csprng_int(1, 6);   // inclusive range
 *   float f = csprng_float();      // [0.0f, 1.0f)
 *   float n = csprng_noise(0.4f);  // [-0.4f, 0.4f]
 */

#ifndef _CSPRNG_H
#define _CSPRNG_H

#include <random>
#include <mutex>

// Seed the global CSPRNG. Call once from JNI_OnLoad after safety gate.
void csprng_init();

// Uniform integer in [low, high] (inclusive).
int csprng_int(int low, int high);

// Uniform float in [0.0f, 1.0f).
float csprng_float();

// Uniform float in [-amplitude, +amplitude].
float csprng_noise(float amplitude);

#endif // _CSPRNG_H
