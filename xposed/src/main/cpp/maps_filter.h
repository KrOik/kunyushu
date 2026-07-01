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

#ifndef PORTAL_MAPS_FILTER_H
#define PORTAL_MAPS_FILTER_H

#include <cstdio>

#ifdef __cplusplus
extern "C" {
#endif

bool should_filter_proc_path(const char* pathname);
bool should_remove_maps_line(const char* line);
bool rewrite_tracer_pid_line(char* line, size_t max_len);
bool try_filter_proc_open(const char* pathname, int flags);
int filter_proc_file(int real_fd);

#ifdef __cplusplus
}
#endif

#endif
