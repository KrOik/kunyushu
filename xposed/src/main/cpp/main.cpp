#include <dobby.h>
#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <cstring>
#include <cstdlib>
#include "sensor_hook.h"
#include "crash_recovery.h"
#include "safety_gate.h"
#include "csprng.h"
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
    LOGD("JNI_OnLoad called");
    JNIEnv* env;
    if (vm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    if (is_system_server_process()) {
        // Seed CSPRNG for sensor hook randomization (risk model R7).
        // Replaces weak srand(time(nullptr)).
        csprng_init();
    }
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_tv_kyshu_dobby_Dobby_installNativeSensorHook(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (!is_system_server_process()) {
        return JNI_FALSE;
    }
    csprng_init();
    if (is_terminal_despair_locked() || !check_crash_meltdown() || is_safe_mode()) {
        LOGE("native sensor hook skipped by safety gate");
        return JNI_FALSE;
    }
    if (!install_crash_handlers()) {
        LOGE("native sensor hook skipped: crash recovery unavailable");
        return JNI_FALSE;
    }
    bool ok = safe_execute_failfast("SENSOR_NATIVE_INSTALL", []() -> bool {
        doSensorHook();
        return !isNativeSensorHookFailed();
    });
    if (ok) {
        LOGD("native sensor hook initialized");
    }
    return ok ? JNI_TRUE : JNI_FALSE;
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
    (void)env; (void)thiz;
    if (isNativeSensorHookFailed()) {
        enableSensorHook.store(false, std::memory_order_release);
        LOGE("setStatus ignored: native sensor hook is failed-inactive");
        return;
    }
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
    if (utf[0] == '\0') {
        LOGE("initSafetyFramework: cacheDir is blank");
        env->ReleaseStringUTFChars(cacheDir, utf);
        return;
    }
    set_app_cache_dir(utf);
    env->ReleaseStringUTFChars(cacheDir, utf);
    LOGI("initSafetyFramework: cache dir ready");
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
    LOGI("Terminal despair lock written");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_tv_kyshu_dobby_Dobby_isTerminalDespairLocked(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return is_terminal_despair_locked() ? JNI_TRUE : JNI_FALSE;
}

// Stub: maps_filter /proc/maps hooks are excluded from release per risk model R4.
// The JNI symbol must remain for binary compatibility (Dobby.kt declares the
// external fun), but returns JNI_FALSE unconditionally.
extern "C"
JNIEXPORT jboolean JNICALL
Java_tv_kyshu_dobby_Dobby_installProcMapsHooks(JNIEnv*, jobject) {
    return JNI_FALSE;
}
