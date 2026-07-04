package tv.kyshu.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.xposed.utils.Log as XLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class StartupDiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: "unknown"
        if (action !in allowedActions) {
            XLog.i(TAG, "[StartupDiag] ignored unsupported action=$action")
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastRunAtMs.get()
        if (last > 0L && now - last < MIN_INTERVAL_MS) {
            XLog.i(TAG, "[StartupDiag] skipped by rate limit action=$action ageMs=${now - last}")
            return
        }
        if (!running.compareAndSet(false, true)) {
            XLog.i(TAG, "[StartupDiag] skipped because previous diagnostic is still running action=$action")
            return
        }
        if (!diagnosticWorkerBusy.compareAndSet(false, true)) {
            running.set(false)
            XLog.w(TAG, "[StartupDiag] skipped because diagnostic worker is still blocked action=$action")
            return
        }

        lastRunAtMs.set(now)
        val pending = goAsync()
        executor.execute {
            val future = try {
                diagnosticExecutor.submit {
                    try {
                        val appContext = context.applicationContext ?: context
                        XLog.init(appContext)
                        XLog.i(TAG, "[StartupDiag] received action=$action")
                        MockServiceHelper.diagnoseInterfaces(appContext, "receiver:$action")
                    } finally {
                        diagnosticWorkerBusy.set(false)
                    }
                }
            } catch (t: Throwable) {
                diagnosticWorkerBusy.set(false)
                running.set(false)
                XLog.e(TAG, "[StartupDiag] submit failed action=$action", t)
                runCatching { pending.finish() }
                return@execute
            }

            try {
                future.get(DIAGNOSTIC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (t: TimeoutException) {
                future.cancel(true)
                XLog.e(TAG, "[StartupDiag] timed out action=$action timeoutMs=$DIAGNOSTIC_TIMEOUT_MS", t)
            } catch (t: Throwable) {
                XLog.e(TAG, "[StartupDiag] failed action=$action", t)
            } finally {
                running.set(false)
                runCatching { pending.finish() }
            }
        }
    }

    companion object {
        private const val TAG = "PTL.App"
        private const val MIN_INTERVAL_MS = 30_000L
        private const val DIAGNOSTIC_TIMEOUT_MS = 20_000L

        private val allowedActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
        private val running = AtomicBoolean(false)
        private val diagnosticWorkerBusy = AtomicBoolean(false)
        private val lastRunAtMs = AtomicLong(0L)
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Portal-StartupDiag").apply { isDaemon = true }
        }
        private val diagnosticExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "Portal-StartupDiagWorker").apply { isDaemon = true }
        }
    }
}
