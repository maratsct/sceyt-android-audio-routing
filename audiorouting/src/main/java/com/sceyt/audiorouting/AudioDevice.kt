package com.sceyt.audiorouting

/**
 * Represents an audio device that can be used for call audio routing.
 * This sealed class hierarchy defines all supported audio device types.
 */
sealed class AudioDevice(
    /** The display name of the audio device. */
    val name: String,

    /** A unique identifier for this audio device. */
    val id: String
) {
    /**
     * Represents a Bluetooth headset device (HFP/HSP profile).
     *
     * @property name The friendly name of the Bluetooth device.
     * @property id A unique identifier combining type and address.
     * @property address The Bluetooth MAC address of the device.
     */
    data class BluetoothHeadset(
        val deviceName: String = "Bluetooth",
        val deviceId: String = "bluetooth",
        val address: String = ""
    ) : AudioDevice(name = deviceName, id = deviceId) {
        constructor(deviceName: String, address: String) : this(
            deviceName = deviceName,
            deviceId = "bluetooth_$address",
            address = address
        )
    }

    /**
     * Represents a wired headset (3.5mm jack or USB-C audio).
     */
    data class WiredHeadset(
        val deviceName: String = "Wired Headset",
        val deviceId: String = "wired_headset"
    ) : AudioDevice(name = deviceName, id = deviceId)

    /**
     * Represents the device's built-in earpiece speaker.
     */
    data class Earpiece(
        val deviceName: String = "Earpiece",
        val deviceId: String = "earpiece"
    ) : AudioDevice(name = deviceName, id = deviceId)

    /**
     * Represents the device's built-in speakerphone.
     */
    data class Speakerphone(
        val deviceName: String = "Speakerphone",
        val deviceId: String = "speakerphone"
    ) : AudioDevice(name = deviceName, id = deviceId)
}
