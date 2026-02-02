package com.sceyt.audiorouting

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented integration tests for AudioRouter.
 * Tests the full router lifecycle on a real device.
 * 
 * Note: BLUETOOTH_CONNECT permission must be granted manually before running tests
 * or via adb: adb shell pm grant <package> android.permission.BLUETOOTH_CONNECT
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AudioRouterInstrumentedTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var audioRouter: AudioRouter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        val config = AudioRouterConfig(loggingEnabled = true)
        audioRouter = AudioRouter.create(context, config)
    }

    @After
    fun tearDown() {
        audioRouter.stop()
        // Reset audio state
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    // ==================== Lifecycle Tests ====================

    @Test
    fun create_returnsNonNullRouter() {
        assertNotNull(audioRouter)
    }

    @Test
    fun initialState_isStopped() {
        assertEquals(RoutingState.IDLE, audioRouter.routingState.value)
    }

    @Test
    fun start_changesStateToStarted() = runBlocking {
        audioRouter.start()
        delay(100) // Allow state to propagate
        assertEquals(RoutingState.STARTED, audioRouter.routingState.value)
    }

    @Test
    fun stop_afterStart_changesStateToStopped() = runBlocking {
        audioRouter.start()
        delay(100)
        audioRouter.stop()
        delay(100)
        assertEquals(RoutingState.IDLE, audioRouter.routingState.value)
    }

    @Test
    fun activate_afterStart_changesStateToActivated() = runBlocking {
        audioRouter.start()
        delay(200) // Wait for initialization
        audioRouter.activate()
        delay(100)
        assertEquals(RoutingState.ACTIVATED, audioRouter.routingState.value)
    }

    @Test
    fun deactivate_afterActivate_changesStateToStarted() = runBlocking {
        audioRouter.start()
        delay(200)
        audioRouter.activate()
        delay(100)
        audioRouter.deactivate()
        delay(100)
        assertEquals(RoutingState.STARTED, audioRouter.routingState.value)
    }

    // ==================== Device Detection Tests ====================

    @Test
    fun start_detectsAvailableDevices() = runBlocking {
        audioRouter.start()
        
        // Wait for device detection
        delay(300)
        
        val devices = audioRouter.availableDevices.value
        assertTrue("Should detect at least one device", devices.isNotEmpty())
    }

    @Test
    fun start_detectsSpeakerphone() = runBlocking {
        audioRouter.start()
        delay(300)
        
        val devices = audioRouter.availableDevices.value
        val hasSpeaker = devices.any { it is AudioDevice.Speakerphone }
        assertTrue("Should detect speakerphone", hasSpeaker)
    }

    @Test
    fun start_selectsDevice() = runBlocking {
        audioRouter.start()
        delay(300)
        
        // Should auto-select a device
        val selected = audioRouter.selectedDevice.value
        assertNotNull("Should have a selected device", selected)
    }

    // ==================== Device Selection Tests ====================

    @Test
    fun selectDevice_speakerphone_succeeds() = runBlocking {
        audioRouter.start()
        delay(500)
        
        // Verify speakerphone is in available devices first
        val devices = audioRouter.availableDevices.value
        val speakerInList = devices.firstOrNull { it is AudioDevice.Speakerphone }
        assertNotNull("Speakerphone should be in available devices", speakerInList)
        
        // Select the speaker from the list (same instance)
        audioRouter.selectDevice(speakerInList!!)
        delay(200)
        
        val selected = audioRouter.selectedDevice.value
        assertTrue("Should select speakerphone, but was: ${selected?.name}", selected is AudioDevice.Speakerphone)
        assertTrue("Should be manual selection", audioRouter.isManualSelection.value)
    }

    @Test
    fun selectDevice_earpiece_succeeds() = runBlocking {
        audioRouter.start()
        delay(300)
        
        val devices = audioRouter.availableDevices.value
        
        // Only test if earpiece is available
        if (devices.any { it is AudioDevice.Earpiece }) {
            val earpiece = AudioDevice.Earpiece()
            audioRouter.selectDevice(earpiece)
            delay(100)
            
            val selected = audioRouter.selectedDevice.value
            assertTrue("Should select earpiece", selected is AudioDevice.Earpiece)
        }
    }

    @Test
    fun clearManualSelection_resumesAutoSelection() = runBlocking {
        audioRouter.start()
        delay(500)
        
        // Get speakerphone from available devices
        val speaker = audioRouter.availableDevices.value.firstOrNull { it is AudioDevice.Speakerphone }
        assertNotNull("Speakerphone should be available", speaker)
        
        // Manually select speakerphone
        audioRouter.selectDevice(speaker!!)
        delay(200)
        assertTrue("Should be manual selection after selectDevice", audioRouter.isManualSelection.value)
        
        // Clear manual selection
        audioRouter.clearManualSelection()
        delay(200)
        assertFalse("Should not be manual selection after clear", audioRouter.isManualSelection.value)
    }

    // ==================== Audio Activation Tests ====================

    @Test
    fun activate_requestsAudioFocus() = runBlocking {
        audioRouter.start()
        delay(200)
        audioRouter.activate()
        delay(200)
        
        // Audio mode should be set to communication
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)
    }

    @Test
    fun deactivate_releasesAudioFocus() = runBlocking {
        audioRouter.start()
        delay(200)
        audioRouter.activate()
        delay(200)
        
        audioRouter.deactivate()
        delay(200)
        
        // Audio mode should be reset
        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
    }

    @Test
    fun activate_withSpeakerphone_activatesSpeaker() = runBlocking {
        audioRouter.start()
        delay(300)
        
        val speaker = AudioDevice.Speakerphone()
        audioRouter.selectDevice(speaker)
        delay(100)
        audioRouter.activate()
        delay(200)
        
        // Verify state is activated
        assertEquals(RoutingState.ACTIVATED, audioRouter.routingState.value)
    }

    // ==================== Preferred Order Tests ====================

    @Test
    fun setPreferredDeviceOrder_updatesOrder() = runBlocking {
        audioRouter.start()
        delay(500)
        
        // First clear any manual selection
        audioRouter.clearManualSelection()
        delay(200)
        
        val newOrder = listOf(
            AudioDevice.Speakerphone::class,
            AudioDevice.Earpiece::class,
            AudioDevice.WiredHeadset::class,
            AudioDevice.BluetoothHeadset::class
        )
        
        audioRouter.setPreferredDeviceOrder(newOrder)
        delay(300)
        
        // Without manual selection and no wired/bluetooth, should select speaker (highest in new order)
        val selected = audioRouter.selectedDevice.value
        assertTrue("Should select speakerphone with new order, but was: ${selected?.name}", selected is AudioDevice.Speakerphone)
    }

    // ==================== StateFlow Tests ====================

    @Test
    fun availableDevices_emitsOnChange() = runTest {
        audioRouter.start()
        
        withTimeout(2000) {
            val devices = audioRouter.availableDevices.first { it.isNotEmpty() }
            assertTrue(devices.isNotEmpty())
        }
    }

    @Test
    fun routingState_emitsOnChange() = runTest {
        val initialState = audioRouter.routingState.value
        assertEquals(RoutingState.IDLE, initialState)
        
        audioRouter.start()
        
        withTimeout(2000) {
            val state = audioRouter.routingState.first { it == RoutingState.STARTED }
            assertEquals(RoutingState.STARTED, state)
        }
    }

    // ==================== Listener Callback Tests ====================

    @Test
    fun listener_receivesDeviceChanges() = runBlocking {
        var deviceChangedCalled = false
        
        val listener = object : AudioRouterListener {
            override fun onAudioDevicesChanged(
                devices: List<AudioDevice>,
                selectedDevice: AudioDevice?
            ) {
                if (devices.isNotEmpty()) {
                    deviceChangedCalled = true
                }
            }

            override fun onRoutingStateChanged(state: RoutingState) {}
            override fun onPermissionMissing(permission: String) {}
        }
        
        // Pass listener to start() to ensure it's set before initialization
        audioRouter.start(listener)
        delay(500)
        
        assertTrue("Listener should receive device changes", deviceChangedCalled)
    }

    @Test
    fun listener_receivesStateChanges() = runBlocking {
        var stateChangedCalled = false
        var lastState: RoutingState? = null
        
        val listener = object : AudioRouterListener {
            override fun onAudioDevicesChanged(
                devices: List<AudioDevice>,
                selectedDevice: AudioDevice?
            ) {}

            override fun onRoutingStateChanged(state: RoutingState) {
                stateChangedCalled = true
                lastState = state
            }
            
            override fun onPermissionMissing(permission: String) {}
        }
        
        // Pass listener to start() to ensure it's set before initialization
        audioRouter.start(listener)
        delay(300)
        
        assertTrue("Listener should receive state changes", stateChangedCalled)
        assertEquals(RoutingState.STARTED, lastState)
    }

    // ==================== Multiple Start/Stop Cycles ====================

    @Test
    fun multipleStartStopCycles_workCorrectly() = runBlocking {
        repeat(3) {
            audioRouter.start()
            delay(200)
            assertEquals(RoutingState.STARTED, audioRouter.routingState.value)
            
            audioRouter.stop()
            delay(200)
            assertEquals(RoutingState.IDLE, audioRouter.routingState.value)
        }
    }

    @Test
    fun multipleActivateDeactivateCycles_workCorrectly() = runBlocking {
        audioRouter.start()
        delay(300)
        
        repeat(3) {
            audioRouter.activate()
            delay(200)
            assertEquals(RoutingState.ACTIVATED, audioRouter.routingState.value)
            
            audioRouter.deactivate()
            delay(200)
            assertEquals(RoutingState.STARTED, audioRouter.routingState.value)
        }
        
        audioRouter.stop()
    }
}
