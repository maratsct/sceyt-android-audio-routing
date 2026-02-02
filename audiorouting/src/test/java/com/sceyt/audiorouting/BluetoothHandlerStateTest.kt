package com.sceyt.audiorouting

import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import com.sceyt.audiorouting.internal.bluetooth.BluetoothStateTracker
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for BluetoothStateTracker state transitions.
 * Verifies the state machine correctly tracks Bluetooth headset connection states.
 */
class BluetoothHandlerStateTest {

    private lateinit var tracker: BluetoothStateTracker

    @Before
    fun setUp() {
        tracker = BluetoothStateTracker()
    }

    // ==================== Connection State Tests ====================

    @Test
    fun `STATE_CONNECTING does not change state`() {
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTING)
        assertEquals(BluetoothStateTracker.HeadsetState.Disconnected, tracker.headsetState)
    }

    @Test
    fun `STATE_DISCONNECTING does not change state when disconnected`() {
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_DISCONNECTING)
        assertEquals(BluetoothStateTracker.HeadsetState.Disconnected, tracker.headsetState)
    }

    @Test
    fun `STATE_DISCONNECTING does not change state when connected`() {
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_DISCONNECTING)
        // Should still be connected until we get DISCONNECTED
        assertEquals(BluetoothStateTracker.HeadsetState.Connected, tracker.headsetState)
    }

    @Test
    fun `full connection lifecycle CONNECTING to CONNECTED to DISCONNECTING to DISCONNECTED`() {
        // Connecting
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTING)
        assertFalse(tracker.isConnected)

        // Connected
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        assertTrue(tracker.isConnected)
        assertEquals(BluetoothStateTracker.HeadsetState.Connected, tracker.headsetState)

        // Disconnecting
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_DISCONNECTING)
        assertTrue(tracker.isConnected) // Still connected until disconnect

        // Disconnected
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_DISCONNECTED)
        assertFalse(tracker.isConnected)
    }

    // ==================== Audio State Tests ====================

    @Test
    fun `audio connected while device connected transitions to AudioActivated`() {
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_CONNECTED)

        assertEquals(BluetoothStateTracker.HeadsetState.AudioActivated, tracker.headsetState)
        assertTrue(tracker.isAudioActive)
    }

    @Test
    fun `audio disconnected returns to Connected state`() {
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_CONNECTED)
        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_DISCONNECTED)

        assertEquals(BluetoothStateTracker.HeadsetState.Connected, tracker.headsetState)
        assertTrue(tracker.isConnected)
        assertFalse(tracker.isAudioActive)
    }

    @Test
    fun `audio disconnected when device also disconnected goes to Disconnected`() {
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_CONNECTED)
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_DISCONNECTED)
        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_DISCONNECTED)

        assertEquals(BluetoothStateTracker.HeadsetState.Disconnected, tracker.headsetState)
    }

    // ==================== SCO Activation Tests ====================

    @Test
    fun `setAudioActivating from Connected transitions to AudioActivating`() {
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        tracker.setAudioActivating()

        assertEquals(BluetoothStateTracker.HeadsetState.AudioActivating, tracker.headsetState)
        assertTrue(tracker.isConnected) // AudioActivating is still connected
    }

    @Test
    fun `setAudioActivating from Disconnected does nothing`() {
        tracker.setAudioActivating()
        assertEquals(BluetoothStateTracker.HeadsetState.Disconnected, tracker.headsetState)
    }

    @Test
    fun `setAudioActivationError sets error state`() {
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        tracker.setAudioActivationError("Timeout")

        assertTrue(tracker.headsetState is BluetoothStateTracker.HeadsetState.AudioActivationError)
        assertTrue(tracker.hasActivationError())
        // Error state is still "connected"
        assertTrue(tracker.isConnected)
    }

    @Test
    fun `hasActivationError returns false after successful connection`() {
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        tracker.setAudioActivationError("Timeout")
        assertTrue(tracker.hasActivationError())

        // Successful audio connection clears error state
        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_CONNECTED)
        assertFalse(tracker.hasActivationError())
    }

    // ==================== Reset Tests ====================

    @Test
    fun `reset clears all state`() {
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_CONNECTED)
        tracker.setAudioActivationError("Error")

        tracker.reset()

        assertEquals(BluetoothStateTracker.HeadsetState.Disconnected, tracker.headsetState)
        assertFalse(tracker.isConnected)
        assertFalse(tracker.isAudioActive)
        assertFalse(tracker.hasActivationError())
    }

    // ==================== Edge Cases ====================

    @Test
    fun `multiple CONNECTED events do not change state`() {
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        assertEquals(BluetoothStateTracker.HeadsetState.Connected, tracker.headsetState)

        // Audio activating
        tracker.setAudioActivating()
        assertEquals(BluetoothStateTracker.HeadsetState.AudioActivating, tracker.headsetState)

        // Another CONNECTED event should not reset to Connected
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        assertEquals(BluetoothStateTracker.HeadsetState.AudioActivating, tracker.headsetState)
    }

    @Test
    fun `STATE_AUDIO_CONNECTING is ignored`() {
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_CONNECTING)

        // Should still be in Connected state, not AudioActivated
        assertEquals(BluetoothStateTracker.HeadsetState.Connected, tracker.headsetState)
    }

    @Test
    fun `reconnect after disconnect works correctly`() {
        // First connection
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_CONNECTED)
        assertTrue(tracker.isAudioActive)

        // Disconnect
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_DISCONNECTED)
        assertFalse(tracker.isConnected)

        // Reconnect
        tracker.onConnectionStateChanged(BluetoothProfile.STATE_CONNECTED)
        assertTrue(tracker.isConnected)
        assertFalse(tracker.isAudioActive) // Audio not yet connected
        assertEquals(BluetoothStateTracker.HeadsetState.Connected, tracker.headsetState)
    }
}
