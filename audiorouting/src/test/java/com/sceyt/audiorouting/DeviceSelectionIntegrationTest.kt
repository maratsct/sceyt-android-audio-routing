package com.sceyt.audiorouting

import com.sceyt.audiorouting.internal.Logger
import com.sceyt.audiorouting.internal.state.AudioRoutingEvent
import com.sceyt.audiorouting.internal.state.AudioRoutingStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Test

/**
 * Integration tests for device selection scenarios.
 * These tests verify complex interaction patterns between state machine events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSelectionIntegrationTest {

    private val logger = Logger(enabled = false)
    private val config = AudioRouterConfig()

    private fun createStateMachine(): AudioRoutingStateMachine {
        val testDispatcher = UnconfinedTestDispatcher()
        return AudioRoutingStateMachine(
            scope = CoroutineScope(testDispatcher),
            config = config,
            logger = logger,
            onStateChanged = { _, _ -> }
        )
    }

    // ==================== Auto-Selection Tests ====================

    @Test
    fun `auto-selects bluetooth when it connects and has highest priority`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            // Initially should have no devices
            assertTrue(stateMachine.state.value.availableDevices.isEmpty())

            // Connect wired headset first
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
            assertEquals(AudioDevice.WiredHeadset::class, stateMachine.state.value.selectedDevice!!::class)

            // Connect Bluetooth - should auto-switch because BT has higher priority
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            assertEquals(btDevice, stateMachine.state.value.selectedDevice)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `does not auto-switch to lower priority device`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            assertEquals(btDevice, stateMachine.state.value.selectedDevice)

            // Connect wired headset - should NOT switch because wired is lower priority than BT
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
            assertEquals(btDevice, stateMachine.state.value.selectedDevice)
        } finally {
            stateMachine.close()
        }
    }

    // ==================== Manual Selection Tests ====================

    @Test
    fun `manual selection persists when higher priority device connects`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)

            // User manually selects speakerphone
            val speaker = AudioDevice.Speakerphone()
            stateMachine.sendEvent(AudioRoutingEvent.UserSelectDevice(speaker))
            assertTrue(stateMachine.state.value.isManualSelection)
            assertEquals(speaker, stateMachine.state.value.selectedDevice)

            // Connect Bluetooth (higher priority) - should NOT switch due to manual selection
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            assertEquals(speaker, stateMachine.state.value.selectedDevice)
            assertTrue(stateMachine.state.value.isManualSelection)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `manual selection cleared when selected device disconnects`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            
            // User manually selects bluetooth
            stateMachine.sendEvent(AudioRoutingEvent.UserSelectDevice(btDevice))
            assertTrue(stateMachine.state.value.isManualSelection)

            // Bluetooth disconnects - manual selection should be cleared
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceDisconnected(btDevice))
            assertFalse(stateMachine.state.value.isManualSelection)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `clear manual selection triggers auto-selection of best device`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)

            // User manually selects speakerphone (lowest priority)
            val speaker = AudioDevice.Speakerphone()
            stateMachine.sendEvent(AudioRoutingEvent.UserSelectDevice(speaker))
            assertEquals(speaker, stateMachine.state.value.selectedDevice)

            // Clear manual selection - should auto-select Bluetooth (highest priority)
            stateMachine.sendEvent(AudioRoutingEvent.ClearManualSelection)
            assertFalse(stateMachine.state.value.isManualSelection)
            assertEquals(btDevice, stateMachine.state.value.selectedDevice)
        } finally {
            stateMachine.close()
        }
    }

    // ==================== Device Disconnect/Reconnect Tests ====================

    @Test
    fun `fallback to next priority device when current disconnects`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
            assertEquals(btDevice, stateMachine.state.value.selectedDevice)

            // Bluetooth disconnects - should fallback to wired headset
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceDisconnected(btDevice))
            assertTrue(stateMachine.state.value.selectedDevice is AudioDevice.WiredHeadset)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `bluetooth reconnects and becomes selected again`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            assertEquals(btDevice, stateMachine.state.value.selectedDevice)

            // Bluetooth disconnects
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceDisconnected(btDevice))
            assertTrue(stateMachine.state.value.selectedDevice is AudioDevice.WiredHeadset)

            // Bluetooth reconnects - should auto-select again
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            assertEquals(btDevice, stateMachine.state.value.selectedDevice)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `wired headset hides earpiece when connected`() {
        val stateMachine = createStateMachine()
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)
            
            // Initially no earpiece in available (state machine doesn't add built-in devices by default)
            // Connect wired - earpiece should be hidden
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
            
            val state = stateMachine.state.value
            assertTrue(state.availableDevices.any { it is AudioDevice.WiredHeadset })
            assertFalse(state.availableDevices.any { it is AudioDevice.Earpiece })
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `wired headset disconnect shows earpiece again`() {
        val stateMachine = createStateMachine()
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
            assertFalse(stateMachine.state.value.availableDevices.any { it is AudioDevice.Earpiece })

            // Disconnect wired - earpiece should appear
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetDisconnected)
            assertTrue(stateMachine.state.value.availableDevices.any { it is AudioDevice.Earpiece })
        } finally {
            stateMachine.close()
        }
    }

    // ==================== State Transition Tests ====================

    @Test
    fun `device changes during ACTIVATED state trigger activation`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
            stateMachine.sendEvent(AudioRoutingEvent.Activate)
            assertEquals(RoutingState.ACTIVATED, stateMachine.state.value.routingState)

            // Connect Bluetooth during active call
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            
            // Should switch to Bluetooth
            assertEquals(btDevice, stateMachine.state.value.selectedDevice)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `stop event resets all state including manual selection`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            stateMachine.sendEvent(AudioRoutingEvent.UserSelectDevice(btDevice))
            stateMachine.sendEvent(AudioRoutingEvent.Activate)

            assertTrue(stateMachine.state.value.isManualSelection)
            assertNotNull(stateMachine.state.value.selectedDevice)

            // Stop
            stateMachine.sendEvent(AudioRoutingEvent.Stop)

            val state = stateMachine.state.value
            assertEquals(RoutingState.STOPPED, state.routingState)
            assertFalse(state.isManualSelection)
            assertNull(state.selectedDevice)
            assertTrue(state.availableDevices.isEmpty())
        } finally {
            stateMachine.close()
        }
    }

    // ==================== Edge Cases ====================

    @Test
    fun `selecting unavailable device does nothing`() {
        val stateMachine = createStateMachine()
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
            
            val wired = stateMachine.state.value.selectedDevice
            
            // Try to select Bluetooth that's not connected
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")
            stateMachine.sendEvent(AudioRoutingEvent.UserSelectDevice(btDevice))

            // Should remain on wired, no manual selection set
            assertEquals(wired, stateMachine.state.value.selectedDevice)
            assertFalse(stateMachine.state.value.isManualSelection)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `events ignored when stopped`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            // Don't start - in STOPPED state
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
            stateMachine.sendEvent(AudioRoutingEvent.UserSelectDevice(btDevice))

            val state = stateMachine.state.value
            assertEquals(RoutingState.STOPPED, state.routingState)
            assertTrue(state.availableDevices.isEmpty())
            assertNull(state.selectedDevice)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `rapid connect disconnect handled correctly`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)

            // Rapid connect/disconnect cycle
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceDisconnected(btDevice))
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceDisconnected(btDevice))

            // Should end up on wired headset
            val state = stateMachine.state.value
            assertTrue(state.selectedDevice is AudioDevice.WiredHeadset)
            assertFalse(state.availableDevices.any { it is AudioDevice.BluetoothHeadset })
        } finally {
            stateMachine.close()
        }
    }

    // ==================== Custom Priority Order Tests ====================

    @Test
    fun `custom priority order is respected when selecting initial device`() {
        // Create config with wired headset as highest priority (over Bluetooth)
        val customConfig = AudioRouterConfig(
            preferredDeviceOrder = listOf(
                AudioDevice.WiredHeadset::class,
                AudioDevice.BluetoothHeadset::class,
                AudioDevice.Earpiece::class,
                AudioDevice.Speakerphone::class
            )
        )
        
        val testDispatcher = UnconfinedTestDispatcher()
        val stateMachine = AudioRoutingStateMachine(
            scope = CoroutineScope(testDispatcher),
            config = customConfig,
            logger = logger,
            onStateChanged = { _, _ -> }
        )
        
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            // With default priority, BT would be selected first
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            assertEquals(btDevice, stateMachine.state.value.selectedDevice)

            // Now connect wired headset - with custom order it's higher priority than BT
            // So should switch to wired headset
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
            assertTrue(stateMachine.state.value.selectedDevice is AudioDevice.WiredHeadset)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `custom priority affects fallback selection`() {
        // Create config with speaker above earpiece
        val customConfig = AudioRouterConfig(
            preferredDeviceOrder = listOf(
                AudioDevice.BluetoothHeadset::class,
                AudioDevice.WiredHeadset::class,
                AudioDevice.Speakerphone::class,
                AudioDevice.Earpiece::class
            )
        )
        
        val testDispatcher = UnconfinedTestDispatcher()
        val stateMachine = AudioRoutingStateMachine(
            scope = CoroutineScope(testDispatcher),
            config = customConfig,
            logger = logger,
            onStateChanged = { _, _ -> }
        )
        
        try {
            stateMachine.sendEvent(AudioRoutingEvent.Start)
            // No BT or wired connected - should fallback to speaker (3rd) before earpiece (4th)
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetDisconnected)

            // After disconnect, should select speakerphone (higher priority than earpiece)
            assertTrue(stateMachine.state.value.selectedDevice is AudioDevice.Speakerphone)
        } finally {
            stateMachine.close()
        }
    }

    @Test
    fun `update preferred order at runtime triggers reselection`() {
        val stateMachine = createStateMachine()
        try {
            val btDevice = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

            stateMachine.sendEvent(AudioRoutingEvent.Start)
            stateMachine.sendEvent(AudioRoutingEvent.BluetoothDeviceConnected(btDevice))
            stateMachine.sendEvent(AudioRoutingEvent.WiredHeadsetConnected)
            
            // BT should be selected (default highest priority)
            assertEquals(btDevice, stateMachine.state.value.selectedDevice)

            // Update preferred order to make wired headset highest priority
            val newOrder = listOf(
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.Earpiece::class.java,
                AudioDevice.Speakerphone::class.java
            )
            stateMachine.sendEvent(AudioRoutingEvent.UpdatePreferredOrder(newOrder))

            // Should now select wired headset
            assertTrue(stateMachine.state.value.selectedDevice is AudioDevice.WiredHeadset)
        } finally {
            stateMachine.close()
        }
    }
}
