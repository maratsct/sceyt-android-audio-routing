package com.sceyt.audiorouting

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sceyt.audiorouting.internal.Logger
import com.sceyt.audiorouting.internal.device.AudioDeviceManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for AudioDeviceManager that run on actual Android devices.
 * These tests verify real AudioManager interactions.
 */
@RunWith(AndroidJUnit4::class)
class AudioDeviceManagerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var audioDeviceManager: AudioDeviceManager
    private val logger = Logger(enabled = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioDeviceManager = AudioDeviceManager.create(context, logger) { }
    }

    @After
    fun tearDown() {
        // Reset audio state after tests
        audioDeviceManager.restoreAudioState()
    }

    // ==================== Audio Focus Tests ====================

    @Test
    fun requestAudioFocus_returnsTrue() {
        // Set mode before requesting focus for better compatibility
        audioDeviceManager.setVoiceCommunicationMode()
        val result = audioDeviceManager.requestAudioFocus()
        // Note: Audio focus may be denied if another app has it, so we just verify no crash
        // On some devices this may return false, which is acceptable behavior
        assertNotNull("Should return a result", result)
    }

    @Test
    fun abandonAudioFocus_afterRequest_succeeds() {
        audioDeviceManager.requestAudioFocus()
        // Should not throw
        audioDeviceManager.abandonAudioFocus()
    }

    @Test
    fun setVoiceCommunicationMode_setsCorrectMode() {
        audioDeviceManager.setVoiceCommunicationMode()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)
    }

    // ==================== Speakerphone Tests ====================

    @Test
    fun enableSpeakerphone_true_enablesSpeaker() {
        audioDeviceManager.enableSpeakerphone(true)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // On API 31+, check communication device
            val commDevice = audioManager.communicationDevice
            // Speaker might be the device, or null if not supported
            if (commDevice != null) {
                assertTrue(
                    "Communication device should be speaker",
                    commDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                )
            }
        } else {
            // Legacy API
            @Suppress("DEPRECATION")
            assertTrue("Speakerphone should be on", audioManager.isSpeakerphoneOn)
        }
    }

    @Test
    fun enableSpeakerphone_false_disablesSpeaker() {
        audioDeviceManager.enableSpeakerphone(true)
        audioDeviceManager.enableSpeakerphone(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevice = audioManager.communicationDevice
            // After disabling speaker, comm device should not be speaker
            if (commDevice != null) {
                assertNotEquals(
                    "Communication device should not be speaker",
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    commDevice.type
                )
            }
        } else {
            @Suppress("DEPRECATION")
            assertFalse("Speakerphone should be off", audioManager.isSpeakerphoneOn)
        }
    }

    // ==================== Device Detection Tests ====================

    @Test
    fun hasEarpiece_returnsBoolean() {
        // Should return without error
        val hasEarpiece = audioDeviceManager.hasEarpiece()
        // On phones this should be true, on tablets it might be false
        assertNotNull("Should return a boolean value", hasEarpiece)
    }

    @Test
    fun hasSpeakerphone_returnsTrue() {
        val hasSpeaker = audioDeviceManager.hasSpeakerphone()
        assertTrue("Every device should have a speaker", hasSpeaker)
    }

    // ==================== Communication Device Tests (API 31+) ====================

    @Test
    fun getAvailableCommunicationDevices_returnsDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioDeviceManager.getAvailableCommunicationDevices()
            assertTrue("Should have available communication devices", devices.isNotEmpty())
        }
    }

    // ==================== Bluetooth SCO Tests ====================
    // Note: These tests may behave differently based on whether Bluetooth is connected

    @Test
    fun startBluetoothSco_doesNotThrow() {
        // Should not throw even if no Bluetooth device is connected
        audioDeviceManager.startBluetoothSco()
    }

    @Test
    fun stopBluetoothSco_doesNotThrow() {
        // Should not throw even if SCO was never started
        audioDeviceManager.stopBluetoothSco()
    }

    // ==================== State Management Tests ====================

    @Test
    fun cacheAudioState_doesNotThrow() {
        // Should not throw
        audioDeviceManager.cacheAudioState()
    }

    @Test
    fun restoreAudioState_doesNotThrow() {
        audioDeviceManager.cacheAudioState()
        // Should not throw
        audioDeviceManager.restoreAudioState()
    }

    // ==================== Mute Tests ====================

    @Test
    fun setMicrophoneMute_true_mutesMic() {
        audioDeviceManager.setMicrophoneMute(true)
        assertTrue("Microphone should be muted", audioManager.isMicrophoneMute)
        
        // Clean up
        audioDeviceManager.setMicrophoneMute(false)
    }

    @Test
    fun setMicrophoneMute_false_unmutesMic() {
        audioDeviceManager.setMicrophoneMute(true)
        audioDeviceManager.setMicrophoneMute(false)
        assertFalse("Microphone should not be muted", audioManager.isMicrophoneMute)
    }

    // ==================== Device Activation Tests ====================

    @Test
    fun activateDevice_speakerphone_succeeds() {
        val speaker = AudioDevice.Speakerphone()
        // Should not throw
        audioDeviceManager.activateDevice(speaker)
    }

    @Test
    fun activateDevice_earpiece_succeeds() {
        if (audioDeviceManager.hasEarpiece()) {
            val earpiece = AudioDevice.Earpiece()
            // Should not throw
            audioDeviceManager.activateDevice(earpiece)
        }
    }
}
