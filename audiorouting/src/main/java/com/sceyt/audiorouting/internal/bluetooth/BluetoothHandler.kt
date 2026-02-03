package com.sceyt.audiorouting.internal.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.sceyt.audiorouting.AudioDevice
import com.sceyt.audiorouting.AudioRouterConfig
import com.sceyt.audiorouting.internal.Logger
import com.sceyt.audiorouting.internal.device.AudioDeviceManager
import kotlinx.coroutines.CoroutineScope

/**
 * Handles Bluetooth headset (HFP/HSP profile) connection and audio routing.
 * Manages SCO (Synchronous Connection-Oriented) links for real-time voice audio.
 * 
 * Note: BLE Audio and Hearing Aid devices are detected via AudioDeviceManager,
 * not through this handler, as they use different connection mechanisms.
 */
internal class BluetoothHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val audioDeviceManager: AudioDeviceManager,
    private val config: AudioRouterConfig,
    private val logger: Logger,
    private val onBluetoothDeviceConnected: (AudioDevice.BluetoothHeadset) -> Unit,
    private val onBluetoothDeviceDisconnected: (AudioDevice.BluetoothHeadset) -> Unit,
    private val onScoStateChanged: (Int) -> Unit
) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var headsetProxy: BluetoothHeadset? = null
    private var isReceiverRegistered = false
    private var isStarted = false

    val stateTracker = BluetoothStateTracker()
    var scoManager: BluetoothScoManager? = null
        private set

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = proxy as BluetoothHeadset
                logger.d("Bluetooth headset service connected")

                // Check for already connected devices
                if (hasBluetoothPermission()) {
                    proxy.connectedDevices.forEach { device ->
                        logger.d("Found connected Bluetooth device: ${device.name}")
                        // Update state tracker BEFORE notifying callback
                        stateTracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
                        handleDeviceConnected(device)
                    }
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                logger.d("Bluetooth headset service disconnected")
                headsetProxy = null
                stateTracker.reset()
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    handleConnectionStateChanged(intent)
                }
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                    handleAudioStateChanged(intent)
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    handleScoAudioStateChanged(intent)
                }
            }
        }
    }

    /**
     * Starts listening for Bluetooth headset events.
     */
    fun start() {
        if (isStarted) {
            logger.d("BluetoothHandler already started")
            return
        }

        if (bluetoothAdapter == null) {
            logger.w("Bluetooth not supported on this device")
            return
        }

        if (!hasBluetoothPermission()) {
            logger.w("Bluetooth permission not granted")
            return
        }

        scoManager = BluetoothScoManager(scope, audioDeviceManager, config, logger)

        // Get the headset profile proxy
        bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)

        // Register broadcast receivers
        registerReceivers()

        isStarted = true
        logger.d("BluetoothHandler started")
    }

    /**
     * Stops listening for Bluetooth headset events.
     */
    fun stop() {
        if (!isStarted) return

        scoManager?.cancelPendingOperations()
        unregisterReceivers()

        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, headsetProxy)
        headsetProxy = null
        stateTracker.reset()
        scoManager = null

        isStarted = false
        logger.d("BluetoothHandler stopped")
    }

    /**
     * Activates Bluetooth audio (starts SCO).
     */
    fun activate(onResult: (BluetoothScoManager.ScoResult) -> Unit) {
        val manager = scoManager
        if (manager == null) {
            logger.w("Cannot activate Bluetooth - not started")
            onResult(BluetoothScoManager.ScoResult.Failed("Bluetooth not started", 0))
            return
        }
        
        if (!stateTracker.isConnected) {
            logger.w("Cannot activate Bluetooth - no device connected")
            onResult(BluetoothScoManager.ScoResult.Failed("No device connected", 0))
            return
        }

        stateTracker.setAudioActivating()
        manager.startScoAsync(onResult)
    }

    /**
     * Deactivates Bluetooth audio (stops SCO).
     */
    fun deactivate() {
        scoManager?.disableSco()
    }

    /**
     * Gets the current connected Bluetooth headset, if any.
     */
    @SuppressLint("MissingPermission")
    fun getConnectedHeadset(): AudioDevice.BluetoothHeadset? {
        if (!hasBluetoothPermission()) return null

        return headsetProxy?.connectedDevices?.firstOrNull()?.let { device ->
            AudioDevice.BluetoothHeadset(
                deviceName = device.name ?: "Bluetooth",
                address = device.address
            )
        }
    }

    /**
     * Checks if Bluetooth permissions are granted.
     */
    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun registerReceivers() {
        if (isReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(bluetoothReceiver, filter)
        }

        isReceiverRegistered = true
    }

    private fun unregisterReceivers() {
        if (!isReceiverRegistered) return

        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: IllegalArgumentException) {
            logger.w("Receiver not registered: ${e.message}")
        }

        isReceiverRegistered = false
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionStateChanged(intent: Intent) {
        val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED)
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        if (device == null || !isHeadsetDevice(device)) return
        if (!hasBluetoothPermission()) return

        // BluetoothProfile states: DISCONNECTED=0, CONNECTING=1, CONNECTED=2, DISCONNECTING=3
        val stateName = when (state) {
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "UNKNOWN($state)"
        }
        logger.d("Bluetooth connection state changed: device=${device.name}, state=$stateName")

        // Update state tracker BEFORE notifying callbacks
        stateTracker.onConnectionStateChanged(state)

        when (state) {
            BluetoothProfile.STATE_CONNECTED -> handleDeviceConnected(device)
            BluetoothProfile.STATE_DISCONNECTED -> handleDeviceDisconnected(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleAudioStateChanged(intent: Intent) {
        val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_AUDIO_DISCONNECTED)
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        if (!hasBluetoothPermission()) return

        logger.d("Bluetooth audio state changed: device=${device?.name}, state=$state")
        stateTracker.onAudioStateChanged(state)
    }

    private fun handleScoAudioStateChanged(intent: Intent) {
        val state = intent.getIntExtra(
            AudioManager.EXTRA_SCO_AUDIO_STATE,
            AudioManager.SCO_AUDIO_STATE_DISCONNECTED
        )

        logger.d("SCO audio state changed: $state")
        scoManager?.onScoAudioStateChanged(state)
        onScoStateChanged(state)
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceConnected(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) return

        val audioDevice = AudioDevice.BluetoothHeadset(
            deviceName = device.name ?: "Bluetooth",
            address = device.address
        )
        onBluetoothDeviceConnected(audioDevice)
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceDisconnected(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) return

        val audioDevice = AudioDevice.BluetoothHeadset(
            deviceName = device.name ?: "Bluetooth",
            address = device.address
        )
        onBluetoothDeviceDisconnected(audioDevice)
    }

    @SuppressLint("MissingPermission")
    private fun isHeadsetDevice(device: BluetoothDevice): Boolean {
        if (!hasBluetoothPermission()) return false

        val deviceClass = device.bluetoothClass?.deviceClass ?: return true

        return deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ||
               deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
               deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
               deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
               deviceClass == BluetoothClass.Device.Major.UNCATEGORIZED
    }
}
