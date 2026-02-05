package com.sceyt.audiorouting.internal

import android.Manifest
import android.content.Context
import android.media.AudioManager
import com.sceyt.audiorouting.AudioDevice
import com.sceyt.audiorouting.AudioRouter
import com.sceyt.audiorouting.AudioRouterConfig
import com.sceyt.audiorouting.AudioRouterListener
import com.sceyt.audiorouting.RoutingState
import com.sceyt.audiorouting.internal.bluetooth.BluetoothHandler
import com.sceyt.audiorouting.internal.bluetooth.BluetoothScoManager
import com.sceyt.audiorouting.internal.device.AudioDeviceManager
import com.sceyt.audiorouting.internal.device.DevicePriorityManager
import com.sceyt.audiorouting.internal.state.AudioRoutingEvent
import com.sceyt.audiorouting.internal.state.AudioRoutingState
import com.sceyt.audiorouting.internal.state.AudioRoutingStateMachine
import com.sceyt.audiorouting.internal.state.BluetoothScoState
import com.sceyt.audiorouting.internal.wired.WiredHeadsetHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.reflect.KClass

/**
 * Main implementation of [AudioRouter].
 * Coordinates all audio routing components and exposes a clean API.
 */
internal class AudioRouterImpl(
    private val context: Context,
    private val config: AudioRouterConfig
) : AudioRouter {

    private val logger = Logger(enabled = config.loggingEnabled)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // State flows
    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    override val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioDevice?>(null)
    override val selectedDevice: StateFlow<AudioDevice?> = _selectedDevice.asStateFlow()

    private val _routingState = MutableStateFlow(RoutingState.IDLE)
    override val routingState: StateFlow<RoutingState> = _routingState.asStateFlow()

    private val _isManualSelection = MutableStateFlow(false)
    override val isManualSelection: StateFlow<Boolean> = _isManualSelection.asStateFlow()

    // Listener
    private var listener: AudioRouterListener? = null

    // Audio focus listener
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        logger.d("Audio focus changed: $focusChange")
        // Handle audio focus loss if needed
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Could pause/duck audio here if needed
            }
        }
    }

    // Components
    private val audioDeviceManager = AudioDeviceManager.create(context, logger, audioFocusChangeListener)
    private val priorityManager = DevicePriorityManager(config, logger)

    private val stateMachine = AudioRoutingStateMachine(
        scope = scope,
        config = config,
        logger = logger,
        deviceManager = priorityManager,
        onStateChanged = { oldState, newState -> handleStateChanged(oldState, newState) }
    )

    private val bluetoothHandler = BluetoothHandler(
        context = context,
        scope = scope,
        audioDeviceManager = audioDeviceManager,
        config = config,
        logger = logger,
        onBluetoothDeviceConnected = { device ->
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(device))
        },
        onBluetoothDeviceDisconnected = { device ->
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceDisconnected(device))
        },
        onScoStateChanged = { state ->
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED ->
                    stateMachine.sendEvent(AudioRoutingEvent.BluetoothScoConnected)
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED ->
                    stateMachine.sendEvent(AudioRoutingEvent.BluetoothScoDisconnected)
            }
        }
    )

    private val wiredHeadsetHandler = WiredHeadsetHandler(
        context = context,
        logger = logger,
        onWiredHeadsetConnected = {
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
        },
        onWiredHeadsetDisconnected = {
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetDisconnected)
        }
    )

    override fun start(listener: AudioRouterListener?) {
        this.listener = listener

        if (_routingState.value != RoutingState.IDLE) {
            logger.d("Already started, updating listener only")
            return
        }

        logger.d("Starting AudioRouter")

        // Check permissions
        if (!bluetoothHandler.hasBluetoothPermission()) {
            listener?.onPermissionMissing(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    Manifest.permission.BLUETOOTH_CONNECT
                } else {
                    Manifest.permission.BLUETOOTH
                }
            )
        }

        // Start handlers
        bluetoothHandler.start()
        wiredHeadsetHandler.start()

        // Send start event to state machine
        stateMachine.sendEvent(AudioRoutingEvent.Start)

        // Initialize with default devices
        initializeDevices()
    }

    override fun stop() {
        if (_routingState.value == RoutingState.IDLE) {
            logger.d("Already stopped")
            return
        }

        logger.d("Stopping AudioRouter")

        // Deactivate first if needed
        if (_routingState.value == RoutingState.ACTIVATED) {
            deactivate()
        }

        // Stop handlers
        bluetoothHandler.stop()
        wiredHeadsetHandler.stop()

        // Send stop event
        stateMachine.sendEvent(AudioRoutingEvent.Stop)

        listener = null
    }

    override fun activate() {
        if (_routingState.value == RoutingState.IDLE) {
            logger.w("Cannot activate - router is stopped. Call start() first.")
            return
        }

        if (_routingState.value == RoutingState.ACTIVATED) {
            logger.d("Already activated")
            return
        }

        logger.d("Activating AudioRouter")

        // Cache audio state and request focus
        audioDeviceManager.cacheAudioState()
        audioDeviceManager.setMicrophoneMute(false)

        if (!audioDeviceManager.requestAudioFocus()) {
            logger.w("Failed to acquire audio focus")
        }

        audioDeviceManager.setVoiceCommunicationMode()

        // Send activate event
        stateMachine.sendEvent(AudioRoutingEvent.Activate)

        // Activate the selected device
        _selectedDevice.value?.let { device ->
            activateDevice(device)
        }
    }

    override fun deactivate() {
        if (_routingState.value != RoutingState.ACTIVATED) {
            logger.d("Not activated, nothing to deactivate")
            return
        }

        logger.d("Deactivating AudioRouter")

        // Stop Bluetooth SCO if active
        bluetoothHandler.deactivate()

        // Restore audio state
        audioDeviceManager.restoreAudioState()

        // Send deactivate event
        stateMachine.sendEvent(AudioRoutingEvent.Deactivate)
    }

    override fun selectDevice(device: AudioDevice?) {
        if (device == null) {
            clearManualSelection()
            return
        }

        // Verify device is available
        if (!_availableDevices.value.any { it.id == device.id }) {
            logger.w("Cannot select unavailable device: ${device.name}")
            return
        }

        logger.d("User selected device: ${device.name}")
        priorityManager.setManualSelection(device)
        stateMachine.sendEvent(AudioRoutingEvent.UserSelectDevice(device))
    }

    override fun clearManualSelection() {
        logger.d("Clearing manual selection")
        priorityManager.clearManualSelection()
        stateMachine.sendEvent(AudioRoutingEvent.ClearManualSelection)
    }

    override fun setPreferredDeviceOrder(devices: List<KClass<out AudioDevice>>) {
        priorityManager.setPreferredOrder(devices)
        stateMachine.sendEvent(AudioRoutingEvent.UpdatePreferredOrder(devices.map { it.java }))
    }

    override fun setListener(listener: AudioRouterListener?) {
        this.listener = listener
    }

    override fun refreshDevices() {
        if (_routingState.value == RoutingState.IDLE) {
            logger.d("Cannot refresh devices - router is stopped")
            return
        }
        
        logger.d("Refreshing devices")
        initializeDevices()
    }

    private fun initializeDevices() {
        val devices = mutableListOf<AudioDevice>()

        // Check for connected Bluetooth headset
        bluetoothHandler.getConnectedHeadset()?.let { devices.add(it) }

        // Check for wired headset
        if (wiredHeadsetHandler.isConnected) {
            devices.add(AudioDevice.WiredHeadset())
        }

        // Add earpiece if no wired headset and device has one
        if (!wiredHeadsetHandler.isConnected && audioDeviceManager.hasEarpiece()) {
            devices.add(AudioDevice.Earpiece())
        }

        // Add speakerphone if available
        if (audioDeviceManager.hasSpeakerphone()) {
            devices.add(AudioDevice.Speakerphone())
        }

        // Sort by priority
        val sortedDevices = devices.sortedBy { priorityManager.getPriority(it) }

        // Select best device
        val selected = priorityManager.selectBestDevice(sortedDevices, null)

        logger.d("Initialized devices: ${sortedDevices.map { it.name }}, selected: ${selected?.name}")

        // Send to state machine to sync its internal state
        stateMachine.sendEvent(AudioRoutingEvent.InitializeDevices(sortedDevices, selected))
    }

    private fun handleStateChanged(oldState: AudioRoutingState, newState: AudioRoutingState) {
        logger.d("State changed: ${oldState.routingState} -> ${newState.routingState}")

        // Update public state flows
        if (oldState.routingState != newState.routingState) {
            _routingState.value = newState.routingState
            listener?.onRoutingStateChanged(newState.routingState)
        }

        if (oldState.availableDevices != newState.availableDevices ||
            oldState.selectedDevice != newState.selectedDevice) {
            _availableDevices.value = newState.availableDevices
            _selectedDevice.value = newState.selectedDevice
            listener?.onAudioDevicesChanged(newState.availableDevices, newState.selectedDevice)
        }

        if (oldState.isManualSelection != newState.isManualSelection) {
            _isManualSelection.value = newState.isManualSelection
        }

        // Handle device activation when in ACTIVATED state
        if (newState.isActivated && oldState.selectedDevice != newState.selectedDevice) {
            newState.selectedDevice?.let { device ->
                activateDevice(device)
            }
        }

        // Handle Bluetooth SCO failure
        if (newState.bluetoothScoState is BluetoothScoState.Failed) {
            val failedState = newState.bluetoothScoState
            if (failedState.retryCount >= config.scoRetryCount) {
                val btDevice = oldState.selectedDevice as? AudioDevice.BluetoothHeadset
                if (btDevice != null) {
                    listener?.onBluetoothScoConnectionFailed(btDevice, newState.selectedDevice)
                }
            }
        }
    }

    private fun activateDevice(device: AudioDevice) {
        logger.d("Activating device: ${device.name}")

        when (device) {
            is AudioDevice.BluetoothHeadset -> {
                bluetoothHandler.activate { result ->
                    when (result) {
                        is BluetoothScoManager.ScoResult.Failed -> {
                            stateMachine.sendEvent(AudioRoutingEvent.BluetoothScoFailed(result.reason))
                        }
                        is BluetoothScoManager.ScoResult.Connected -> {
                            // Already handled via SCO state events
                        }
                        is BluetoothScoManager.ScoResult.Cancelled -> {
                            // Operation was cancelled, no action needed
                        }
                    }
                }
            }
            else -> {
                audioDeviceManager.activateDevice(device)
            }
        }
    }

    /**
     * Cleans up resources. Call this when the router is no longer needed.
     */
    @Suppress("unused")
    fun destroy() {
        stop()
        stateMachine.close()
        scope.cancel()
    }
}
