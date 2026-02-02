package com.sceyt.audiorouting

/**
 * Represents an audio device that can be used for call audio routing.
 * This sealed class hierarchy defines all supported audio device types.
 */
sealed class AudioDevice {
    /** The display name of the audio device. */
    abstract val name: String
    
    /** A unique identifier for this audio device. */
    abstract val id: String

    /**
     * Represents a Bluetooth headset device (HFP/HSP profile).
     *
     * @property name The friendly name of the Bluetooth device.
     * @property id A unique identifier combining type and address.
     * @property address The Bluetooth MAC address of the device.
     */
    data class BluetoothHeadset(
        override val name: String = "Bluetooth",
        override val id: String = "bluetooth",
        val address: String = ""
    ) : AudioDevice() {
        constructor(name: String, address: String) : this(
            name = name,
            id = "bluetooth_$address",
            address = address
        )
    }

    /**
     * Represents a wired headset (3.5mm jack or USB-C audio).
     */
    data class WiredHeadset(
        override val name: String = "Wired Headset",
        override val id: String = "wired_headset"
    ) : AudioDevice()

    /**
     * Represents the device's built-in earpiece speaker.
     */
    data class Earpiece(
        override val name: String = "Earpiece",
        override val id: String = "earpiece"
    ) : AudioDevice()

    /**
     * Represents the device's built-in speakerphone.
     */
    data class Speakerphone(
        override val name: String = "Speakerphone",
        override val id: String = "speakerphone"
    ) : AudioDevice()
}
