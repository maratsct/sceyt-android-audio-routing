package com.sceyt.audiorouting.internal.bluetooth

import com.sceyt.audiorouting.AudioRouterConfig
import com.sceyt.audiorouting.internal.Logger
import com.sceyt.audiorouting.internal.device.AudioDeviceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Manages Bluetooth SCO (Synchronous Connection-Oriented) audio connections
 * with configurable retry logic and timeout handling.
 */
internal class BluetoothScoManager(
    private val scope: CoroutineScope,
    private val audioDeviceManager: AudioDeviceManager,
    private val config: AudioRouterConfig,
    private val logger: Logger
) {
    /**
     * Result of an SCO connection attempt.
     */
    sealed class ScoResult {
        object Connected : ScoResult()
        data class Failed(val reason: String, val retryCount: Int) : ScoResult()
        object Cancelled : ScoResult()
    }

    private val _scoEvents = MutableSharedFlow<ScoEvent>(extraBufferCapacity = 10)
    val scoEvents: SharedFlow<ScoEvent> = _scoEvents.asSharedFlow()

    private var currentJob: Job? = null
    private var isConnected = false
    private var pendingCallback: ((Boolean) -> Unit)? = null

    /**
     * Events emitted by the SCO manager.
     */
    sealed class ScoEvent {
        object Connecting : ScoEvent()
        object Connected : ScoEvent()
        object Disconnected : ScoEvent()
        data class Failed(val reason: String, val retryCount: Int) : ScoEvent()
    }

    /**
     * Attempts to enable Bluetooth SCO with retries.
     * Returns the result of the connection attempt.
     */
    suspend fun enableSco(): ScoResult {
        cancelPendingOperations()

        var retryCount = 0
        var lastError: String = "Unknown error"

        while (retryCount <= config.scoRetryCount) {
            logger.d("SCO enable attempt ${retryCount + 1}/${config.scoRetryCount + 1}")
            _scoEvents.emit(ScoEvent.Connecting)

            try {
                val connected = withTimeout(config.scoTimeoutMs) {
                    attemptScoConnection()
                }

                if (connected) {
                    isConnected = true
                    _scoEvents.emit(ScoEvent.Connected)
                    return ScoResult.Connected
                } else {
                    lastError = "SCO connection failed"
                }
            } catch (e: CancellationException) {
                logger.d("SCO enable cancelled")
                return ScoResult.Cancelled
            } catch (e: Exception) {
                lastError = e.message ?: "Timeout"
                logger.w("SCO enable attempt $retryCount failed: $lastError")
            }

            retryCount++
            if (retryCount <= config.scoRetryCount) {
                delay(config.scoRetryDelayMs)
            }
        }

        _scoEvents.emit(ScoEvent.Failed(lastError, retryCount))
        return ScoResult.Failed(lastError, retryCount)
    }

    /**
     * Disables Bluetooth SCO.
     */
    suspend fun disableSco() {
        cancelPendingOperations()

        if (isConnected) {
            audioDeviceManager.stopBluetoothSco()
            isConnected = false
            _scoEvents.emit(ScoEvent.Disconnected)
            logger.d("SCO disabled")
        }
    }

    /**
     * Cancels any pending SCO operations.
     */
    fun cancelPendingOperations() {
        currentJob?.cancel()
        currentJob = null
        pendingCallback?.invoke(false)
        pendingCallback = null
    }

    /**
     * Called when SCO audio state changes from the system.
     */
    fun onScoAudioStateChanged(state: Int) {
        when (state) {
            android.media.AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                logger.d("System SCO audio connected")
                isConnected = true
                pendingCallback?.invoke(true)
                pendingCallback = null
                scope.launch {
                    _scoEvents.emit(ScoEvent.Connected)
                }
            }
            android.media.AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                logger.d("System SCO audio disconnected")
                val wasConnected = isConnected
                isConnected = false
                pendingCallback?.invoke(false)
                pendingCallback = null
                if (wasConnected) {
                    scope.launch {
                        _scoEvents.emit(ScoEvent.Disconnected)
                    }
                }
            }
            android.media.AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                logger.d("System SCO audio connecting")
            }
        }
    }

    private suspend fun attemptScoConnection(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            pendingCallback = { success ->
                if (continuation.isActive) {
                    continuation.resume(success)
                }
            }

            continuation.invokeOnCancellation {
                pendingCallback = null
            }

            audioDeviceManager.startBluetoothSco()
        }
    }

    /**
     * Starts a non-blocking SCO enable operation.
     */
    fun startScoAsync(onResult: (ScoResult) -> Unit) {
        currentJob = scope.launch {
            val result = enableSco()
            onResult(result)
        }
    }

    /**
     * Whether SCO is currently connected.
     */
    val isScoConnected: Boolean
        get() = isConnected
}
