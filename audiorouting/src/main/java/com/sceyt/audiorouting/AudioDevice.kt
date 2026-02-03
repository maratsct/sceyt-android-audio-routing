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
     * Represents a Bluetooth LE Audio device (Android 13+).
     * Supports both headsets and speakers via LE Audio profile.
     *
     * @property name The friendly name of the BLE device.
     * @property id A unique identifier combining type and address.
     * @property address The Bluetooth MAC address of the device.
     */
    data class BleHeadset(
        val deviceName: String = "BLE Audio",
        val deviceId: String = "ble_headset",
        val address: String = ""
    ) : AudioDevice(name = deviceName, id = deviceId) {
        constructor(deviceName: String, address: String) : this(
            deviceName = deviceName,
            deviceId = "ble_$address",
            address = address
        )
    }

    /**
     * Represents a Bluetooth hearing aid device.
     * Special handling for accessibility.
     *
     * @property name The friendly name of the hearing aid.
     * @property id A unique identifier combining type and address.
     * @property address The Bluetooth MAC address of the device.
     */
    data class HearingAid(
        val deviceName: String = "Hearing Aid",
        val deviceId: String = "hearing_aid",
        val address: String = ""
    ) : AudioDevice(name = deviceName, id = deviceId) {
        constructor(deviceName: String, address: String) : this(
            deviceName = deviceName,
            deviceId = "hearing_aid_$address",
            address = address
        )
    }

    /**
     * Represents a wired headset (3.5mm jack or USB-C analog audio).
     */
    data class WiredHeadset(
        val deviceName: String = "Wired Headset",
        val deviceId: String = "wired_headset"
    ) : AudioDevice(name = deviceName, id = deviceId)

    /**
     * Represents a USB audio device (USB-C digital audio, USB DAC, etc.).
     */
    data class UsbHeadset(
        val deviceName: String = "USB Audio",
        val deviceId: String = "usb_headset"
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
