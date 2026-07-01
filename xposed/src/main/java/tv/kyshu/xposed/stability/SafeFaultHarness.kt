package tv.kyshu.xposed.stability

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class FaultClass {
    NONE,
    NULL_POINTER,
    INVALID_MEMORY_ACCESS,
    ILLEGAL_FUNCTION_CALL,
    TIMEOUT,
    UNEXPECTED_RUNTIME,
}

sealed class SafeFaultResult {
    data object Completed : SafeFaultResult()
    data class Recovered(val faultClass: FaultClass, val message: String) : SafeFaultResult()
    data class Blocked(val faultClass: FaultClass, val message: String) : SafeFaultResult()
}

open class SimulatedUnsafeFault(message: String) : RuntimeException(message)
class SimulatedInvalidMemoryAccess(message: String = "Simulated invalid memory address access") : SimulatedUnsafeFault(message)
class SimulatedIllegalFunctionCall(message: String = "Simulated illegal function invocation") : SimulatedUnsafeFault(message)

object SafeFaultHarness {
    fun run(label: String, timeoutMs: Long = 200L, block: () -> Unit): SafeFaultResult {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "safe-fault-$label").apply { isDaemon = true }
        }
        var future: java.util.concurrent.Future<*>? = null
        return try {
            future = executor.submit(Callable {
                block()
            })
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
            SafeFaultResult.Completed
        } catch (_: TimeoutException) {
            future?.cancel(true)
            SafeFaultResult.Blocked(FaultClass.TIMEOUT, "Execution timed out after ${timeoutMs}ms")
        } catch (executionException: ExecutionException) {
            classify(executionException.cause ?: executionException)
        } catch (throwable: Throwable) {
            classify(throwable)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun classify(throwable: Throwable): SafeFaultResult {
        return when (throwable) {
            is SimulatedInvalidMemoryAccess -> {
                SafeFaultResult.Blocked(FaultClass.INVALID_MEMORY_ACCESS, throwable.message ?: "Invalid memory access blocked")
            }

            is SimulatedIllegalFunctionCall -> {
                SafeFaultResult.Blocked(FaultClass.ILLEGAL_FUNCTION_CALL, throwable.message ?: "Illegal function call blocked")
            }

            is NullPointerException -> {
                SafeFaultResult.Recovered(FaultClass.NULL_POINTER, throwable.message ?: "Null pointer recovered")
            }

            else -> {
                SafeFaultResult.Recovered(FaultClass.UNEXPECTED_RUNTIME, throwable.message ?: throwable::class.java.simpleName)
            }
        }
    }
}
