package com.sceyt.audiorouting

/**
 * Listener interface for audio routing events.
 * Provides callback-based alternative to StateFlow observation.
 */
interface AudioRouterListener {
    /**
     * Called when the list of available audio devices changes.
     *
     * @param devices The current list of available audio devices.
     * @param selectedDevice The currently selected audio device, or null if none.
     */
    fun onAudioDevicesChanged(devices: List<AudioDevice>, selectedDevice: AudioDevice?)

    /**
     * Called when the routing state changes.
     *
     * @param state The new routing state.
     */
    fun onRoutingStateChanged(state: RoutingState) {}

    /**
     * Called when Bluetooth SCO connection fails after all retries.
     *
     * @param device The Bluetooth device that failed to connect.
     * @param fallbackDevice The device that was selected as fallback, or null.
     */
    fun onBluetoothScoConnectionFailed(
        device: AudioDevice.BluetoothHeadset,
        fallbackDevice: AudioDevice?
    ) {}

    /**
     * Called when a permission required for audio routing is missing.
     *
     * @param permission The missing permission (e.g., BLUETOOTH_CONNECT).
     */
    fun onPermissionMissing(permission: String) {}
}
