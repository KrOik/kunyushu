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

#ifndef PORTAL_PROC_MAPS_FILTER_H
#define PORTAL_PROC_MAPS_FILTER_H

#include <cstdbool>

#ifdef __cplusplus
extern "C" {
#endif

// Install Dobby hooks on open/openat/readlink to filter /proc/self/maps,
// /proc/self/smaps, and /proc/self/status content. Removes lines containing
// "xposed", "lsposed", "libportal", and related markers. Also rewrites
// TracerPid to 0 in status files.
bool portal_install_proc_maps_hooks(void);

// For TV.KYSHU JNI bridge: maps to Java_tv_kyshu_dobby_Dobby_installProcMapsHooks
bool portal_install_proc_maps_hooks_jni(void);

#ifdef __cplusplus
}
#endif

#endif // PORTAL_PROC_MAPS_FILTER_H
