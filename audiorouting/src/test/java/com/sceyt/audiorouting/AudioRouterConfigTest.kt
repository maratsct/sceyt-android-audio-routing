package com.sceyt.audiorouting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioRouterConfigTest {

    @Test
    fun `default config has correct values`() {
        val config = AudioRouterConfig()

        assertEquals(4, config.preferredDeviceOrder.size)
        assertEquals(AudioDevice.BluetoothHeadset::class, config.preferredDeviceOrder[0])
        assertEquals(AudioDevice.WiredHeadset::class, config.preferredDeviceOrder[1])
        assertEquals(AudioDevice.Earpiece::class, config.preferredDeviceOrder[2])
        assertEquals(AudioDevice.Speakerphone::class, config.preferredDeviceOrder[3])
        assertFalse(config.loggingEnabled)
        assertEquals(3, config.scoRetryCount)
        assertEquals(500L, config.scoRetryDelayMs)
        assertEquals(5000L, config.scoTimeoutMs)
        assertEquals(300L, config.debounceDelayMs)
    }

    @Test
    fun `custom config values are preserved`() {
        val customOrder = listOf(
            AudioDevice.Speakerphone::class,
            AudioDevice.Earpiece::class
        )
        val config = AudioRouterConfig(
            preferredDeviceOrder = customOrder,
            loggingEnabled = true,
            scoRetryCount = 5,
            scoRetryDelayMs = 1000L,
            scoTimeoutMs = 10000L,
            debounceDelayMs = 500L
        )

        assertEquals(customOrder, config.preferredDeviceOrder)
        assertEquals(true, config.loggingEnabled)
        assertEquals(5, config.scoRetryCount)
        assertEquals(1000L, config.scoRetryDelayMs)
        assertEquals(10000L, config.scoTimeoutMs)
        assertEquals(500L, config.debounceDelayMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws on empty preferred device order`() {
        AudioRouterConfig(preferredDeviceOrder = emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws on duplicate preferred devices`() {
        AudioRouterConfig(
            preferredDeviceOrder = listOf(
                AudioDevice.Speakerphone::class,
                AudioDevice.Speakerphone::class
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws on negative sco retry count`() {
        AudioRouterConfig(scoRetryCount = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws on zero sco retry delay`() {
        AudioRouterConfig(scoRetryDelayMs = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws on negative sco retry delay`() {
        AudioRouterConfig(scoRetryDelayMs = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws on zero sco timeout`() {
        AudioRouterConfig(scoTimeoutMs = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws on negative debounce delay`() {
        AudioRouterConfig(debounceDelayMs = -1)
    }

    @Test
    fun `allows zero sco retry count`() {
        val config = AudioRouterConfig(scoRetryCount = 0)
        assertEquals(0, config.scoRetryCount)
    }

    @Test
    fun `allows zero debounce delay`() {
        val config = AudioRouterConfig(debounceDelayMs = 0)
        assertEquals(0L, config.debounceDelayMs)
    }
}
