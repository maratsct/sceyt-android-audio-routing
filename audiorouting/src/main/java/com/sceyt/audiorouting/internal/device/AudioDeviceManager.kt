package com.sceyt.audiorouting.internal.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.os.Build
import com.sceyt.audiorouting.AudioDevice
import com.sceyt.audiorouting.internal.Logger

/**
 * Manages audio device settings, audio focus, and routing.
 * Handles API level differences between Android versions.
 */
internal class AudioDeviceManager(
    private val context: Context,
    private val audioManager: AudioManager,
    private val logger: Logger,
    private val audioFocusChangeListener: OnAudioFocusChangeListener
) {
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var savedIsMicrophoneMuted: Boolean = false
    private var savedSpeakerphoneEnabled: Boolean = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false

    /**
     * Checks if the device has an earpiece.
     */
    @SuppressLint("NewApi")
    fun hasEarpiece(): Boolean {
        return if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
        } else {
            false
        }
    }

    /**
     * Checks if the device has a speakerphone.
     */
    @SuppressLint("NewApi")
    fun hasSpeakerphone(): Boolean {
        return if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
        } else {
            true // Assume speaker exists on older devices
        }
    }

    /**
     * Checks if a wired headset is connected.
     */
    @SuppressLint("NewApi")
    fun hasWiredHeadset(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
    }

    /**
     * Checks if a USB audio device is connected.
     */
    @SuppressLint("NewApi")
    fun hasUsbAudio(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
    }

    /**
     * Checks if a BLE audio device is connected (Android 12+).
     */
    @SuppressLint("NewApi")
    fun hasBleAudio(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
    }

    /**
     * Checks if a hearing aid is connected.
     */
    @SuppressLint("NewApi")
    fun hasHearingAid(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_HEARING_AID
        }
    }

    /**
     * Gets all connected audio output devices with their types.
     */
    @SuppressLint("NewApi")
    fun getConnectedDevices(): List<AudioDevice> {
        val devices = mutableListOf<AudioDevice>()
        
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { deviceInfo ->
            when (deviceInfo.type) {
                AudioDeviceInfo.TYPE_HEARING_AID -> {
                    devices.add(AudioDevice.HearingAid(
                        deviceName = deviceInfo.productName?.toString() ?: "Hearing Aid"
                    ))
                }
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        devices.add(AudioDevice.BleHeadset(
                            deviceName = deviceInfo.productName?.toString() ?: "BLE Audio"
                        ))
                    }
                }
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> {
                    devices.add(AudioDevice.UsbHeadset(
                        deviceName = deviceInfo.productName?.toString() ?: "USB Audio"
                    ))
                }
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> {
                    devices.add(AudioDevice.WiredHeadset())
                }
            }
        }
        
        return devices.distinctBy { it.id }
    }

    /**
     * Caches the current audio state before modifying it.
     */
    @SuppressLint("NewApi")
    fun cacheAudioState() {
        savedAudioMode = audioManager.mode
        savedIsMicrophoneMuted = audioManager.isMicrophoneMute

        savedSpeakerphoneEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn
        }

        logger.d("Cached audio state: mode=$savedAudioMode, muted=$savedIsMicrophoneMuted, speaker=$savedSpeakerphoneEnabled")
    }

    /**
     * Restores the previously cached audio state.
     */
    @SuppressLint("NewApi")
    fun restoreAudioState() {
        audioManager.mode = savedAudioMode
        setMicrophoneMute(savedIsMicrophoneMuted)
        enableSpeakerphone(savedSpeakerphoneEnabled)
        abandonAudioFocus()

        logger.d("Restored audio state")
    }

    /**
     * Requests audio focus for voice communication.
     */
    @SuppressLint("NewApi")
    fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) {
            logger.d("Audio focus already acquired")
            return true
        }

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        logger.d("Audio focus request result: $result, hasAudioFocus=$hasAudioFocus")
        return hasAudioFocus
    }

    /**
     * Releases audio focus.
     */
    @SuppressLint("NewApi")
    fun abandonAudioFocus() {
        if (!hasAudioFocus) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }

        hasAudioFocus = false
        logger.d("Audio focus abandoned")
    }

    /**
     * Sets the audio mode for voice communication.
     */
    fun setVoiceCommunicationMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        logger.d("Audio mode set to MODE_IN_COMMUNICATION")
    }

    /**
     * Sets the microphone mute state.
     */
    fun setMicrophoneMute(mute: Boolean) {
        audioManager.isMicrophoneMute = mute
    }

    /**
     * Enables or disables the speakerphone.
     */
    @SuppressLint("NewApi")
    fun enableSpeakerphone(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, enable)
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enable
        }
        logger.d("Speakerphone ${if (enable) "enabled" else "disabled"}")
    }

    /**
     * Routes audio to the earpiece.
     */
    @SuppressLint("NewApi")
    fun enableEarpiece(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, enable)
        } else {
            // On older APIs, disabling speakerphone routes to earpiece
            if (enable) {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
        }
        logger.d("Earpiece ${if (enable) "enabled" else "disabled"}")
    }

    /**
     * Starts Bluetooth SCO audio connection.
     * Note: We use startBluetoothSco() on all API levels because setCommunicationDevice
     * requires the BT SCO device to already be in the available list, which only happens
     * after SCO is established.
     */
    @SuppressLint("NewApi")
    fun startBluetoothSco() {
        // First try the new API on Android 12+ if the device is available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scoDevice = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (scoDevice != null) {
                val success = audioManager.setCommunicationDevice(scoDevice)
                logger.d("setCommunicationDevice(BT_SCO) result: $success")
                if (success) {
                    return
                }
            }
        }

        // Fall back to legacy API
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        logger.d("Bluetooth SCO start requested (legacy API)")
    }

    /**
     * Stops Bluetooth SCO audio connection.
     */
    @SuppressLint("NewApi")
    fun stopBluetoothSco() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Clear communication device if it's BT SCO
            val currentDevice = audioManager.communicationDevice
            if (currentDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                audioManager.clearCommunicationDevice()
                logger.d("Cleared BT SCO communication device")
            }
        }

        // Always call stopBluetoothSco for cleanup
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
        logger.d("Bluetooth SCO stop requested")
    }

    /**
     * Activates audio routing to the specified device.
     */
    @SuppressLint("NewApi")
    fun activateDevice(device: AudioDevice) {
        logger.d("Activating device: ${device.name}")

        when (device) {
            is AudioDevice.BluetoothHeadset -> {
                enableSpeakerphone(false)
                startBluetoothSco()
            }

            is AudioDevice.BleHeadset -> {
                enableSpeakerphone(false)
                stopBluetoothSco()
                enableBleAudio(true)
            }

            is AudioDevice.HearingAid -> {
                enableSpeakerphone(false)
                stopBluetoothSco()
                enableHearingAid(true)
            }

            is AudioDevice.WiredHeadset -> {
                enableSpeakerphone(false)
                stopBluetoothSco()
                // Wired headset is automatically used when connected
            }

            is AudioDevice.UsbHeadset -> {
                enableSpeakerphone(false)
                stopBluetoothSco()
                enableUsbAudio(true)
            }

            is AudioDevice.Earpiece -> {
                enableSpeakerphone(false)
                stopBluetoothSco()
                enableEarpiece(true)
            }

            is AudioDevice.Speakerphone -> {
                stopBluetoothSco()
                enableSpeakerphone(true)
            }
        }
    }

    /**
     * Enables BLE Audio device routing (Android 13+).
     */
    @SuppressLint("NewApi")
    private fun enableBleAudio(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Try BLE headset first, then BLE speaker
            val bleDevice = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
            if (bleDevice != null && enable) {
                val success = audioManager.setCommunicationDevice(bleDevice)
                logger.d("setCommunicationDevice(BLE): $success")
            } else if (!enable) {
                val currentDevice = audioManager.communicationDevice
                if (currentDevice?.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    currentDevice?.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                    audioManager.clearCommunicationDevice()
                }
            }
        }
        logger.d("BLE Audio ${if (enable) "enabled" else "disabled"}")
    }

    /**
     * Enables Hearing Aid device routing.
     */
    @SuppressLint("NewApi")
    private fun enableHearingAid(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setCommunicationDevice(AudioDeviceInfo.TYPE_HEARING_AID, enable)
        }
        logger.d("Hearing Aid ${if (enable) "enabled" else "disabled"}")
    }

    /**
     * Enables USB Audio device routing.
     */
    @SuppressLint("NewApi")
    private fun enableUsbAudio(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Try USB headset first, then generic USB device
            val usbDevice = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }
            if (usbDevice != null && enable) {
                val success = audioManager.setCommunicationDevice(usbDevice)
                logger.d("setCommunicationDevice(USB): $success")
            } else if (!enable) {
                val currentDevice = audioManager.communicationDevice
                if (currentDevice?.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    currentDevice?.type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                    audioManager.clearCommunicationDevice()
                }
            }
        }
        logger.d("USB Audio ${if (enable) "enabled" else "disabled"}")
    }

    /**
     * Sets the communication device on Android 12+.
     */
    @SuppressLint("NewApi")
    private fun setCommunicationDevice(deviceType: Int, enable: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        if (enable) {
            val device = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == deviceType }

            if (device != null) {
                if (audioManager.communicationDevice?.id != device.id) {
                    val success = audioManager.setCommunicationDevice(device)
                    logger.d("setCommunicationDevice(${device.type}): $success")
                }
            } else {
                logger.w("Communication device type $deviceType not available")
            }
        } else {
            if (audioManager.communicationDevice?.type == deviceType) {
                audioManager.clearCommunicationDevice()
                logger.d("Cleared communication device")
            }
        }
    }

    /**
     * Gets the list of available communication devices on Android 12+.
     */
    @SuppressLint("NewApi")
    fun getAvailableCommunicationDevices(): List<AudioDeviceInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            emptyList()
        }
    }

    companion object {
        fun create(
            context: Context,
            logger: Logger,
            audioFocusChangeListener: OnAudioFocusChangeListener
        ): AudioDeviceManager {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return AudioDeviceManager(context, audioManager, logger, audioFocusChangeListener)
        }
    }
}
