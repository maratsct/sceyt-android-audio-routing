package com.sceyt.audiorouting.internal.state

import com.sceyt.audiorouting.AudioDevice

/**
 * Events that can be processed by the AudioRoutingStateMachine.
 * Events are processed sequentially to prevent race conditions.
 */
internal sealed class AudioRoutingEvent {
    // Lifecycle events
    object Start : AudioRoutingEvent()
    object Stop : AudioRoutingEvent()
    object Activate : AudioRoutingEvent()
    object Deactivate : AudioRoutingEvent()

    // Device connection events
    data class BluetoothDeviceConnected(
        val device: AudioDevice.BluetoothHeadset
    ) : AudioRoutingEvent()

    data class BluetoothDeviceDisconnected(
        val device: AudioDevice.BluetoothHeadset
    ) : AudioRoutingEvent()

    object WiredHeadsetConnected : AudioRoutingEvent()
    object WiredHeadsetDisconnected : AudioRoutingEvent()

    // Bluetooth SCO events
    object BluetoothScoConnected : AudioRoutingEvent()
    object BluetoothScoDisconnected : AudioRoutingEvent()
    data class BluetoothScoFailed(val reason: String) : AudioRoutingEvent()

    // User interaction events
    data class UserSelectDevice(val device: AudioDevice?) : AudioRoutingEvent()
    object ClearManualSelection : AudioRoutingEvent()

    // Configuration events
    data class UpdatePreferredOrder(
        val order: List<Class<out AudioDevice>>
    ) : AudioRoutingEvent()

    // Internal events
    object EnumerateDevices : AudioRoutingEvent()
}
