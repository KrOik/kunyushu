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

package tv.kyshu.xposed

import android.os.IBinder

object ProtocolConstants {
    /**
     * Transaction code for exchanging data between System Server and App Process.
     * Uses IBinder.FIRST_CALL_TRANSACTION (1) as the base.
     */
    const val TRANSACTION_EXCHANGE_DATA = IBinder.FIRST_CALL_TRANSACTION
}
