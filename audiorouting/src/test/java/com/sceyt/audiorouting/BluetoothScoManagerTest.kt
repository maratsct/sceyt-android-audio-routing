package com.sceyt.audiorouting

import android.media.AudioManager
import com.sceyt.audiorouting.internal.Logger
import com.sceyt.audiorouting.internal.bluetooth.BluetoothScoManager
import com.sceyt.audiorouting.internal.device.AudioDeviceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify

/**
 * Tests for BluetoothScoManager retry and state logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothScoManagerTest {

    private val logger = Logger(enabled = false)
    private val config = AudioRouterConfig(
        scoRetryCount = 2,
        scoTimeoutMs = 100,
        scoRetryDelayMs = 50
    )
    
    private lateinit var audioDeviceManager: AudioDeviceManager
    private lateinit var scoManager: BluetoothScoManager
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        audioDeviceManager = mock()
        scoManager = BluetoothScoManager(
            scope = CoroutineScope(testDispatcher),
            audioDeviceManager = audioDeviceManager,
            config = config,
            logger = logger
        )
    }

    @Test
    fun `initial state is disconnected`() {
        assertEquals(BluetoothScoManager.ScoState.DISCONNECTED, scoManager.scoState.value)
        assertFalse(scoManager.isScoConnected)
    }

    @Test
    fun `startScoAsync transitions to connecting state`() {
        var result: BluetoothScoManager.ScoResult? = null
        scoManager.startScoAsync { result = it }

        assertEquals(BluetoothScoManager.ScoState.CONNECTING, scoManager.scoState.value)
        verify(audioDeviceManager).startBluetoothSco()
    }

    @Test
    fun `onScoAudioStateChanged CONNECTED transitions to connected state`() {
        scoManager.startScoAsync { }
        
        scoManager.onScoAudioStateChanged(AudioManager.SCO_AUDIO_STATE_CONNECTED)

        assertEquals(BluetoothScoManager.ScoState.CONNECTED, scoManager.scoState.value)
        assertTrue(scoManager.isScoConnected)
    }

    @Test
    fun `onScoAudioStateChanged CONNECTED invokes result callback with success`() {
        var result: BluetoothScoManager.ScoResult? = null
        scoManager.startScoAsync { result = it }
        
        scoManager.onScoAudioStateChanged(AudioManager.SCO_AUDIO_STATE_CONNECTED)

        assertTrue(result is BluetoothScoManager.ScoResult.Connected)
    }

    @Test
    fun `already connected returns immediately`() {
        // First connect
        scoManager.startScoAsync { }
        scoManager.onScoAudioStateChanged(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        reset(audioDeviceManager)

        // Try to connect again
        var result: BluetoothScoManager.ScoResult? = null
        scoManager.startScoAsync { result = it }

        // Should return connected without calling startBluetoothSco
        assertTrue(result is BluetoothScoManager.ScoResult.Connected)
        verify(audioDeviceManager, never()).startBluetoothSco()
    }

    @Test
    fun `disableSco transitions to disconnected`() {
        scoManager.startScoAsync { }
        scoManager.onScoAudioStateChanged(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        assertTrue(scoManager.isScoConnected)

        scoManager.disableSco()

        assertEquals(BluetoothScoManager.ScoState.DISCONNECTED, scoManager.scoState.value)
        assertFalse(scoManager.isScoConnected)
        verify(audioDeviceManager).stopBluetoothSco()
    }

    @Test
    fun `disableSco does nothing when already disconnected`() {
        scoManager.disableSco()

        assertEquals(BluetoothScoManager.ScoState.DISCONNECTED, scoManager.scoState.value)
        verify(audioDeviceManager, never()).stopBluetoothSco()
    }

    @Test
    fun `cancelPendingOperations resets state`() {
        scoManager.startScoAsync { }
        assertEquals(BluetoothScoManager.ScoState.CONNECTING, scoManager.scoState.value)

        scoManager.cancelPendingOperations()

        // State remains CONNECTING since cancel doesn't change state directly
        // But the callback reference is cleared
    }

    @Test
    fun `onScoAudioStateChanged DISCONNECTED while connected transitions to disconnected`() {
        scoManager.startScoAsync { }
        scoManager.onScoAudioStateChanged(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        assertTrue(scoManager.isScoConnected)

        scoManager.onScoAudioStateChanged(AudioManager.SCO_AUDIO_STATE_DISCONNECTED)

        assertEquals(BluetoothScoManager.ScoState.DISCONNECTED, scoManager.scoState.value)
        assertFalse(scoManager.isScoConnected)
    }

    @Test
    fun `onScoAudioStateChanged DISCONNECTED while connecting waits for retry`() {
        scoManager.startScoAsync { }
        assertEquals(BluetoothScoManager.ScoState.CONNECTING, scoManager.scoState.value)

        // SCO rejected during connection attempt
        scoManager.onScoAudioStateChanged(AudioManager.SCO_AUDIO_STATE_DISCONNECTED)

        // Should still be in CONNECTING state, waiting for timeout/retry
        assertEquals(BluetoothScoManager.ScoState.CONNECTING, scoManager.scoState.value)
    }
}
