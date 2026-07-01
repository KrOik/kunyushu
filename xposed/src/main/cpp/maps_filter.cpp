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

#include "maps_filter.h"
#include "logging.h"

#include <cstring>
#include <linux/memfd.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <fcntl.h>
#include <sstream>
#include <string>
#include <cerrno>

// Filter markers: lines in /proc/self/maps containing any of these
// substrings are removed before the content is returned to the caller.
static const char* kFilterMarkers[] = {
    "XposedBridge",
    "lsposed",
    "lspd",
    "edxposed",
    "de.robv.android.xposed",
    "org.lsposed",
    "libportal",
    "libkys",
    "tv.kyshu",
    nullptr
};

bool should_filter_proc_path(const char* pathname) {
    if (pathname == nullptr) return false;
    if (strncmp(pathname, "/proc/", 6) != 0) return false;
    return strstr(pathname, "/maps") != nullptr ||
           strstr(pathname, "/smaps") != nullptr ||
           strstr(pathname, "/status") != nullptr;
}

bool should_remove_maps_line(const char* line) {
    if (line == nullptr) return false;
    for (int i = 0; kFilterMarkers[i] != nullptr; i++) {
        if (strstr(line, kFilterMarkers[i]) != nullptr) {
            return true;
        }
    }
    return false;
}

bool rewrite_tracer_pid_line(char* line, size_t max_len) {
    if (line == nullptr) return false;
    int tracer_pid = 0;
    char prefix[32] = {};
    if (sscanf(line, "%31s %d", prefix, &tracer_pid) == 2) {
        if (strstr(prefix, "TracerPid") != nullptr) {
            snprintf(line, max_len, "TracerPid:\t0\n");
            return true;
        }
    }
    return false;
}

bool try_filter_proc_open(const char* pathname, int flags) {
    if (!should_filter_proc_path(pathname)) return false;
    if ((flags & O_ACCMODE) != O_RDONLY) return false;
    if (flags & O_DIRECTORY) return false;
    return true;
}

int filter_proc_file(int real_fd) {
    // Read entire file content into memory.
    // /proc/self/maps is generated on-the-fly — between read() calls,
    // mappings may change. A 64KB buffer minimizes the TOCTOU window.
    std::string content;
    content.reserve(65536);
    char buf[65536];
    ssize_t n;
    while ((n = read(real_fd, buf, sizeof(buf))) > 0) {
        content.append(buf, n);
    }

    std::string filtered;
    std::istringstream stream(content);
    std::string line;
    while (std::getline(stream, line)) {
        if (!line.empty()) {
            char tmp[512];
            strncpy(tmp, line.c_str(), sizeof(tmp) - 1);
            tmp[sizeof(tmp) - 1] = '\0';
            if (rewrite_tracer_pid_line(tmp, sizeof(tmp))) {
                filtered += tmp;
                continue;
            }
        }
        if (should_remove_maps_line(line.c_str())) {
            continue;
        }
        filtered += line;
        filtered += '\n';
    }

    // Write filtered content to a memfd so the original file is untouched.
    int memfd = syscall(__NR_memfd_create, "", MFD_CLOEXEC);
    if (memfd < 0) {
        LOGE("maps_filter: memfd_create failed: errno=%d", errno);
        return -1;
    }

    ssize_t written = write(memfd, filtered.data(), filtered.size());
    if (written < 0) {
        LOGE("maps_filter: write to memfd failed: errno=%d", errno);
        close(memfd);
        return -1;
    }

    lseek(memfd, 0, SEEK_SET);
    return memfd;
}
