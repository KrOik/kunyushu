package tv.kyshu.android.coro

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

class CoroutineRouteMock {
    private val routeMockChannel = Channel<RouteMockCommand>(Channel.UNLIMITED)
    var isPaused = false

    suspend fun routeMockCoroutine() {
        checkRouteMockStatus()
    }

    private suspend fun checkRouteMockStatus() {
        routeMockChannel.tryReceive().getOrNull()?.let {
            when (it) {
                RouteMockCommand.Pause -> {
                    isPaused = true
                    withTimeoutOrNull(30_000) {
                        while (routeMockChannel.receive() != RouteMockCommand.Resume) { }
                    }
                    isPaused = false
                }
                RouteMockCommand.Resume -> {}
            }
        }
    }

    fun pause() {
        routeMockChannel.trySend(RouteMockCommand.Pause)
    }

    fun resume() {
        routeMockChannel.trySend(RouteMockCommand.Resume)
    }
}

enum class RouteMockCommand {
    Pause,
    Resume
}