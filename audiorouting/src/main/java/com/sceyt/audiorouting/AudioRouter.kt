package com.sceyt.audiorouting

import android.content.Context
import com.sceyt.audiorouting.internal.AudioRouterImpl
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass

/**
 * Main interface for managing audio routing in VoIP/call applications.
 *
 * AudioRouter provides:
 * - Real-time detection of available audio devices (Bluetooth, wired headset, earpiece, speaker)
 * - Automatic device selection based on configurable priority order
 * - Manual device selection with lock (prevents auto-switching)
 * - Bluetooth SCO lifecycle management with retries and fallback
 * - Audio focus management for voice communication
 *
 * Usage:
 * ```kotlin
 * val router = AudioRouter.create(context)
 *
 * // Observe available devices and selection
 * lifecycleScope.launch {
 *     router.availableDevices.collect { devices ->
 *         // Update UI with available devices
 *     }
 * }
 *
 * // Start listening for device changes
 * router.start()
 *
 * // When call starts, activate audio routing
 * router.activate()
 *
 * // User selects a specific device
 * router.selectDevice(device)
 *
 * // When call ends
 * router.deactivate()
 * router.stop()
 * ```
 */
interface AudioRouter {
    /**
     * StateFlow of currently available audio devices.
     * Emits whenever devices are connected or disconnected.
     */
    val availableDevices: StateFlow<List<AudioDevice>>

    /**
     * StateFlow of the currently selected audio device.
     * May be null if no devices are available.
     */
    val selectedDevice: StateFlow<AudioDevice?>

    /**
     * StateFlow of the current routing state.
     */
    val routingState: StateFlow<RoutingState>

    /**
     * Whether the user has manually selected a device.
     * When true, automatic device switching is disabled.
     */
    val isManualSelection: StateFlow<Boolean>

    /**
     * Starts listening for audio device changes.
     * Call this when preparing for a call or when the app needs to track audio devices.
     *
     * @param listener Optional callback listener for audio events.
     *                 Can be used alongside StateFlow observation.
     */
    fun start(listener: AudioRouterListener? = null)

    /**
     * Stops listening for audio device changes.
     * Automatically calls [deactivate] if currently activated.
     * Call this when audio routing is no longer needed.
     */
    fun stop()

    /**
     * Activates audio routing to the selected device.
     * This acquires audio focus and starts routing audio.
     * Call this when a call actually starts.
     *
     * @throws IllegalStateException if called when state is STOPPED
     */
    fun activate()

    /**
     * Deactivates audio routing and releases audio focus.
     * Call this when a call ends.
     */
    fun deactivate()

    /**
     * Manually selects an audio device for routing.
     * Sets [isManualSelection] to true, preventing automatic device switching
     * when higher-priority devices connect.
     *
     * @param device The device to select. Must be in [availableDevices].
     *               If null or not available, this call is ignored.
     */
    fun selectDevice(device: AudioDevice?)

    /**
     * Clears the manual device selection.
     * Resumes automatic priority-based device selection.
     * The router will immediately select the highest-priority available device.
     */
    fun clearManualSelection()

    /**
     * Updates the preferred device order for automatic selection.
     * Devices earlier in the list have higher priority.
     *
     * @param devices The new priority order. Must not contain duplicates.
     * @throws IllegalArgumentException if the list contains duplicates or is empty
     */
    fun setPreferredDeviceOrder(devices: List<KClass<out AudioDevice>>)

    /**
     * Sets or removes the audio router listener.
     *
     * @param listener The listener to set, or null to remove.
     */
    fun setListener(listener: AudioRouterListener?)

    companion object {
        /**
         * Creates a new AudioRouter instance.
         *
         * @param context Android context (application context will be used internally)
         * @param config Configuration options for the router
         * @return A new AudioRouter instance
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            context: Context,
            config: AudioRouterConfig = AudioRouterConfig()
        ): AudioRouter {
            return AudioRouterImpl(context.applicationContext, config)
        }
    }
}
