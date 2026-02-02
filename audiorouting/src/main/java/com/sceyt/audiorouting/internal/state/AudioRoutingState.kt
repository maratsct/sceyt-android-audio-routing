package com.sceyt.audiorouting.internal.state

import com.sceyt.audiorouting.AudioDevice
import com.sceyt.audiorouting.RoutingState

/**
 * Internal state representation for the audio routing state machine.
 */
internal data class AudioRoutingState(
    val routingState: RoutingState = RoutingState.STOPPED,
    val availableDevices: List<AudioDevice> = emptyList(),
    val selectedDevice: AudioDevice? = null,
    val isManualSelection: Boolean = false,
    val bluetoothScoState: BluetoothScoState = BluetoothScoState.Disconnected,
    val activeBluetoothDevice: AudioDevice.BluetoothHeadset? = null,
    val wiredHeadsetConnected: Boolean = false
) {
    /**
     * Returns true if the router is in a state where device changes should be processed.
     */
    val isListening: Boolean
        get() = routingState != RoutingState.STOPPED

    /**
     * Returns true if audio is actively being routed.
     */
    val isActivated: Boolean
        get() = routingState == RoutingState.ACTIVATED

    /**
     * Returns true if Bluetooth SCO is connecting or connected.
     */
    val isBluetoothScoActive: Boolean
        get() = bluetoothScoState == BluetoothScoState.Connected ||
                bluetoothScoState == BluetoothScoState.Connecting
}

/**
 * Represents the state of Bluetooth SCO (Synchronous Connection-Oriented) audio.
 */
internal sealed class BluetoothScoState {
    object Disconnected : BluetoothScoState()
    object Connecting : BluetoothScoState()
    object Connected : BluetoothScoState()
    data class Failed(val reason: String, val retryCount: Int) : BluetoothScoState()
}
