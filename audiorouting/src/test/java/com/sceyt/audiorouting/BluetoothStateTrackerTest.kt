package com.sceyt.audiorouting

import android.bluetooth.BluetoothHeadset
import com.sceyt.audiorouting.internal.bluetooth.BluetoothStateTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BluetoothStateTrackerTest {

    private lateinit var tracker: BluetoothStateTracker

    @Before
    fun setUp() {
        tracker = BluetoothStateTracker()
    }

    @Test
    fun `initial state is disconnected`() {
        assertEquals(BluetoothStateTracker.HeadsetState.Disconnected, tracker.headsetState)
        assertFalse(tracker.isConnected)
        assertFalse(tracker.isAudioActive)
    }

    @Test
    fun `onConnectionStateChanged with STATE_CONNECTED transitions to Connected`() {
        tracker.onConnectionStateChanged(BluetoothHeadset.STATE_CONNECTED)

        assertEquals(BluetoothStateTracker.HeadsetState.Connected, tracker.headsetState)
        assertTrue(tracker.isConnected)
        assertFalse(tracker.isAudioActive)
    }

    @Test
    fun `onConnectionStateChanged with STATE_DISCONNECTED transitions to Disconnected`() {
        tracker.onConnectionStateChanged(BluetoothHeadset.STATE_CONNECTED)
        tracker.onConnectionStateChanged(BluetoothHeadset.STATE_DISCONNECTED)

        assertEquals(BluetoothStateTracker.HeadsetState.Disconnected, tracker.headsetState)
        assertFalse(tracker.isConnected)
    }

    @Test
    fun `onAudioStateChanged with STATE_AUDIO_CONNECTED transitions to AudioActivated`() {
        tracker.onConnectionStateChanged(BluetoothHeadset.STATE_CONNECTED)
        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_CONNECTED)

        assertEquals(BluetoothStateTracker.HeadsetState.AudioActivated, tracker.headsetState)
        assertTrue(tracker.isConnected)
        assertTrue(tracker.isAudioActive)
    }

    @Test
    fun `onAudioStateChanged with STATE_AUDIO_DISCONNECTED transitions back to Connected`() {
        tracker.onConnectionStateChanged(BluetoothHeadset.STATE_CONNECTED)
        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_CONNECTED)
        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_DISCONNECTED)

        assertEquals(BluetoothStateTracker.HeadsetState.Connected, tracker.headsetState)
        assertTrue(tracker.isConnected)
        assertFalse(tracker.isAudioActive)
    }

    @Test
    fun `setAudioActivating transitions to AudioActivating when connected`() {
        tracker.onConnectionStateChanged(BluetoothHeadset.STATE_CONNECTED)
        tracker.setAudioActivating()

        assertEquals(BluetoothStateTracker.HeadsetState.AudioActivating, tracker.headsetState)
    }

    @Test
    fun `setAudioActivating does nothing when disconnected`() {
        tracker.setAudioActivating()

        assertEquals(BluetoothStateTracker.HeadsetState.Disconnected, tracker.headsetState)
    }

    @Test
    fun `setAudioActivationError sets error state`() {
        tracker.onConnectionStateChanged(BluetoothHeadset.STATE_CONNECTED)
        tracker.setAudioActivationError("Timeout")

        assertTrue(tracker.headsetState is BluetoothStateTracker.HeadsetState.AudioActivationError)
        assertTrue(tracker.hasActivationError())
    }

    @Test
    fun `hasActivationError returns true only for error state`() {
        assertFalse(tracker.hasActivationError())

        tracker.onConnectionStateChanged(BluetoothHeadset.STATE_CONNECTED)
        assertFalse(tracker.hasActivationError())

        tracker.setAudioActivationError("Error")
        assertTrue(tracker.hasActivationError())
    }

    @Test
    fun `reset returns to disconnected state`() {
        tracker.onConnectionStateChanged(BluetoothHeadset.STATE_CONNECTED)
        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_CONNECTED)
        tracker.reset()

        assertEquals(BluetoothStateTracker.HeadsetState.Disconnected, tracker.headsetState)
        assertFalse(tracker.isConnected)
        assertFalse(tracker.isAudioActive)
    }

    @Test
    fun `isConnected returns true for all connected states`() {
        tracker.onConnectionStateChanged(BluetoothHeadset.STATE_CONNECTED)
        assertTrue(tracker.isConnected)

        tracker.setAudioActivating()
        assertTrue(tracker.isConnected)

        tracker.onAudioStateChanged(BluetoothHeadset.STATE_AUDIO_CONNECTED)
        assertTrue(tracker.isConnected)

        tracker.setAudioActivationError("Error")
        assertTrue(tracker.isConnected)
    }
}
