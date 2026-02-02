package com.sceyt.audiorouting

import com.sceyt.audiorouting.internal.Logger
import com.sceyt.audiorouting.internal.device.DevicePriorityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DevicePriorityManagerTest {

    private lateinit var priorityManager: DevicePriorityManager
    private val logger = Logger(enabled = false)

    @Before
    fun setUp() {
        priorityManager = DevicePriorityManager(AudioRouterConfig(), logger)
    }

    @Test
    fun `selectBestDevice returns null when no devices available`() {
        val result = priorityManager.selectBestDevice(emptyList(), null)
        assertNull(result)
    }

    @Test
    fun `selectBestDevice returns highest priority device`() {
        val devices = listOf(
            AudioDevice.Speakerphone(),
            AudioDevice.Earpiece(),
            AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")
        )

        val result = priorityManager.selectBestDevice(devices, null)

        // Bluetooth has highest priority by default
        assertTrue(result is AudioDevice.BluetoothHeadset)
    }

    @Test
    fun `selectBestDevice keeps current device if still available`() {
        val earpiece = AudioDevice.Earpiece()
        val devices = listOf(
            AudioDevice.Speakerphone(),
            earpiece
        )

        val result = priorityManager.selectBestDevice(devices, earpiece)

        assertEquals(earpiece, result)
    }

    @Test
    fun `selectBestDevice switches to higher priority new device when not manual selection`() {
        val earpiece = AudioDevice.Earpiece()
        val bluetooth = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")
        val devices = listOf(earpiece, bluetooth, AudioDevice.Speakerphone())

        val result = priorityManager.selectBestDevice(
            availableDevices = devices,
            currentDevice = earpiece,
            newlyConnectedDevice = bluetooth
        )

        assertEquals(bluetooth, result)
    }

    @Test
    fun `selectBestDevice keeps manual selection when available`() {
        val earpiece = AudioDevice.Earpiece()
        val bluetooth = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")
        val devices = listOf(earpiece, bluetooth, AudioDevice.Speakerphone())

        // Set manual selection
        priorityManager.setManualSelection(earpiece)

        val result = priorityManager.selectBestDevice(
            availableDevices = devices,
            currentDevice = earpiece,
            newlyConnectedDevice = bluetooth
        )

        // Should keep earpiece despite higher priority BT connecting
        assertEquals(earpiece, result)
    }

    @Test
    fun `shouldAutoSwitch returns false when manual selection active`() {
        val bluetooth = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")
        val earpiece = AudioDevice.Earpiece()

        priorityManager.setManualSelection(earpiece)

        val result = priorityManager.shouldAutoSwitch(bluetooth, earpiece)

        assertFalse(result)
    }

    @Test
    fun `shouldAutoSwitch returns true when new device has higher priority`() {
        val bluetooth = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")
        val earpiece = AudioDevice.Earpiece()

        val result = priorityManager.shouldAutoSwitch(bluetooth, earpiece)

        assertTrue(result)
    }

    @Test
    fun `shouldAutoSwitch returns false when new device has lower priority`() {
        val bluetooth = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")
        val speakerphone = AudioDevice.Speakerphone()

        val result = priorityManager.shouldAutoSwitch(speakerphone, bluetooth)

        assertFalse(result)
    }

    @Test
    fun `selectFallbackDevice returns best available after disconnect`() {
        val bluetooth = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")
        val devices = listOf(bluetooth, AudioDevice.Earpiece(), AudioDevice.Speakerphone())

        val result = priorityManager.selectFallbackDevice(devices, bluetooth)

        // Should select earpiece (next highest priority)
        assertTrue(result is AudioDevice.Earpiece)
    }

    @Test
    fun `clearManualSelection allows auto-switching again`() {
        val earpiece = AudioDevice.Earpiece()
        val bluetooth = AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55")

        priorityManager.setManualSelection(earpiece)
        assertTrue(priorityManager.isManualSelection)

        priorityManager.clearManualSelection()
        assertFalse(priorityManager.isManualSelection)

        val result = priorityManager.shouldAutoSwitch(bluetooth, earpiece)
        assertTrue(result)
    }

    @Test
    fun `custom priority order is respected`() {
        // Set speaker as highest priority
        priorityManager.setPreferredOrder(listOf(
            AudioDevice.Speakerphone::class,
            AudioDevice.Earpiece::class,
            AudioDevice.WiredHeadset::class,
            AudioDevice.BluetoothHeadset::class
        ))

        val devices = listOf(
            AudioDevice.BluetoothHeadset("BT Headset", "00:11:22:33:44:55"),
            AudioDevice.Earpiece(),
            AudioDevice.Speakerphone()
        )

        val result = priorityManager.selectBestDevice(devices, null)

        assertTrue(result is AudioDevice.Speakerphone)
    }

    @Test
    fun `getPriority returns correct values based on order`() {
        assertEquals(0, priorityManager.getPriority(AudioDevice.BluetoothHeadset()))
        assertEquals(1, priorityManager.getPriority(AudioDevice.WiredHeadset()))
        assertEquals(2, priorityManager.getPriority(AudioDevice.Earpiece()))
        assertEquals(3, priorityManager.getPriority(AudioDevice.Speakerphone()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setPreferredOrder throws on duplicates`() {
        priorityManager.setPreferredOrder(listOf(
            AudioDevice.Speakerphone::class,
            AudioDevice.Speakerphone::class
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setPreferredOrder throws on empty list`() {
        priorityManager.setPreferredOrder(emptyList())
    }
}
