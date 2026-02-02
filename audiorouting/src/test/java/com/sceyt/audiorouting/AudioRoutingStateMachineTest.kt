package com.sceyt.audiorouting

import com.sceyt.audiorouting.internal.Logger
import com.sceyt.audiorouting.internal.state.AudioRoutingEvent
import com.sceyt.audiorouting.internal.state.AudioRoutingState
import com.sceyt.audiorouting.internal.state.AudioRoutingStateMachine
import com.sceyt.audiorouting.internal.state.BluetoothScoState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioRoutingStateMachineTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val logger = Logger(enabled = false)
    private val config = AudioRouterConfig()

    private lateinit var stateMachine: AudioRoutingStateMachine
    private var lastOldState: AudioRoutingState? = null
    private var lastNewState: AudioRoutingState? = null

    @Before
    fun setUp() {
        stateMachine = AudioRoutingStateMachine(
            scope = testScope,
            config = config,
            logger = logger,
            onStateChanged = { old, new ->
                lastOldState = old
                lastNewState = new
            }
        )
    }

    @After
    fun tearDown() {
        stateMachine.close()
    }

    @Test
    fun `initial state is STOPPED`() {
        assertEquals(RoutingState.STOPPED, stateMachine.state.value.routingState)
    }

    @Test
    fun `start event transitions to STARTED`() = testScope.runTest {
        stateMachine.sendEvent(AudioRoutingEvent.Start)
        advanceUntilIdle()

        assertEquals(RoutingState.STARTED, stateMachine.state.value.routingState)
    }

    @Test
    fun `activate event transitions from STARTED to ACTIVATED`() = testScope.runTest {
        stateMachine.sendEvent(AudioRoutingEvent.Start)
        advanceUntilIdle()
        stateMachine.sendEvent(AudioRoutingEvent.Activate)
        advanceUntilIdle()

        assertEquals(RoutingState.ACTIVATED, stateMachine.state.value.routingState)
    }

    @Test
    fun `activate event does nothing when STOPPED`() = testScope.runTest {
        stateMachine.sendEvent(AudioRoutingEvent.Activate)
        advanceUntilIdle()

        assertEquals(RoutingState.STOPPED, stateMachine.state.value.routingState)
    }

    @Test
    fun `deactivate event transitions from ACTIVATED to STARTED`() = testScope.runTest {
        stateMachine.sendEvent(AudioRoutingEvent.Start)
        stateMachine.sendEvent(AudioRoutingEvent.Activate)
        advanceUntilIdle()
        stateMachine.sendEvent(AudioRoutingEvent.Deactivate)
        advanceUntilIdle()

        assertEquals(RoutingState.STARTED, stateMachine.state.value.routingState)
    }

    @Test
    fun `stop event transitions to STOPPED and resets state`() = testScope.runTest {
        stateMachine.sendEvent(AudioRoutingEvent.Start)
        stateMachine.sendEvent(AudioRoutingEvent.Activate)
        advanceUntilIdle()
        stateMachine.sendEvent(AudioRoutingEvent.Stop)
        advanceUntilIdle()

        assertEquals(RoutingState.STOPPED, stateMachine.state.value.routingState)
        assertTrue(stateMachine.state.value.availableDevices.isEmpty())
        assertNull(stateMachine.state.value.selectedDevice)
    }

    @Test
    fun `bluetooth device connected adds to available devices`() = testScope.runTest {
        val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

        stateMachine.sendEvent(AudioRoutingEvent.Start)
        advanceUntilIdle()
        stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
        advanceUntilIdle()

        val state = stateMachine.state.value
        assertTrue(state.availableDevices.any { it is AudioDevice.BluetoothHeadset })
        assertEquals(btDevice, state.activeBluetoothDevice)
    }

    @Test
    fun `bluetooth device connected selects as active when highest priority`() = testScope.runTest {
        val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

        stateMachine.sendEvent(AudioRoutingEvent.Start)
        advanceUntilIdle()
        stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
        advanceUntilIdle()

        assertEquals(btDevice, stateMachine.state.value.selectedDevice)
    }

    @Test
    fun `bluetooth device disconnected removes from available and selects fallback`() = testScope.runTest {
        val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

        stateMachine.sendEvent(AudioRoutingEvent.Start)
        stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
        advanceUntilIdle()
        stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceDisconnected(btDevice))
        advanceUntilIdle()

        val state = stateMachine.state.value
        assertFalse(state.availableDevices.any { it is AudioDevice.BluetoothHeadset })
        assertNull(state.activeBluetoothDevice)
    }

    @Test
    fun `wired headset connected adds to available devices`() = testScope.runTest {
        stateMachine.sendEvent(AudioRoutingEvent.Start)
        advanceUntilIdle()
        stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
        advanceUntilIdle()

        val state = stateMachine.state.value
        assertTrue(state.availableDevices.any { it is AudioDevice.WiredHeadset })
        assertTrue(state.wiredHeadsetConnected)
    }

    @Test
    fun `wired headset connected hides earpiece`() = testScope.runTest {
        stateMachine.sendEvent(AudioRoutingEvent.Start)
        advanceUntilIdle()
        stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
        advanceUntilIdle()

        val state = stateMachine.state.value
        assertFalse(state.availableDevices.any { it is AudioDevice.Earpiece })
    }

    @Test
    fun `user select device sets manual selection`() = testScope.runTest {
        val earpiece = AudioDevice.Earpiece()

        stateMachine.sendEvent(AudioRoutingEvent.Start)
        stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
        advanceUntilIdle()

        // Speakerphone is always available
        val speaker = AudioDevice.Speakerphone()
        stateMachine.sendEvent(AudioRoutingEvent.UserSelectDevice(speaker))
        advanceUntilIdle()

        val state = stateMachine.state.value
        assertTrue(state.isManualSelection)
        assertEquals(speaker, state.selectedDevice)
    }

    @Test
    fun `clear manual selection resumes auto-selection`() = testScope.runTest {
        stateMachine.sendEvent(AudioRoutingEvent.Start)
        advanceUntilIdle()

        val speaker = AudioDevice.Speakerphone()
        stateMachine.sendEvent(AudioRoutingEvent.UserSelectDevice(speaker))
        advanceUntilIdle()
        assertTrue(stateMachine.state.value.isManualSelection)

        stateMachine.sendEvent(AudioRoutingEvent.ClearManualSelection)
        advanceUntilIdle()

        assertFalse(stateMachine.state.value.isManualSelection)
    }

    @Test
    fun `bluetooth sco connected updates state`() = testScope.runTest {
        stateMachine.sendEvent(AudioRoutingEvent.Start)
        stateMachine.sendEvent(AudioRoutingEvent.Activate)
        advanceUntilIdle()
        stateMachine.sendEvent(AudioRoutingEvent.BluetoothScoConnected)
        advanceUntilIdle()

        assertEquals(BluetoothScoState.Connected, stateMachine.state.value.bluetoothScoState)
    }

    @Test
    fun `bluetooth sco failed triggers fallback after max retries`() = testScope.runTest {
        val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

        stateMachine.sendEvent(AudioRoutingEvent.Start)
        stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
        stateMachine.sendEvent(AudioRoutingEvent.Activate)
        advanceUntilIdle()

        // Simulate max retries (default is 3)
        repeat(4) {
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothScoFailed("Connection failed"))
            advanceUntilIdle()
        }

        val state = stateMachine.state.value
        // Should have fallen back to a non-BT device
        assertFalse(state.selectedDevice is AudioDevice.BluetoothHeadset)
    }

    @Test
    fun `onStateChanged callback is invoked on state changes`() = testScope.runTest {
        stateMachine.sendEvent(AudioRoutingEvent.Start)
        advanceUntilIdle()

        assertNotNull(lastOldState)
        assertNotNull(lastNewState)
        assertEquals(RoutingState.STOPPED, lastOldState?.routingState)
        assertEquals(RoutingState.STARTED, lastNewState?.routingState)
    }

    @Test
    fun `events are ignored when not listening`() = testScope.runTest {
        val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

        // Don't start - should be in STOPPED state
        stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
        advanceUntilIdle()

        // Device should not be added when stopped
        assertTrue(stateMachine.state.value.availableDevices.isEmpty())
    }
}
