package com.sceyt.audiorouting.internal.bluetooth

import android.media.AudioManager
import com.sceyt.audiorouting.AudioRouterConfig
import com.sceyt.audiorouting.internal.Logger
import com.sceyt.audiorouting.internal.device.AudioDeviceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    /**
     * Events emitted by the SCO manager.
     */
    sealed class ScoEvent {
        object Connecting : ScoEvent()
        object Connected : ScoEvent()
        object Disconnected : ScoEvent()
        data class Failed(val reason: String, val retryCount: Int) : ScoEvent()
    }

    private val _scoEvents = MutableSharedFlow<ScoEvent>(extraBufferCapacity = 10)
    val scoEvents: SharedFlow<ScoEvent> = _scoEvents.asSharedFlow()

    private val _scoState = MutableStateFlow(ScoState.DISCONNECTED)
    val scoState: StateFlow<ScoState> = _scoState.asStateFlow()

    private var currentJob: Job? = null
    private var retryCount = 0
    private var resultCallback: ((ScoResult) -> Unit)? = null

    enum class ScoState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    /**
     * Starts a non-blocking SCO enable operation with retries.
     */
    fun startScoAsync(onResult: (ScoResult) -> Unit) {
        // Don't start if already connected or connecting
        if (_scoState.value == ScoState.CONNECTED) {
            logger.d("SCO already connected")
            onResult(ScoResult.Connected)
            return
        }

        // Cancel any existing operation
        cancelPendingOperations()

        resultCallback = onResult
        retryCount = 0
        attemptConnection()
    }

    private fun attemptConnection() {
        if (retryCount > config.scoRetryCount) {
            logger.w("SCO connection failed after $retryCount attempts")
            val callback = resultCallback
            resultCallback = null
            _scoState.value = ScoState.DISCONNECTED
            scope.launch {
                _scoEvents.emit(ScoEvent.Failed("Max retries exceeded", retryCount))
            }
            callback?.invoke(ScoResult.Failed("Max retries exceeded", retryCount))
            return
        }

        retryCount++
        logger.d("SCO enable attempt $retryCount/${config.scoRetryCount + 1}")
        _scoState.value = ScoState.CONNECTING

        scope.launch {
            _scoEvents.emit(ScoEvent.Connecting)
        }

        // Start SCO
        audioDeviceManager.startBluetoothSco()

        // Set up timeout for this attempt
        currentJob = scope.launch {
            delay(config.scoTimeoutMs)
            // If still connecting after timeout, retry
            if (_scoState.value == ScoState.CONNECTING) {
                logger.w("SCO connection timeout, retrying...")
                audioDeviceManager.stopBluetoothSco()
                delay(config.scoRetryDelayMs)
                attemptConnection()
            }
        }
    }

    /**
     * Called when SCO audio state changes from the system.
     */
    fun onScoAudioStateChanged(state: Int) {
        when (state) {
            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                logger.d("System SCO audio connected")
                currentJob?.cancel()
                currentJob = null
                _scoState.value = ScoState.CONNECTED
                
                val callback = resultCallback
                resultCallback = null
                retryCount = 0
                
                scope.launch {
                    _scoEvents.emit(ScoEvent.Connected)
                }
                callback?.invoke(ScoResult.Connected)
            }
            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                logger.d("System SCO audio disconnected")
                val wasConnected = _scoState.value == ScoState.CONNECTED
                
                // If we were connecting and got disconnected, this might be a failed attempt
                // Don't update state here - let the timeout/retry logic handle it
                if (_scoState.value == ScoState.CONNECTING) {
                    // SCO attempt was rejected, will retry via timeout
                    logger.d("SCO connection rejected, waiting for retry...")
                } else if (wasConnected) {
                    _scoState.value = ScoState.DISCONNECTED
                    scope.launch {
                        _scoEvents.emit(ScoEvent.Disconnected)
                    }
                }
            }
            AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                logger.d("System SCO audio connecting")
            }
        }
    }

    /**
     * Cancels any pending SCO operations.
     */
    fun cancelPendingOperations() {
        currentJob?.cancel()
        currentJob = null
        resultCallback = null
        retryCount = 0
    }

    /**
     * Disables Bluetooth SCO.
     */
    fun disableSco() {
        cancelPendingOperations()
        if (_scoState.value != ScoState.DISCONNECTED) {
            audioDeviceManager.stopBluetoothSco()
            _scoState.value = ScoState.DISCONNECTED
            scope.launch {
                _scoEvents.emit(ScoEvent.Disconnected)
            }
            logger.d("SCO disabled")
        }
    }

    /**
     * Whether SCO is currently connected.
     */
    val isScoConnected: Boolean
        get() = _scoState.value == ScoState.CONNECTED
}
