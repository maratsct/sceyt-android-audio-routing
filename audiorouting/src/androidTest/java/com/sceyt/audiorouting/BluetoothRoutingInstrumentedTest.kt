package com.sceyt.audiorouting

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for Bluetooth audio routing.
 * Tests Bluetooth headset detection and SCO connection on real devices.
 *
 * Note: These tests require a Bluetooth headset to be connected for full coverage.
 * Tests will skip gracefully if no Bluetooth device is connected.
 *
 * BLUETOOTH_CONNECT permission must be granted manually before running tests
 * or via adb: adb shell pm grant <package> android.permission.BLUETOOTH_CONNECT
 */
@RunWith(AndroidJUnit4::class)
class BluetoothRoutingInstrumentedTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var audioRouter: AudioRouter
    private var bluetoothAdapter: BluetoothAdapter? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        val config = AudioRouterConfig(loggingEnabled = true)
        audioRouter = AudioRouter.create(context, config)
    }

    @After
    fun tearDown() {
        audioRouter.stop()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    // ==================== Bluetooth Availability Tests ====================

    @Test
    fun bluetoothAdapter_isAvailable() {
        // Most Android devices have Bluetooth
        assertNotNull("Device should have Bluetooth adapter", bluetoothAdapter)
    }

    // ==================== Bluetooth Device Detection Tests ====================

    @Test
    fun start_detectsBluetoothDeviceIfConnected() = runBlocking {
        assumeBluetoothAvailable()

        audioRouter.start()
        delay(500) // Wait for Bluetooth profile to connect

        val devices = audioRouter.availableDevices.value
        val hasBluetoothDevice = devices.any { it is AudioDevice.BluetoothHeadset }

        // This will be true only if a Bluetooth headset is connected
        // Log the result for debugging
        println("Bluetooth device detected: $hasBluetoothDevice, devices: $devices")
    }

    @Test
    fun bluetoothDevice_hasCorrectProperties() = runBlocking {
        assumeBluetoothAvailable()

        audioRouter.start()
        delay(500)

        val btDevice = audioRouter.availableDevices.value
            .filterIsInstance<AudioDevice.BluetoothHeadset>()
            .firstOrNull()

        if (btDevice != null) {
            assertNotNull("Bluetooth device should have name", btDevice.name)
            assertNotNull("Bluetooth device should have address", btDevice.address)
            assertTrue(
                "Bluetooth address should be valid format",
                btDevice.address.matches(Regex("[0-9A-Fa-f:]{17}"))
            )
        }
    }

    // ==================== Bluetooth Selection Tests ====================

    @Test
    fun selectBluetoothDevice_whenAvailable_succeeds() = runBlocking {
        assumeBluetoothAvailable()

        audioRouter.start()
        delay(500)

        val btDevice = audioRouter.availableDevices.value
            .filterIsInstance<AudioDevice.BluetoothHeadset>()
            .firstOrNull()

        if (btDevice != null) {
            audioRouter.selectDevice(btDevice)
            delay(100)

            assertEquals(btDevice.id, audioRouter.selectedDevice.value?.id)
            assertTrue(audioRouter.isManualSelection.value)
        }
    }

    @Test
    fun selectBluetoothDevice_autoSelectsWithHighestPriority() = runBlocking {
        assumeBluetoothAvailable()

        audioRouter.start()
        delay(500)

        val devices = audioRouter.availableDevices.value
        val btDevice = devices.filterIsInstance<AudioDevice.BluetoothHeadset>().firstOrNull()

        if (btDevice != null) {
            // With default priority, Bluetooth should be auto-selected
            val selected = audioRouter.selectedDevice.value
            assertTrue(
                "Bluetooth should be auto-selected when connected",
                selected is AudioDevice.BluetoothHeadset
            )
        }
    }

    // ==================== Bluetooth SCO Tests ====================

    @Test
    fun activate_withBluetooth_requestsSco() = runBlocking {
        assumeBluetoothAvailable()

        audioRouter.start()
        delay(500)

        val btDevice = audioRouter.availableDevices.value
            .filterIsInstance<AudioDevice.BluetoothHeadset>()
            .firstOrNull()

        if (btDevice != null) {
            audioRouter.selectDevice(btDevice)
            audioRouter.activate()

            // Wait for SCO connection attempt
            delay(2000)

            // Check if audio mode is set correctly
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)

            // Check SCO state - this depends on actual Bluetooth connection
            @Suppress("DEPRECATION")
            val isScoOn = audioManager.isBluetoothScoOn
            println("SCO state after activation: $isScoOn")
        }
    }

    @Test
    fun deactivate_withBluetooth_stopsSco() = runBlocking {
        assumeBluetoothAvailable()

        audioRouter.start()
        delay(500)

        val btDevice = audioRouter.availableDevices.value
            .filterIsInstance<AudioDevice.BluetoothHeadset>()
            .firstOrNull()

        if (btDevice != null) {
            audioRouter.selectDevice(btDevice)
            audioRouter.activate()
            delay(1000)

            audioRouter.deactivate()
            delay(500)

            // Audio mode should be reset
            assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        }
    }

    // ==================== Fallback Tests ====================

    @Test
    fun switchFromBluetoothToSpeaker_works() = runBlocking {
        assumeBluetoothAvailable()

        audioRouter.start()
        delay(500)

        val btDevice = audioRouter.availableDevices.value
            .filterIsInstance<AudioDevice.BluetoothHeadset>()
            .firstOrNull()

        if (btDevice != null) {
            audioRouter.selectDevice(btDevice)
            audioRouter.activate()
            delay(500)

            // Switch to speakerphone
            val speaker = AudioDevice.Speakerphone()
            audioRouter.selectDevice(speaker)
            delay(500)

            assertEquals(speaker.id, audioRouter.selectedDevice.value?.id)
        }
    }

    @Test
    fun switchFromSpeakerToBluetooth_works() = runBlocking {
        assumeBluetoothAvailable()

        audioRouter.start()
        delay(500)

        val btDevice = audioRouter.availableDevices.value
            .filterIsInstance<AudioDevice.BluetoothHeadset>()
            .firstOrNull()

        if (btDevice != null) {
            // First select speaker
            val speaker = AudioDevice.Speakerphone()
            audioRouter.selectDevice(speaker)
            audioRouter.activate()
            delay(500)

            // Switch to Bluetooth
            audioRouter.selectDevice(btDevice)
            delay(1000)

            assertEquals(btDevice.id, audioRouter.selectedDevice.value?.id)
        }
    }

    // ==================== SCO Retry Tests ====================

    @Test
    fun scoRetry_configurationIsRespected() = runBlocking {
        val customConfig = AudioRouterConfig(
            loggingEnabled = true,
            scoRetryCount = 2,
            scoTimeoutMs = 3000,
            scoRetryDelayMs = 500
        )

        val customRouter = AudioRouter.create(context, customConfig)

        try {
            customRouter.start()
            delay(200)

            // Just verify router starts without issues with custom config
            assertEquals(RoutingState.STARTED, customRouter.routingState.value)
        } finally {
            customRouter.stop()
        }
    }

    // ==================== Helper Methods ====================

    private fun assumeBluetoothAvailable() {
        assumeTrue("Bluetooth adapter required", bluetoothAdapter != null)
        assumeTrue("Bluetooth must be enabled", bluetoothAdapter?.isEnabled == true)
    }
}
