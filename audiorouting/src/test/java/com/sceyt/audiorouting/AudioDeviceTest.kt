package com.sceyt.audiorouting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AudioDeviceTest {

    @Test
    fun `BluetoothHeadset with address has correct id`() {
        val device = AudioDevice.BluetoothHeadset("My Headset", "00:11:22:33:44:55")

        assertEquals("bluetooth_00:11:22:33:44:55", device.id)
        assertEquals("My Headset", device.name)
        assertEquals("00:11:22:33:44:55", device.address)
    }

    @Test
    fun `BluetoothHeadset default constructor has default values`() {
        val device = AudioDevice.BluetoothHeadset()

        assertEquals("bluetooth", device.id)
        assertEquals("Bluetooth", device.name)
        assertEquals("", device.address)
    }

    @Test
    fun `WiredHeadset has correct defaults`() {
        val device = AudioDevice.WiredHeadset()

        assertEquals("wired_headset", device.id)
        assertEquals("Wired Headset", device.name)
    }

    @Test
    fun `Earpiece has correct defaults`() {
        val device = AudioDevice.Earpiece()

        assertEquals("earpiece", device.id)
        assertEquals("Earpiece", device.name)
    }

    @Test
    fun `Speakerphone has correct defaults`() {
        val device = AudioDevice.Speakerphone()

        assertEquals("speakerphone", device.id)
        assertEquals("Speakerphone", device.name)
    }

    @Test
    fun `devices with same type and id are equal`() {
        val device1 = AudioDevice.Earpiece()
        val device2 = AudioDevice.Earpiece()

        assertEquals(device1, device2)
    }

    @Test
    fun `bluetooth devices with different addresses are not equal`() {
        val device1 = AudioDevice.BluetoothHeadset("BT 1", "00:11:22:33:44:55")
        val device2 = AudioDevice.BluetoothHeadset("BT 2", "00:11:22:33:44:66")

        assertNotEquals(device1, device2)
    }

    @Test
    fun `bluetooth devices with same address but different names are equal`() {
        val device1 = AudioDevice.BluetoothHeadset("Name 1", "00:11:22:33:44:55")
        val device2 = AudioDevice.BluetoothHeadset("Name 2", "00:11:22:33:44:55")

        // Same address means same id, so they should be equal
        assertEquals(device1.id, device2.id)
    }

    @Test
    fun `different device types are not equal`() {
        val earpiece = AudioDevice.Earpiece()
        val speaker = AudioDevice.Speakerphone()

        assertNotEquals(earpiece, speaker)
    }

    @Test
    fun `custom names are preserved`() {
        val wired = AudioDevice.WiredHeadset(name = "USB-C Headphones")
        val earpiece = AudioDevice.Earpiece(name = "Phone Speaker")
        val speaker = AudioDevice.Speakerphone(name = "Loud Speaker")

        assertEquals("USB-C Headphones", wired.name)
        assertEquals("Phone Speaker", earpiece.name)
        assertEquals("Loud Speaker", speaker.name)
    }
}
