#include <dobby.h>
#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <cstring>
#include "sensor_hook.h"
#include "crash_recovery.h"
#include "safety_gate.h"
#include "logging.h"

std::atomic_bool enableSensorHook{false};

static bool is_system_server_process() {
    char cmdline[256] = {};
    FILE* file = fopen("/proc/self/cmdline", "r");
    if (file == nullptr) return false;
    size_t n = fread(cmdline, 1, sizeof(cmdline) - 1, file);
    fclose(file);
    return n > 0 && strcmp(cmdline, "system_server") == 0;
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnLoad called: Portal Native Hook is loading...");
    JNIEnv* env;
    if (vm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        LOGD("JNI_OnLoad failed: GetEnv failed");
        return JNI_ERR;
    }
    if (is_system_server_process()) {
        // Layer 2: Install SIGSEGV/BUS/ILL recovery handlers.
        install_crash_handlers();

        // Sensor hooks: ELF parsing + Dobby inline hook.
        doSensorHook();
        LOGD("doSensorHook complete");
    }
    return JNI_VERSION_1_6;
}

// ==========================================================================
// Layer 3 JNI exports: crash counter management from Java watchdog.
// ==========================================================================
extern "C"
JNIEXPORT jint JNICALL
Java_tv_kyshu_dobby_Dobby_checkCrashMeltdown(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    int count = read_crash_counter();
    if (count >= kCrashMeltdownThreshold) {
        write_terminal_despair_lock();
        return -1;  // meltdown triggered
    }
    return count;
}

extern "C"
JNIEXPORT void JNICALL
Java_tv_kyshu_dobby_Dobby_recordCrashRecovery(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    record_crash_recovery();
}

extern "C"
JNIEXPORT void JNICALL
Java_tv_kyshu_dobby_Dobby_resetCrashCounter(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    reset_crash_counter();
}

extern "C"
JNIEXPORT void JNICALL
Java_tv_kyshu_dobby_Dobby_setStatus(JNIEnv *env, jobject thiz, jboolean status) {
    enableSensorHook.store(status != JNI_FALSE, std::memory_order_release);
}

extern "C"
JNIEXPORT void JNICALL
Java_tv_kyshu_dobby_Dobby_initSafetyFramework(JNIEnv *env, jobject thiz, jstring cacheDir) {
    if (cacheDir == nullptr) {
        LOGE("initSafetyFramework: cacheDir is null");
        return;
    }
    const char* utf = env->GetStringUTFChars(cacheDir, nullptr);
    if (utf == nullptr) return;
    strncpy(g_app_cache_dir, utf, sizeof(g_app_cache_dir) - 1);
    g_app_cache_dir[sizeof(g_app_cache_dir) - 1] = '\0';
    env->ReleaseStringUTFChars(cacheDir, utf);
    LOGD("initSafetyFramework: cache dir set to %s", g_app_cache_dir);
}

extern "C"
JNIEXPORT void JNICALL
Java_tv_kyshu_dobby_Dobby_writeArtVersionLock(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    write_art_version_lock();
    LOGD("ART version lock written");
}

extern "C"
JNIEXPORT void JNICALL
Java_tv_kyshu_dobby_Dobby_writeTerminalDespairLock(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    write_terminal_despair_lock();
    LOGD("Terminal despair lock written");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_tv_kyshu_dobby_Dobby_isTerminalDespairLocked(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return is_terminal_despair_locked() ? JNI_TRUE : JNI_FALSE;
}
