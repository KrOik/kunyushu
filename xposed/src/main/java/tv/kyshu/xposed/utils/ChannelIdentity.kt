/*
 * Copyright (C) 2025 The KunYuShu Project
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

package tv.kyshu.xposed.utils

import android.os.Build

/**
 * Device-specific IPC channel identity.
 *
 * Derives the provider name from Build.FINGERPRINT so each device
 * uses a unique, non-guessable string for the sendExtraCommand
 * back-channel. Replaces the previous hardcoded "gnss_debug".
 */
object ChannelIdentity {
    val providerName: String by lazy {
        "cmd_" + Build.FINGERPRINT.take(8).fold(0) { acc, c ->
            acc * 31 + c.code
        }.toUInt().toString(16).take(8)
    }
}
