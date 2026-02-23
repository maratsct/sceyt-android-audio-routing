package com.sceyt.audiorouting.internal.state

import com.sceyt.audiorouting.AudioDevice
import com.sceyt.audiorouting.AudioRouterConfig
import com.sceyt.audiorouting.RoutingState
import com.sceyt.audiorouting.internal.Logger
import com.sceyt.audiorouting.internal.device.DevicePriorityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State machine for managing audio routing state transitions.
 * Events are processed sequentially via a Channel to prevent race conditions.
 */
internal class AudioRoutingStateMachine(
    scope: CoroutineScope,
    private val config: AudioRouterConfig,
    private val logger: Logger,
    private val deviceManager: DevicePriorityManager,
    private val onStateChanged: suspend (AudioRoutingState, AudioRoutingState) -> Unit
) {
    private val _state = MutableStateFlow(AudioRoutingState())
    val state: StateFlow<AudioRoutingState> = _state.asStateFlow()

    private val eventChannel = Channel<AudioRoutingEvent>(Channel.UNLIMITED)

    init {
        // Start event processing loop
        scope.launch {
            for (event in eventChannel) {
                processEvent(event)
            }
        }
    }

    /**
     * Sends an event to be processed by the state machine.
     */
    fun sendEvent(event: AudioRoutingEvent) {
        eventChannel.trySend(event)
    }

    /**
     * Closes the event channel. Call this when shutting down.
     */
    fun close() {
        eventChannel.close()
    }

    private suspend fun processEvent(event: AudioRoutingEvent) {
        val oldState = _state.value
        logger.d("Processing event: $event in state: ${oldState.routingState}")

        val newState = when (event) {
            is AudioRoutingEvent.Start -> handleStart(oldState)
            is AudioRoutingEvent.Stop -> handleStop(oldState)
            is AudioRoutingEvent.Activate -> handleActivate(oldState)
            is AudioRoutingEvent.Deactivate -> handleDeactivate(oldState)
            is AudioRoutingEvent.BluetoothDeviceConnected -> handleBluetoothConnected(
                state = oldState,
                device = event.device
            )

            is AudioRoutingEvent.BluetoothDeviceDisconnected -> handleBluetoothDisconnected(oldState)
            is AudioRoutingEvent.WiredHeadsetConnected -> handleWiredHeadsetConnected(oldState)
            is AudioRoutingEvent.WiredHeadsetDisconnected -> handleWiredHeadsetDisconnected(oldState)
            is AudioRoutingEvent.BluetoothScoConnected -> handleScoConnected(oldState)
            is AudioRoutingEvent.BluetoothScoDisconnected -> handleScoDisconnected(oldState)
            is AudioRoutingEvent.BluetoothScoFailed -> handleScoFailed(oldState, event.reason)
            is AudioRoutingEvent.UserSelectDevice -> handleUserSelectDevice(oldState, event.device)
            is AudioRoutingEvent.ClearManualSelection -> handleClearManualSelection(oldState)
            is AudioRoutingEvent.UpdatePreferredOrder -> handleUpdatePreferredOrder(
                oldState,
                event.order
            )

            is AudioRoutingEvent.EnumerateDevices -> handleEnumerateDevices(oldState)
            is AudioRoutingEvent.InitializeDevices -> handleInitializeDevices(
                state = oldState,
                devices = event.devices,
                selectedDevice = event.selectedDevice
            )
        }

        if (newState != oldState) {
            _state.value = newState
            onStateChanged(oldState, newState)
        }
    }

    private fun handleStart(state: AudioRoutingState): AudioRoutingState {
        if (state.routingState != RoutingState.IDLE) {
            logger.d("Ignoring start() - already in state ${state.routingState}")
            return state
        }
        return state.copy(routingState = RoutingState.STARTED)
    }

    private fun handleStop(state: AudioRoutingState): AudioRoutingState {
        if (state.routingState == RoutingState.IDLE) {
            logger.d("Ignoring stop() - already stopped")
            return state
        }
        return AudioRoutingState() // Reset to initial state
    }

    private fun handleActivate(state: AudioRoutingState): AudioRoutingState {
        return when (state.routingState) {
            RoutingState.IDLE -> {
                logger.w("Cannot activate when stopped")
                state
            }

            RoutingState.STARTED -> state.copy(routingState = RoutingState.ACTIVATED)
            RoutingState.ACTIVATED -> {
                logger.d("Already activated")
                state
            }
        }
    }

    private fun handleDeactivate(state: AudioRoutingState): AudioRoutingState {
        return when (state.routingState) {
            RoutingState.ACTIVATED -> state.copy(
                routingState = RoutingState.STARTED,
                bluetoothScoState = BluetoothScoState.Disconnected
            )

            else -> {
                logger.d("Ignoring deactivate() - not activated")
                state
            }
        }
    }

    private fun handleBluetoothConnected(
        state: AudioRoutingState,
        device: AudioDevice.BluetoothHeadset
    ): AudioRoutingState {
        if (!state.isListening) return state

        val newDevices = deviceManager.buildDeviceList(
            bluetoothDevice = device,
            wiredHeadsetConnected = state.wiredHeadsetConnected
        )
        val newSelected = deviceManager.selectBestDevice(
            availableDevices = newDevices,
            currentDevice = state.selectedDevice,
            newlyConnectedDevice = device
        )

        return state.copy(
            availableDevices = newDevices,
            selectedDevice = newSelected,
            activeBluetoothDevice = device
        )
    }

    private fun handleBluetoothDisconnected(
        state: AudioRoutingState
    ): AudioRoutingState {
        if (!state.isListening) return state

        val newDevices = deviceManager.buildDeviceList(
            bluetoothDevice = null,
            wiredHeadsetConnected = state.wiredHeadsetConnected
        )
        val currentDevice = state.selectedDevice
        val newSelected = if (currentDevice is AudioDevice.BluetoothHeadset) {
            deviceManager.selectFallbackDevice(state.availableDevices, currentDevice)
        } else {
            currentDevice
        }

        // Clear manual selection if the manually selected BT device was disconnected
        if (state.isManualSelection && currentDevice is AudioDevice.BluetoothHeadset) {
            deviceManager.clearManualSelection()
        }

        return state.copy(
            availableDevices = newDevices,
            selectedDevice = newSelected,
            activeBluetoothDevice = null,
            bluetoothScoState = BluetoothScoState.Disconnected,
            isManualSelection = deviceManager.isManualSelection
        )
    }

    private fun handleWiredHeadsetConnected(state: AudioRoutingState): AudioRoutingState {
        if (!state.isListening) return state

        val newDevices = deviceManager.buildDeviceList(
            bluetoothDevice = state.activeBluetoothDevice,
            wiredHeadsetConnected = true
        )
        val wiredHeadset = AudioDevice.WiredHeadset()
        val newSelected = deviceManager.selectBestDevice(
            availableDevices = newDevices,
            currentDevice = state.selectedDevice,
            newlyConnectedDevice = wiredHeadset
        )

        return state.copy(
            availableDevices = newDevices,
            selectedDevice = newSelected,
            wiredHeadsetConnected = true
        )
    }

    private fun handleWiredHeadsetDisconnected(state: AudioRoutingState): AudioRoutingState {
        if (!state.isListening) return state

        val newDevices = deviceManager.buildDeviceList(
            bluetoothDevice = state.activeBluetoothDevice,
            wiredHeadsetConnected = false
        )
        val currentDevice = state.selectedDevice
        val newSelected = if (currentDevice is AudioDevice.WiredHeadset) {
            deviceManager.selectFallbackDevice(state.availableDevices, currentDevice)
        } else {
            currentDevice
        }

        // Clear manual selection if the manually selected wired headset was disconnected
        if (state.isManualSelection && currentDevice is AudioDevice.WiredHeadset) {
            deviceManager.clearManualSelection()
        }

        return state.copy(
            availableDevices = newDevices,
            selectedDevice = newSelected,
            wiredHeadsetConnected = false,
            isManualSelection = deviceManager.isManualSelection
        )
    }

    private fun handleScoConnected(state: AudioRoutingState): AudioRoutingState {
        return state.copy(bluetoothScoState = BluetoothScoState.Connected)
    }

    private fun handleScoDisconnected(state: AudioRoutingState): AudioRoutingState {
        return state.copy(bluetoothScoState = BluetoothScoState.Disconnected)
    }

    private fun handleScoFailed(state: AudioRoutingState, reason: String): AudioRoutingState {
        val currentFailState = state.bluetoothScoState as? BluetoothScoState.Failed
        val retryCount = (currentFailState?.retryCount ?: 0) + 1

        return if (retryCount >= config.scoRetryCount) {
            // Max retries reached, select fallback device
            logger.w("Bluetooth SCO failed after $retryCount retries: $reason")
            val nonBluetoothDevices = state.availableDevices.filterNot { it is AudioDevice.BluetoothHeadset }
            val newSelected = deviceManager.selectBestDevice(
                availableDevices = nonBluetoothDevices,
                currentDevice = null
            )
            deviceManager.clearManualSelectionIfBluetooth()
            state.copy(
                bluetoothScoState = BluetoothScoState.Failed(reason, retryCount),
                selectedDevice = newSelected,
                isManualSelection = deviceManager.isManualSelection
            )
        } else {
            state.copy(
                bluetoothScoState = BluetoothScoState.Failed(reason, retryCount)
            )
        }
    }

    private fun handleUserSelectDevice(
        state: AudioRoutingState,
        device: AudioDevice?
    ): AudioRoutingState {
        if (!state.isListening) return state

        if (device == null) {
            return handleClearManualSelection(state)
        }

        // Verify device is available
        if (!state.availableDevices.any { it.id == device.id }) {
            logger.w("Cannot select unavailable device: $device")
            return state
        }

        deviceManager.setManualSelection(device)

        return state.copy(
            selectedDevice = device,
            isManualSelection = true
        )
    }

    private fun handleClearManualSelection(state: AudioRoutingState): AudioRoutingState {
        if (!state.isManualSelection) return state

        deviceManager.clearManualSelection()

        val newSelected = deviceManager.selectBestDevice(
            availableDevices = state.availableDevices,
            currentDevice = null
        )

        return state.copy(
            selectedDevice = newSelected,
            isManualSelection = false
        )
    }

    private fun handleUpdatePreferredOrder(
        state: AudioRoutingState,
        order: List<Class<out AudioDevice>>
    ): AudioRoutingState {
        // Convert Java Class to KClass for the strategy
        deviceManager.setPreferredOrder(order.map { it.kotlin })

        // Re-select device based on new order if not manually selected
        if (!state.isManualSelection && state.isListening) {
            val newSelected = deviceManager.selectBestDevice(
                availableDevices = state.availableDevices,
                currentDevice = null
            )
            return state.copy(selectedDevice = newSelected)
        }

        return state
    }

    private fun handleEnumerateDevices(state: AudioRoutingState): AudioRoutingState {
        // This is triggered by external device detection
        // The actual device list update happens via specific connect/disconnect events
        return state
    }

    private fun handleInitializeDevices(
        state: AudioRoutingState,
        devices: List<AudioDevice>,
        selectedDevice: AudioDevice?
    ): AudioRoutingState {
        if (!state.isListening) return state

        // Determine wired headset status from device list
        val hasWiredHeadset = devices.any { it is AudioDevice.WiredHeadset }
        val bluetoothDevice = devices.filterIsInstance<AudioDevice.BluetoothHeadset>().firstOrNull()

        return state.copy(
            availableDevices = devices,
            selectedDevice = selectedDevice,
            wiredHeadsetConnected = hasWiredHeadset,
            activeBluetoothDevice = bluetoothDevice
        )
    }
}
