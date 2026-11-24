#ifndef _LOGGING_H
#define _LOGGING_H

#include <android/log.h>

// ==========================================================================
// Logging policy — anti-detection hardened.
//
// DuckDetector risk model (R2): LSPosed logcat probe matches tag "LSPosed-Bridge",
// hook lifecycle IPCs, classloader operations, and absolute paths. To reduce the
// controllable signal surface:
//
//   - Default LOG_TAG is project-neutral ("TV.KS" → tv.kyshu shorthand).
//   - In release builds (NDEBUG defined): INFO/WARN are suppressed from logcat
//     entirely. ERROR stays but with short codes only — no file/line/function.
//   - In debug builds (KUNYUSHU_DEBUG or !NDEBUG): full file/line/function output,
//     same as before, for development auditing.
//   - LOG_DISABLED macro: compile-time kill-switch for ALL log output.
//
// Best practices aligned with: Vector (VectorNative tag, #ifndef NDEBUG gating),
// zeromount (structured file logging, not logcat).
// ==========================================================================

#ifndef LOG_TAG
#define LOG_TAG  "TV.KS"
#endif

#ifndef LOG_MARKER
#define LOG_MARKER "KS"
#endif

#ifdef LOG_DISABLED
// Compile-time kill-switch: all macros are no-ops.
#define LOGD(...)
#define LOGV(...)
#define LOGI(...)
#define LOGW(...)
#define LOGE(...)
#elif defined(KUNYUSHU_DEBUG) || !defined(NDEBUG)
// ---- Debug/development build: full diagnostic logging ----
#define LOGD(fmt, ...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "[" LOG_MARKER "][D] %s:%d#%s" ": " fmt, __FILE_NAME__, __LINE__, __PRETTY_FUNCTION__ __VA_OPT__(,) __VA_ARGS__)
#define LOGV(fmt, ...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, "[" LOG_MARKER "][V] %s:%d#%s" ": " fmt, __FILE_NAME__, __LINE__, __PRETTY_FUNCTION__ __VA_OPT__(,) __VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "[" LOG_MARKER "][W] %s:%d#%s" ": " fmt, __FILE_NAME__, __LINE__, __PRETTY_FUNCTION__ __VA_OPT__(,) __VA_ARGS__)
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "[" LOG_MARKER "][I] %s:%d#%s" ": " fmt, __FILE_NAME__, __LINE__, __PRETTY_FUNCTION__ __VA_OPT__(,) __VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[" LOG_MARKER "][E] %s:%d#%s" ": " fmt, __FILE_NAME__, __LINE__, __PRETTY_FUNCTION__ __VA_OPT__(,) __VA_ARGS__)
#else
// ---- Release build: anti-detection hardened ----
// DEBUG/VERBOSE: always stripped.
#define LOGD(...)
#define LOGV(...)
// INFO/WARN: suppressed from logcat. These often leak hook lifecycle,
// IPC operations, classloader paths, and security gate decisions that
// DuckDetector's LSPosed logcat probe can pattern-match.
#define LOGI(...)
#define LOGW(...)
// ERROR only: constant short code. The format string and arguments are
// intentionally ignored in release builds so device fingerprints, paths, and
// symbols cannot leak through native logcat.
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[" LOG_MARKER "][E]")
#endif

#endif // _LOGGING_H
