package com.sceyt.audiorouting

import com.sceyt.audiorouting.internal.Logger
import com.sceyt.audiorouting.internal.state.AudioRoutingEvent
import com.sceyt.audiorouting.internal.state.AudioRoutingState
import com.sceyt.audiorouting.internal.state.AudioRoutingStateMachine
import com.sceyt.audiorouting.internal.state.BluetoothScoState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioRoutingStateMachineTest {

    private val logger = Logger(enabled = false)
    private val config = AudioRouterConfig()

    private fun createStateMachine(
        onStateChanged: suspend (AudioRoutingState, AudioRoutingState) -> Unit = { _, _ -> }
    ): AudioRoutingStateMachine {
        // Use UnconfinedTestDispatcher so events are processed immediately
        val testDispatcher = UnconfinedTestDispatcher()
        return AudioRoutingStateMachine(
            scope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            config = config,
            logger = logger,
            onStateChanged = onStateChanged
        )
    }

    @Test
    fun `initial state is IDLE`() {
        val stateMachine = createStateMachine()
        try {
            assertEquals(RoutingState.IDLE, stateMachine.state.value.routingState)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `start event transitions to STARTED`() {
        val stateMachine = createStateMachine()
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)
            assertEquals(RoutingState.STARTED, stateMachine.state.value.routingState)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `activate event transitions from STARTED to ACTIVATED`() {
        val stateMachine = createStateMachine()
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.Activate)
            assertEquals(RoutingState.ACTIVATED, stateMachine.state.value.routingState)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `activate event does nothing when IDLE`() {
        val stateMachine = createStateMachine()
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Activate)
            assertEquals(RoutingState.IDLE, stateMachine.state.value.routingState)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `deactivate event transitions from ACTIVATED to STARTED`() {
        val stateMachine = createStateMachine()
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.Activate)
            stateMachine.sendEvent(AudioRoutingEvent.Deactivate)
            assertEquals(RoutingState.STARTED, stateMachine.state.value.routingState)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `stop event transitions to IDLE and resets state`() {
        val stateMachine = createStateMachine()
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.Activate)
            stateMachine.sendEvent(AudioRoutingEvent.Stop)

            assertEquals(RoutingState.IDLE, stateMachine.state.value.routingState)
            assertTrue(stateMachine.state.value.availableDevices.isEmpty())
            assertNull(stateMachine.state.value.selectedDevice)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `bluetooth device connected adds to available devices`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))

            val state = stateMachine.state.value
            assertTrue(state.availableDevices.any { it is AudioDevice.BluetoothHeadset })
            assertEquals(btDevice, state.activeBluetoothDevice)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `bluetooth device connected selects as active when highest priority`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))

            assertEquals(btDevice, stateMachine.state.value.selectedDevice)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `bluetooth device disconnected removes from available and selects fallback`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceDisconnected(btDevice))

            val state = stateMachine.state.value
            assertFalse(state.availableDevices.any { it is AudioDevice.BluetoothHeadset })
            assertNull(state.activeBluetoothDevice)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `wired headset connected adds to available devices`() {
        val stateMachine = createStateMachine()
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)

            val state = stateMachine.state.value
            assertTrue(state.availableDevices.any { it is AudioDevice.WiredHeadset })
            assertTrue(state.wiredHeadsetConnected)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `wired headset connected hides earpiece`() {
        val stateMachine = createStateMachine()
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)

            val state = stateMachine.state.value
            assertFalse(state.availableDevices.any { it is AudioDevice.Earpiece })
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `user select device sets manual selection`() {
        val stateMachine = createStateMachine()
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)

            // Speakerphone is always available
            val speaker = AudioDevice.Speakerphone()
            stateMachine.sendEvent(AudioRoutingEvent.UserSelectDevice(speaker))

            val state = stateMachine.state.value
            assertTrue(state.isManualSelection)
            assertEquals(speaker, state.selectedDevice)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `clear manual selection resumes auto-selection`() {
        val stateMachine = createStateMachine()
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)
            // Connect a wired headset to populate available devices (includes Speakerphone)
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)

            val speaker = AudioDevice.Speakerphone()
            stateMachine.sendEvent(AudioRoutingEvent.UserSelectDevice(speaker))
            assertTrue(stateMachine.state.value.isManualSelection)

            stateMachine.sendEvent(AudioRoutingEvent.ClearManualSelection)
            assertFalse(stateMachine.state.value.isManualSelection)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `bluetooth sco connected updates state`() {
        val stateMachine = createStateMachine()
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.Activate)
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothScoConnected)

            assertEquals(BluetoothScoState.Connected, stateMachine.state.value.bluetoothScoState)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `bluetooth sco failed triggers fallback after max retries`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            stateMachine.sendEvent(AudioRoutingEvent.Activate)

            // Simulate max retries (default is 3)
            repeat(4) {
                stateMachine.sendEvent(AudioRoutingEvent.BluetoothScoFailed("Connection failed"))
            }

            val state = stateMachine.state.value
            // Should have fallen back to a non-BT device
            assertFalse(state.selectedDevice is AudioDevice.BluetoothHeadset)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `onStateChanged callback is invoked on state changes`() = runTest {
        var lastOldState: AudioRoutingState? = null
        var lastNewState: AudioRoutingState? = null
        
        val stateMachine = createStateMachine { old, new ->
            lastOldState = old
            lastNewState = new
        }
        
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)

            assertNotNull(lastOldState)
            assertNotNull(lastNewState)
            assertEquals(RoutingState.IDLE, lastOldState?.routingState)
            assertEquals(RoutingState.STARTED, lastNewState?.routingState)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `events are ignored when not listening`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            // Don't start - should be in IDLE state
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))

            // Device should not be added when stopped
            assertTrue(stateMachine.state.value.availableDevices.isEmpty())
        } finally {
            stateMachine.close()
        }
    }
}
