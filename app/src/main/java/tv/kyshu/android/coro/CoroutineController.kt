package tv.kyshu.android.coro

import kotlinx.coroutines.withTimeoutOrNull

class CoroutineController {
    private val controlChannel = Channel<ControlCommand>(Channel.UNLIMITED)
    var isPaused = false

    suspend fun controlledCoroutine() {
        checkControl()
    }

    private suspend fun checkControl() {
        controlChannel.tryReceive().getOrNull()?.let {
            when (it) {
                ControlCommand.Pause -> {
                    isPaused = true
                    withTimeoutOrNull(30_000) {
                        while (controlChannel.receive() != ControlCommand.Resume) { }
                    }
                    isPaused = false
                }
                ControlCommand.Resume -> {}
            }
        }
    }

    fun pause() {
        controlChannel.trySend(ControlCommand.Pause)
    }

    fun resume() {
        controlChannel.trySend(ControlCommand.Resume)
    }
}

enum class ControlCommand {
    Pause,
    Resume
}