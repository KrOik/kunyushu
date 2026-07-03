//
// Created by fuqiuluo on 2024/10/15.
//
// Safety boundary: every hooked function that performs raw pointer arithmetic
// MUST be wrapped in CRASH_RECOVERY_BEGIN/END. If the target Android version
// changes sensors_event_t layout or symbol offsets, the hook will SIGSEGV
// inside our code range → crash recovery catches it → hook permanently disabled
// → system_server stays alive.
//

#include <dobby.h>
#include <unistd.h>
#include <atomic>
#include <cstdlib>
#include "sensor_hook.h"
#include "logging.h"
#include "elf_util.h"
#include "dobby_hook.h"
#include "crash_recovery.h"

#define LIBSF_PATH "/system/lib64/libsensorservice.so"

extern std::atomic_bool enableSensorHook;

OriginalSensorEventQueueWriteType OriginalSensorEventQueueWrite = nullptr;
OriginalConvertToSensorEventType OriginalConvertToSensorEvent = nullptr;

// Per-thread crash recovery state for ConvertToSensorEvent.
// If pointer arithmetic crashes (SIGSEGV on incompatible ROM layout),
// the signal handler recovers and sensorHookFailed is set to prevent
// re-entry.
CRASH_RECOVERY_DECL(g_sensor_convert_recovery);
static std::atomic<bool> sensorHookFailed{false};
int64_t SensorEventQueueWrite(void *tube, void *events, int64_t numEvents) {
    if (enableSensorHook.load()) {
        LOGD("SensorEventQueueWrite called");
    }
    if (OriginalSensorEventQueueWrite == nullptr) {
        LOGE("SensorEventQueueWrite: original function is null, skipping hook");
        return 0;
    }
    return OriginalSensorEventQueueWrite(tube, events, numEvents);
}
void ConvertToSensorEvent(void *src, void *dst) {
    if (OriginalConvertToSensorEvent == nullptr) {
        LOGE("ConvertToSensorEvent: original function is null, skipping hook");
        return;
    }

    // Fast bypass: if the hook has already crashed (recovered), fall through
    // to original to avoid repeated crashes.
    if (sensorHookFailed.load()) {
        OriginalConvertToSensorEvent(src, dst);
        return;
    }

    if (!enableSensorHook.load()) {
        OriginalConvertToSensorEvent(src, dst);
        return;
    }

    // null check outside crash recovery block: avoid break skipping cleanup
    if (src == nullptr || dst == nullptr) {
        LOGE("ConvertToSensorEvent: src or dst is null, skipping hook");
        OriginalConvertToSensorEvent(src, dst);
        return;
    }

    // Wrap bare-pointer arithmetic in crash recovery. On ROMs where
    // Event/sensors_event_t layout differs from the hardcoded offsets,
    // a SIGSEGV will be caught and sensorHookFailed permanently set.
    CRASH_RECOVERY_BEGIN(g_sensor_convert_recovery, "SENSOR_CONVERT") {
        auto a = *(int32_t *)((char*)src + 4);
        auto b = *(int32_t *)((char*)src + 8);
        auto c = *(int64_t *)((char*)src + 16);

        // Copy timestamp and basic type fields from source
        *(int64_t *)((char*)dst) = c;
        *(int32_t *)((char*)dst + 8) = a;
        *(int32_t *)((char*)dst + 12) = b;

        // Generate randomized sensor values instead of constant -1.
        // This makes mock detection via sensor fingerprinting harder.
        // Android: TYPE_STEP_DETECTOR=18, TYPE_STEP_COUNTER=19
        if (b == 19) {
            // Step counter (TYPE_STEP_COUNTER = 19): accumulate small increments
            // sensors_event_t has uint64_t step_counter at offset 16
            uint64_t prior = *(uint64_t *)((char*)dst + 16);
            int delta = (rand() % 3) + 1;
            *(uint64_t *)((char*)dst + 16) = prior + static_cast<uint64_t>(delta);
            *(int32_t *)((char*)dst + 24) = 0;
        } else if (b == 18) {
            // Step detector (TYPE_STEP_DETECTOR = 18): binary with probabilistic trigger
            *(int64_t *)((char*)dst + 16) = (rand() % 4 == 0) ? 1 : 0;
        } else {
            // Accelerometer / gyroscope / other: small noise around zero
            float noise_x = (float)(rand() % 400 - 200) / 1000.0f;
            float noise_y = (float)(rand() % 400 - 200) / 1000.0f;
            float noise_z = (float)(rand() % 400 - 200) / 1000.0f;
            *(float *)((char*)dst + 16) = noise_x;
            *(float *)((char*)dst + 24) = noise_y;
            *(int8_t *)((char*)dst + 28) = *(int8_t *)((char*)src + 36);
        }
    } CRASH_RECOVERY_END(g_sensor_convert_recovery);

    if (g_sensor_convert_recovery.recovered) {
        // The sensor hook body crashed (SIGSEGV/BUS/ILL). Permanently disable
        // to protect system_server stability.
        sensorHookFailed.store(true);
        enableSensorHook.store(false);
        LOGE("ConvertToSensorEvent: CRASH RECOVERED — sensor hook permanently disabled");
        // Call the original function to forward the event correctly.
        OriginalConvertToSensorEvent(src, dst);
    }
}

void doSensorHook() {
    SandHook::ElfImg sensorService(LIBSF_PATH);

    if (!sensorService.isValid()) {
        LOGE("failed to load libsensorservice");
        return;
    }

    auto sensorWrite = sensorService.getSymbolAddress<void*>("_ZN7android16SensorEventQueue5writeERKNS_2spINS_7BitTubeEEEPK12ASensorEventm");
    if (sensorWrite == nullptr) {
        sensorWrite = sensorService.getSymbolAddress<void*>("_ZN7android16SensorEventQueue5writeERKNS_2spINS_7BitTubeEEEPK12ASensorEventj");
    }

    auto convertToSensorEvent = sensorService.getSymbolAddress<void*>("_ZN7android8hardware7sensors4V1_014implementation20convertToSensorEventERKNS2_5EventEP15sensors_event_t");

    LOGI("Dobby SensorEventQueue::write found at %p", sensorWrite);
    LOGI("Dobby convertToSensorEvent found at %p", convertToSensorEvent);

    if (sensorWrite != nullptr) {
        auto orig = InlineHook(sensorWrite, (void *)SensorEventQueueWrite);
        if (orig != nullptr) {
            OriginalSensorEventQueueWrite = (OriginalSensorEventQueueWriteType)orig;
        } else {
            LOGE("DobbyHook failed for SensorEventQueue::write");
        }
    }

    if (convertToSensorEvent != nullptr) {
        auto orig = InlineHook(convertToSensorEvent, (void *)ConvertToSensorEvent);
        if (orig != nullptr) {
            OriginalConvertToSensorEvent = (OriginalConvertToSensorEventType)orig;
        } else {
            LOGE("DobbyHook failed for convertToSensorEvent");
        }
    }
}

