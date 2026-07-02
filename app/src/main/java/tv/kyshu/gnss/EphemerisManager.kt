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

package tv.kyshu.gnss

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.xposed.utils.Log

object EphemerisManager {
    private const val TAG = "EphemerisManager"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun getEphemerisFile(context: Context): File {
        return File(context.filesDir, "brdc.rnx")
    }

    /**
     * 由用户手动触发星历下载，完成后通过 callback 报告结果。
     * 下载成功时自动将路径同步至 xposed 模块 (update_rinex 命令)。
     */
    fun updateEphemeris(context: Context, locationManager: android.location.LocationManager, callback: (Boolean, String) -> Unit) {
        Thread {
            try {
                val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                val errors = StringBuilder()
                if (!tryDownload(context, calendar, errors)) {
                    errors.append("\n尝试昨天数据...")
                    calendar.add(Calendar.DAY_OF_YEAR, -1)
                    if (!tryDownload(context, calendar, errors)) {
                        callback(false, "下载失败：无法获取近两天星历文件。\n$errors")
                        return@Thread
                    }
                }
                // 部署完成，通过 update_rinex 命令通知 xposed 模块
                val rinexFile = getEphemerisFile(context)
                val path = rinexFile.absolutePath
                MockServiceHelper.sendCommandWithFallback(
                    locationManager, context,
                    "update_rinex", android.os.Bundle().apply {
                        putString("rinex_path", path)
                    })
                Log.i(TAG, "RINEX deployed at $path, sent update_rinex command")
                // 持久化到本地 SharedPreferences，跨页面切换时恢复显示
                context.getSharedPreferences(tv.kyshu.service.MockServiceHelper.PROVIDER_NAME, android.content.Context.MODE_PRIVATE)
                    .edit().putString("rinexPath", path).apply()
                callback(true, "星历更新成功！\n$path")
            } catch (e: Exception) {
                Log.e(TAG, "updateEphemeris failed", e)
                callback(false, "更新异常: ${e.message}")
            }
        }.start()
    }

    private fun tryDownload(context: Context, calendar: Calendar, errorLog: StringBuilder): Boolean {
        val year = calendar.get(Calendar.YEAR)
        val doy = calendar.get(Calendar.DAY_OF_YEAR)
        val shortYear = year % 100
        val doyStr = String.format(Locale.US, "%03d", doy)

        val fileNames = mutableListOf(
            "BRDC00WRD_R_${year}${doyStr}0000_01D_MN.rnx.gz",
            "brdm${doyStr}0.${shortYear}p.gz",
            "brdc${doyStr}0.${shortYear}n.gz"
        )

        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val hourChar = (97 + hour).toChar()
        fileNames.add(0, "brdm${doyStr}${hourChar}.${shortYear}p.gz")
        fileNames.add(1, "brdc${doyStr}${hourChar}.${shortYear}n.gz")

        val baseUrls = listOf(
            "https://igs.bkg.bund.de/root_ftp/IGS/BRDC/$year/$doyStr/",
            "https://igs.bkg.bund.de/root_ftp/IGS/nrt/$year/$doyStr/"
        )

        val tempFile = File(context.cacheDir, "ephemeris.gz")
        val outFile = getEphemerisFile(context)

        for (baseUrl in baseUrls) {
            for (fileName in fileNames) {
                val url = baseUrl + fileName
                Log.i(TAG, "Trying: $url")
                try {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (response.code == 404) {
                            errorLog.append("Not found: $url\n")
                            return@use
                        }
                        if (!response.isSuccessful) {
                            errorLog.append("HTTP ${response.code}: $url\n")
                            return@use
                        }
                        val body = response.body ?: run {
                            errorLog.append("Empty body: $url\n")
                            return@use
                        }
                        FileOutputStream(tempFile).use { output ->
                            body.byteStream().copyTo(output)
                        }
                        // 解压 GZIP
                        try {
                            GZIPInputStream(tempFile.inputStream()).use { input ->
                                FileOutputStream(outFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        } catch (e: Exception) {
                            errorLog.append("解压失败: ${e.message}\n")
                            return@use
                        }
                        // 简单校验：非空且包含 RINEX 头
                        if (!outFile.exists() || outFile.length() < 1024) {
                            errorLog.append("文件过小或不存在\n")
                            return@use
                        }
                        Log.i(TAG, "Downloaded and extracted: $fileName -> ${outFile.absolutePath}")
                        return true
                    }
                } catch (e: Exception) {
                    errorLog.append("下载异常: ${e.message}\n")
                }
            }
        }
        return false
    }
}
