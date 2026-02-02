package com.sceyt.audiorouting

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sceyt.audiorouting.internal.Logger
import com.sceyt.audiorouting.internal.wired.WiredHeadsetHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for WiredHeadsetHandler.
 * Tests BroadcastReceiver registration and callback behavior.
 */
@RunWith(AndroidJUnit4::class)
class WiredHeadsetHandlerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var wiredHeadsetHandler: WiredHeadsetHandler
    private val logger = Logger(enabled = true)

    private var connectedCalled = false
    private var disconnectedCalled = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        connectedCalled = false
        disconnectedCalled = false
        
        wiredHeadsetHandler = WiredHeadsetHandler(
            context = context,
            logger = logger,
            onWiredHeadsetConnected = { connectedCalled = true },
            onWiredHeadsetDisconnected = { disconnectedCalled = true }
        )
    }

    @After
    fun tearDown() {
        wiredHeadsetHandler.stop()
    }

    @Test
    fun start_doesNotThrow() {
        // Should not throw when registering receiver
        wiredHeadsetHandler.start()
    }

    @Test
    fun stop_doesNotThrow() {
        wiredHeadsetHandler.start()
        // Should not throw when unregistering
        wiredHeadsetHandler.stop()
    }

    @Test
    fun stop_withoutStart_doesNotThrow() {
        // Should not throw even if never registered
        wiredHeadsetHandler.stop()
    }

    @Test
    fun doubleStart_doesNotThrow() {
        wiredHeadsetHandler.start()
        // Second start should be ignored or handled gracefully
        wiredHeadsetHandler.start()
    }

    @Test
    fun doubleStop_doesNotThrow() {
        wiredHeadsetHandler.start()
        wiredHeadsetHandler.stop()
        // Second stop should be ignored
        wiredHeadsetHandler.stop()
    }

    // Note: We cannot easily simulate actual headset plug/unplug events in instrumented tests
    // The following tests verify the handler doesn't crash when receiving broadcasts
    
    @Test
    fun simulatedHeadsetPlugBroadcast_handledWithoutCrash() = runBlocking {
        wiredHeadsetHandler.start()
        
        // Send a simulated headset plug intent
        // Note: This may not trigger the callback because the system doesn't allow
        // fake ACTION_HEADSET_PLUG intents from apps, but it verifies no crash
        val intent = Intent(AudioManager.ACTION_HEADSET_PLUG).apply {
            putExtra("state", 1) // plugged
            putExtra("name", "Test Headset")
            putExtra("microphone", 1)
        }
        
        try {
            context.sendBroadcast(intent)
        } catch (e: SecurityException) {
            // Expected - system doesn't allow this
        }
        
        delay(100)
        // Main thing is we didn't crash
    }

    @Test
    fun checkCurrentState_detectsHeadsetIfConnected() {
        wiredHeadsetHandler.start()
        
        // Check the current state via the property
        val isConnected = wiredHeadsetHandler.isConnected
        
        // Just verify we can query without crashing
        // The actual value depends on physical device state
        assertNotNull("Should return a boolean value", isConnected)
    }
}
