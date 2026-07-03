#ifndef _LOGGING_H
#define _LOGGING_H

#include <android/log.h>

#ifndef LOG_TAG
#define LOG_TAG  "LSPosed-Bridge"
#endif

#ifndef LOG_MARKER
#define LOG_MARKER "PTL"
#endif

#ifdef LOG_DISABLED
#define LOGD(...)
#define LOGV(...)
#define LOGI(...)
#define LOGW(...)
#define LOGE(...)
#else
#ifndef NDEBUG
#define LOGD(fmt, ...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "[" LOG_MARKER "][DEBUG] %s:%d#%s" ": " fmt, __FILE_NAME__, __LINE__, __PRETTY_FUNCTION__ __VA_OPT__(,) __VA_ARGS__)
#define LOGV(fmt, ...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, "[" LOG_MARKER "][VERBOSE] %s:%d#%s" ": " fmt, __FILE_NAME__, __LINE__, __PRETTY_FUNCTION__ __VA_OPT__(,) __VA_ARGS__)
#else
#define LOGD(...)
#define LOGV(...)
#endif
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "[" LOG_MARKER "][WARN] %s:%d#%s" ": " fmt, __FILE_NAME__, __LINE__, __PRETTY_FUNCTION__ __VA_OPT__(,) __VA_ARGS__)
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "[" LOG_MARKER "][INFO] %s:%d#%s" ": " fmt, __FILE_NAME__, __LINE__, __PRETTY_FUNCTION__ __VA_OPT__(,) __VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[" LOG_MARKER "][ERROR] %s:%d#%s" ": " fmt, __FILE_NAME__, __LINE__, __PRETTY_FUNCTION__ __VA_OPT__(,) __VA_ARGS__)
#endif

#endif // _LOGGING_H
