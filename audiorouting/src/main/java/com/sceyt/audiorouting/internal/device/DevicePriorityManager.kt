package com.sceyt.audiorouting.internal.device

import com.sceyt.audiorouting.AudioDevice
import com.sceyt.audiorouting.AudioRouterConfig
import com.sceyt.audiorouting.internal.Logger
import kotlin.reflect.KClass

/**
 * Manages device priority ordering and selection logic.
 * Handles automatic device selection and manual selection locking.
 */
internal class DevicePriorityManager(
    config: AudioRouterConfig,
    private val logger: Logger
) {
    private var preferredOrder: List<KClass<out AudioDevice>> = config.preferredDeviceOrder.toList()
    private var manuallySelectedDevice: AudioDevice? = null
    private var _isManualSelection: Boolean = false

    /**
     * Whether the user has manually selected a device.
     * When true, automatic device switching is disabled.
     */
    val isManualSelection: Boolean
        get() = _isManualSelection

    /**
     * Updates the preferred device order.
     *
     * @param order The new priority order. Devices earlier have higher priority.
     * @throws IllegalArgumentException if the list contains duplicates or is empty.
     */
    fun setPreferredOrder(order: List<KClass<out AudioDevice>>) {
        require(order.isNotEmpty()) { "Preferred device order must not be empty" }
        require(order.distinct().size == order.size) { "Preferred device order must not contain duplicates" }
        preferredOrder = order.toList()
        logger.d("Updated preferred device order: ${order.map { it.simpleName }}")
    }

    /**
     * Gets the current preferred device order.
     */
    fun getPreferredOrder(): List<KClass<out AudioDevice>> = preferredOrder

    /**
     * Selects the best available device based on priority and current state.
     *
     * @param availableDevices List of currently available devices.
     * @param currentDevice The currently selected device, if any.
     * @param newlyConnectedDevice A device that was just connected, if any.
     * @return The best device to select, or null if no devices are available.
     */
    fun selectBestDevice(
        availableDevices: List<AudioDevice>,
        currentDevice: AudioDevice?,
        newlyConnectedDevice: AudioDevice? = null
    ): AudioDevice? {
        if (availableDevices.isEmpty()) {
            logger.d("No devices available")
            return null
        }

        // If manual selection is active and the manually selected device is still available
        if (_isManualSelection && manuallySelectedDevice != null) {
            val manualDeviceAvailable = availableDevices.any { isSameDevice(it, manuallySelectedDevice!!) }
            if (manualDeviceAvailable) {
                logger.d("Keeping manually selected device: ${manuallySelectedDevice?.name}")
                return manuallySelectedDevice
            } else {
                // Manually selected device no longer available, clear manual selection
                logger.d("Manually selected device no longer available, clearing selection")
                clearManualSelection()
            }
        }

        // If a new device connected and has higher priority than current, switch to it
        if (newlyConnectedDevice != null && !_isManualSelection) {
            val newDevicePriority = getPriority(newlyConnectedDevice)
            val currentPriority = currentDevice?.let { getPriority(it) } ?: Int.MAX_VALUE
            
            if (newDevicePriority < currentPriority) {
                logger.d("Switching to newly connected device: ${newlyConnectedDevice.name} (priority $newDevicePriority < $currentPriority)")
                return newlyConnectedDevice
            }
        }

        // If current device is still available, keep it
        if (currentDevice != null && availableDevices.any { isSameDevice(it, currentDevice) }) {
            logger.d("Keeping current device: ${currentDevice.name}")
            return currentDevice
        }

        // Select highest priority available device
        val bestDevice = availableDevices.minByOrNull { getPriority(it) }
        logger.d("Selected highest priority device: ${bestDevice?.name}")
        return bestDevice
    }

    /**
     * Determines if auto-switching should occur when a new device connects.
     *
     * @param newDevice The newly connected device.
     * @param currentDevice The currently selected device.
     * @return True if should auto-switch to the new device.
     */
    fun shouldAutoSwitch(
        newDevice: AudioDevice,
        currentDevice: AudioDevice?
    ): Boolean {
        // Never auto-switch if manual selection is active
        if (_isManualSelection) {
            logger.d("Manual selection active, not auto-switching to ${newDevice.name}")
            return false
        }

        if (currentDevice == null) {
            return true
        }

        val newPriority = getPriority(newDevice)
        val currentPriority = getPriority(currentDevice)

        val shouldSwitch = newPriority < currentPriority
        logger.d("Auto-switch check: ${newDevice.name}($newPriority) vs ${currentDevice.name}($currentPriority) -> $shouldSwitch")
        return shouldSwitch
    }

    /**
     * Determines the fallback device when the current device disconnects.
     *
     * @param availableDevices List of currently available devices.
     * @param disconnectedDevice The device that was disconnected.
     * @return The fallback device, or null if no devices are available.
     */
    fun selectFallbackDevice(
        availableDevices: List<AudioDevice>,
        disconnectedDevice: AudioDevice
    ): AudioDevice? {
        // Filter out the disconnected device
        val remaining = availableDevices.filterNot { isSameDevice(it, disconnectedDevice) }
        
        if (remaining.isEmpty()) {
            logger.d("No fallback devices available")
            return null
        }

        // On disconnect, always select best available regardless of manual selection
        val fallback = remaining.minByOrNull { getPriority(it) }
        logger.d("Selected fallback device: ${fallback?.name}")
        return fallback
    }

    /**
     * Sets the manually selected device.
     * Enables manual selection mode, preventing auto-switching.
     *
     * @param device The device to select, or null to clear selection.
     */
    fun setManualSelection(device: AudioDevice?) {
        if (device == null) {
            clearManualSelection()
            return
        }

        manuallySelectedDevice = device
        _isManualSelection = true
        logger.d("Manual selection set: ${device.name}")
    }

    /**
     * Clears the manual device selection.
     * Resumes automatic priority-based selection.
     */
    fun clearManualSelection() {
        if (_isManualSelection) {
            manuallySelectedDevice = null
            _isManualSelection = false
            logger.d("Manual selection cleared")
        }
    }

    /**
     * Clears manual selection only if the selected device was a Bluetooth device.
     * Used when Bluetooth SCO fails.
     */
    fun clearManualSelectionIfBluetooth() {
        if (_isManualSelection && manuallySelectedDevice is AudioDevice.BluetoothHeadset) {
            clearManualSelection()
        }
    }

    /**
     * Gets the priority of a device based on the preferred order.
     * Lower value = higher priority.
     */
    fun getPriority(device: AudioDevice): Int {
        val deviceClass = device::class
        val index = preferredOrder.indexOfFirst { it == deviceClass }
        return if (index >= 0) index else Int.MAX_VALUE
    }

    /**
     * Checks if two devices are the same (by type and id).
     */
    private fun isSameDevice(a: AudioDevice, b: AudioDevice): Boolean {
        return a.id == b.id
    }
}
