package com.sceyt.audiorouting.internal.bluetooth

import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile

/**
 * Tracks the state of Bluetooth headset connections.
 */
internal class BluetoothStateTracker {

    /**
     * Represents the connection state of a Bluetooth headset.
     */
    sealed class HeadsetState {
        object Disconnected : HeadsetState()
        object Connected : HeadsetState()
        object AudioActivating : HeadsetState()
        object AudioActivated : HeadsetState()
        data class AudioActivationError(val reason: String) : HeadsetState()

        val isConnected: Boolean
            get() = this != Disconnected

        val isAudioActive: Boolean
            get() = this == AudioActivated
    }

    private var _headsetState: HeadsetState = HeadsetState.Disconnected

    val headsetState: HeadsetState
        get() = _headsetState

    val isConnected: Boolean
        get() = _headsetState.isConnected

    val isAudioActive: Boolean
        get() = _headsetState.isAudioActive

    /**
     * Updates the state based on Bluetooth headset connection state.
     * Uses BluetoothProfile constants: DISCONNECTED=0, CONNECTING=1, CONNECTED=2, DISCONNECTING=3
     */
    fun onConnectionStateChanged(state: Int) {
        _headsetState = when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                // Transition to Connected if not already in a connected state
                if (_headsetState == HeadsetState.Disconnected) {
                    HeadsetState.Connected
                } else {
                    _headsetState
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> HeadsetState.Disconnected
            else -> _headsetState // CONNECTING, DISCONNECTING - keep current state
        }
    }

    /**
     * Updates the state based on Bluetooth audio connection state.
     * Uses BluetoothHeadset audio state constants.
     */
    fun onAudioStateChanged(state: Int) {
        _headsetState = when (state) {
            BluetoothHeadset.STATE_AUDIO_CONNECTED -> HeadsetState.AudioActivated
            BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> {
                if (_headsetState.isConnected) {
                    HeadsetState.Connected
                } else {
                    HeadsetState.Disconnected
                }
            }
            else -> _headsetState
        }
    }

    /**
     * Sets the state to audio activating (SCO connecting).
     */
    fun setAudioActivating() {
        if (_headsetState.isConnected) {
            _headsetState = HeadsetState.AudioActivating
        }
    }

    /**
     * Sets the state to audio activation error.
     */
    fun setAudioActivationError(reason: String) {
        _headsetState = HeadsetState.AudioActivationError(reason)
    }

    /**
     * Checks if there was an audio activation error.
     */
    fun hasActivationError(): Boolean {
        return _headsetState is HeadsetState.AudioActivationError
    }

    /**
     * Resets the state to disconnected.
     */
    fun reset() {
        _headsetState = HeadsetState.Disconnected
    }
}
