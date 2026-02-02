package com.sceyt.audiorouting.internal.state

import com.sceyt.audiorouting.AudioDevice
import com.sceyt.audiorouting.AudioRouterConfig
import com.sceyt.audiorouting.RoutingState
import com.sceyt.audiorouting.internal.Logger
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
    private val scope: CoroutineScope,
    private val config: AudioRouterConfig,
    private val logger: Logger,
    private val onStateChanged: suspend (AudioRoutingState, AudioRoutingState) -> Unit
) {
    private val _state = MutableStateFlow(AudioRoutingState())
    val state: StateFlow<AudioRoutingState> = _state.asStateFlow()

    private val eventChannel = Channel<AudioRoutingEvent>(Channel.UNLIMITED)

    // Preferred device order (can be updated at runtime)
    private var preferredOrder: List<Class<out AudioDevice>> = config.preferredDeviceOrder.map { it.java }

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
            is AudioRoutingEvent.BluetoothDeviceConnected -> handleBluetoothConnected(oldState, event.device)
            is AudioRoutingEvent.BluetoothDeviceDisconnected -> handleBluetoothDisconnected(oldState, event.device)
            is AudioRoutingEvent.WiredHeadsetConnected -> handleWiredHeadsetConnected(oldState)
            is AudioRoutingEvent.WiredHeadsetDisconnected -> handleWiredHeadsetDisconnected(oldState)
            is AudioRoutingEvent.BluetoothScoConnected -> handleScoConnected(oldState)
            is AudioRoutingEvent.BluetoothScoDisconnected -> handleScoDisconnected(oldState)
            is AudioRoutingEvent.BluetoothScoFailed -> handleScoFailed(oldState, event.reason)
            is AudioRoutingEvent.UserSelectDevice -> handleUserSelectDevice(oldState, event.device)
            is AudioRoutingEvent.ClearManualSelection -> handleClearManualSelection(oldState)
            is AudioRoutingEvent.UpdatePreferredOrder -> handleUpdatePreferredOrder(oldState, event.order)
            is AudioRoutingEvent.EnumerateDevices -> handleEnumerateDevices(oldState)
        }

        if (newState != oldState) {
            _state.value = newState
            onStateChanged(oldState, newState)
        }
    }

    private fun handleStart(state: AudioRoutingState): AudioRoutingState {
        if (state.routingState != RoutingState.STOPPED) {
            logger.d("Ignoring start() - already in state ${state.routingState}")
            return state
        }
        return state.copy(routingState = RoutingState.STARTED)
    }

    private fun handleStop(state: AudioRoutingState): AudioRoutingState {
        if (state.routingState == RoutingState.STOPPED) {
            logger.d("Ignoring stop() - already stopped")
            return state
        }
        return AudioRoutingState() // Reset to initial state
    }

    private fun handleActivate(state: AudioRoutingState): AudioRoutingState {
        return when (state.routingState) {
            RoutingState.STOPPED -> {
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

        val newDevices = updateDeviceList(state, bluetoothDevice = device)
        val newSelected = selectBestDevice(
            available = newDevices,
            current = state.selectedDevice,
            isManualSelection = state.isManualSelection,
            newDevice = device
        )

        return state.copy(
            availableDevices = newDevices,
            selectedDevice = newSelected,
            activeBluetoothDevice = device
        )
    }

    private fun handleBluetoothDisconnected(
        state: AudioRoutingState,
        device: AudioDevice.BluetoothHeadset
    ): AudioRoutingState {
        if (!state.isListening) return state

        val newDevices = state.availableDevices.filterNot { it is AudioDevice.BluetoothHeadset }
        val needsNewSelection = state.selectedDevice is AudioDevice.BluetoothHeadset
        val newSelected = if (needsNewSelection) {
            selectBestDevice(newDevices, null, isManualSelection = false)
        } else {
            state.selectedDevice
        }

        // Clear manual selection if the manually selected BT device was disconnected
        val clearManual = state.isManualSelection && state.selectedDevice is AudioDevice.BluetoothHeadset

        return state.copy(
            availableDevices = newDevices,
            selectedDevice = newSelected,
            activeBluetoothDevice = null,
            bluetoothScoState = BluetoothScoState.Disconnected,
            isManualSelection = if (clearManual) false else state.isManualSelection
        )
    }

    private fun handleWiredHeadsetConnected(state: AudioRoutingState): AudioRoutingState {
        if (!state.isListening) return state

        val newDevices = updateDeviceList(state, wiredHeadsetConnected = true)
        val wiredHeadset = AudioDevice.WiredHeadset()
        val newSelected = selectBestDevice(
            available = newDevices,
            current = state.selectedDevice,
            isManualSelection = state.isManualSelection,
            newDevice = wiredHeadset
        )

        return state.copy(
            availableDevices = newDevices,
            selectedDevice = newSelected,
            wiredHeadsetConnected = true
        )
    }

    private fun handleWiredHeadsetDisconnected(state: AudioRoutingState): AudioRoutingState {
        if (!state.isListening) return state

        val newDevices = updateDeviceList(state, wiredHeadsetConnected = false)
        val needsNewSelection = state.selectedDevice is AudioDevice.WiredHeadset
        val newSelected = if (needsNewSelection) {
            selectBestDevice(newDevices, null, isManualSelection = false)
        } else {
            state.selectedDevice
        }

        // Clear manual selection if the manually selected wired headset was disconnected
        val clearManual = state.isManualSelection && state.selectedDevice is AudioDevice.WiredHeadset

        return state.copy(
            availableDevices = newDevices,
            selectedDevice = newSelected,
            wiredHeadsetConnected = false,
            isManualSelection = if (clearManual) false else state.isManualSelection
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
            val newSelected = selectBestDevice(
                available = state.availableDevices.filterNot { it is AudioDevice.BluetoothHeadset },
                current = null,
                isManualSelection = false
            )
            state.copy(
                bluetoothScoState = BluetoothScoState.Failed(reason, retryCount),
                selectedDevice = newSelected,
                isManualSelection = false // Clear manual selection on BT failure
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

        return state.copy(
            selectedDevice = device,
            isManualSelection = true
        )
    }

    private fun handleClearManualSelection(state: AudioRoutingState): AudioRoutingState {
        if (!state.isManualSelection) return state

        val newSelected = selectBestDevice(
            available = state.availableDevices,
            current = null,
            isManualSelection = false
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
        preferredOrder = order

        // Re-select device based on new order if not manually selected
        if (!state.isManualSelection && state.isListening) {
            val newSelected = selectBestDevice(
                available = state.availableDevices,
                current = null,
                isManualSelection = false
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

    /**
     * Updates the device list based on current state.
     */
    private fun updateDeviceList(
        state: AudioRoutingState,
        bluetoothDevice: AudioDevice.BluetoothHeadset? = state.activeBluetoothDevice,
        wiredHeadsetConnected: Boolean = state.wiredHeadsetConnected
    ): List<AudioDevice> {
        val devices = mutableListOf<AudioDevice>()

        // Add devices in priority order
        preferredOrder.forEach { deviceClass ->
            when (deviceClass) {
                AudioDevice.BluetoothHeadset::class.java -> {
                    bluetoothDevice?.let { devices.add(it) }
                }
                AudioDevice.WiredHeadset::class.java -> {
                    if (wiredHeadsetConnected) {
                        devices.add(AudioDevice.WiredHeadset())
                    }
                }
                AudioDevice.Earpiece::class.java -> {
                    // Earpiece is hidden when wired headset is connected
                    if (!wiredHeadsetConnected) {
                        devices.add(AudioDevice.Earpiece())
                    }
                }
                AudioDevice.Speakerphone::class.java -> {
                    devices.add(AudioDevice.Speakerphone())
                }
            }
        }

        return devices
    }

    /**
     * Selects the best device based on priority and current state.
     */
    private fun selectBestDevice(
        available: List<AudioDevice>,
        current: AudioDevice?,
        isManualSelection: Boolean,
        newDevice: AudioDevice? = null
    ): AudioDevice? {
        if (available.isEmpty()) return null

        // If manual selection is active and current device is still available, keep it
        if (isManualSelection && current != null) {
            val currentStillAvailable = available.any { it.id == current.id }
            if (currentStillAvailable) {
                return current
            }
        }

        // If a new device connected and it has higher priority than current, switch to it
        if (newDevice != null && !isManualSelection) {
            val newDevicePriority = getPriority(newDevice)
            val currentPriority = current?.let { getPriority(it) } ?: Int.MAX_VALUE
            if (newDevicePriority < currentPriority) {
                return newDevice
            }
        }

        // Return current if still available
        if (current != null && available.any { it.id == current.id }) {
            return current
        }

        // Select highest priority available device
        return available.minByOrNull { getPriority(it) }
    }

    /**
     * Returns the priority of a device (lower is higher priority).
     */
    private fun getPriority(device: AudioDevice): Int {
        val deviceClass = device::class.java
        val index = preferredOrder.indexOfFirst { it.isAssignableFrom(deviceClass) }
        return if (index >= 0) index else Int.MAX_VALUE
    }
}
