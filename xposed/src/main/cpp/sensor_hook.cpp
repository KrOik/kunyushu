//
// Created by fuqiuluo on 2024/10/15.
//
#include <dobby.h>
#include <unistd.h>
#include "sensor_hook.h"
#include "logging.h"
#include "elf_util.h"
#include "dobby_hook.h"

#define LIBSF_PATH "/system/lib64/libsensorservice.so"

extern bool enableSensorHook;

// _ZN7android16SensorEventQueue5writeERKNS_2spINS_7BitTubeEEEPK12ASensorEventm
// _ZN7android16SensorEventQueue5writeERKNS_2spINS_7BitTubeEEEPK12ASensorEventm
OriginalSensorEventQueueWriteType OriginalSensorEventQueueWrite = nullptr;

OriginalConvertToSensorEventType OriginalConvertToSensorEvent = nullptr;

int64_t SensorEventQueueWrite(void *tube, void *events, int64_t numEvents) {
    if (enableSensorHook) {
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
    if (enableSensorHook) {
        auto a = *(int32_t *)((char*)src + 4);
        auto b = *(int32_t *)((char*)src + 8);
        auto c = *(int64_t *)((char*)src + 16);

        // Copy timestamp and basic type fields from source
        *(int64_t *)((char*)dst) = c;
        *(int32_t *)((char*)dst + 8) = a;
        *(int32_t *)((char*)dst + 12) = b;

        // Generate randomized sensor values instead of constant -1.
        // This makes mock detection via sensor fingerprinting harder.
        if (b == 18) {
            // Step counter: accumulate small increments
            float prior = *(float *)((char*)dst + 16);
            float delta = (float)((rand() % 3) + 1);
            *(float *)((char*)dst + 16) = prior + delta;
            *(int32_t *)((char*)dst + 24) = 0;
        } else if (b == 19) {
            // Step detector: binary with probabilistic trigger
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
    } else {
        OriginalConvertToSensorEvent(src, dst);
    }

    if (enableSensorHook) {
        LOGD("ConvertToSensorEvent called");
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

