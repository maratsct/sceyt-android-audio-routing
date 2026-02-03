package com.sceyt.audiorouting

import kotlin.reflect.KClass

/**
 * Configuration options for the AudioRouter.
 *
 * @property preferredDeviceOrder The priority order for automatic device selection.
 *           Devices earlier in the list have higher priority.
 *           Default order: HearingAid > BluetoothHeadset > BleHeadset > WiredHeadset > UsbHeadset > Earpiece > Speakerphone
 * @property loggingEnabled Whether to enable debug logging. Default is false.
 * @property scoRetryCount Number of times to retry Bluetooth SCO connection. Default is 3.
 * @property scoRetryDelayMs Delay between SCO retry attempts in milliseconds. Default is 500ms.
 * @property scoTimeoutMs Timeout for SCO connection in milliseconds. Default is 5000ms.
 * @property debounceDelayMs Delay for debouncing rapid device connection events. Default is 300ms.
 */
data class AudioRouterConfig(
    val preferredDeviceOrder: List<KClass<out AudioDevice>> = defaultPreferredDeviceOrder,
    val loggingEnabled: Boolean = false,
    val scoRetryCount: Int = 3,
    val scoRetryDelayMs: Long = 500L,
    val scoTimeoutMs: Long = 5000L,
    val debounceDelayMs: Long = 300L
) {
    init {
        require(preferredDeviceOrder.isNotEmpty()) {
            "Preferred device order must not be empty"
        }
        require(preferredDeviceOrder.distinct().size == preferredDeviceOrder.size) {
            "Preferred device order must not contain duplicates"
        }
        require(scoRetryCount >= 0) {
            "SCO retry count must be non-negative"
        }
        require(scoRetryDelayMs > 0) {
            "SCO retry delay must be positive"
        }
        require(scoTimeoutMs > 0) {
            "SCO timeout must be positive"
        }
        require(debounceDelayMs >= 0) {
            "Debounce delay must be non-negative"
        }
    }

    companion object {
        /**
         * Default device priority order for automatic selection.
         * Hearing aids get highest priority for accessibility.
         * Bluetooth devices (classic and LE) are preferred over wired.
         * USB audio is treated similarly to wired headsets.
         */
        val defaultPreferredDeviceOrder: List<KClass<out AudioDevice>> = listOf(
            AudioDevice.HearingAid::class,
            AudioDevice.BluetoothHeadset::class,
            AudioDevice.BleHeadset::class,
            AudioDevice.WiredHeadset::class,
            AudioDevice.UsbHeadset::class,
            AudioDevice.Earpiece::class,
            AudioDevice.Speakerphone::class
        )
    }
}
