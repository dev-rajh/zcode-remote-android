package com.raj.zcoderemote.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedDeviceTest {

  private fun device(id: String, mid: String?) =
    PairedDevice(id = id, url = "https://zcode.z.ai/remote/v4?sid=$id&hash=H&t=1", name = "d", linkedAtMillis = 0, mid = mid)

  @Test
  fun sameSidIsSameMachine() {
    assertTrue(PairedDevice.sameMachine(device("s1", "m1"), device("s1", "m2")))
  }

  @Test
  fun sameMidDifferentSidIsSameMachine() {
    // Pairing reset rotates sid but keeps mid: treat as the same desktop.
    assertTrue(PairedDevice.sameMachine(device("s1", "MID"), device("s2", "MID")))
  }

  @Test
  fun differentMachineIsNotSame() {
    assertFalse(PairedDevice.sameMachine(device("s1", "m1"), device("s2", "m2")))
  }

  @Test
  fun nullMidFallsBackToSidComparison() {
    assertFalse(PairedDevice.sameMachine(device("s1", null), device("s2", null)))
    assertTrue(PairedDevice.sameMachine(device("s1", null), device("s1", null)))
  }

  @Test
  fun nullDevicesAreNeverSame() {
    assertFalse(PairedDevice.sameMachine(null, device("s1", "m1")))
    assertFalse(PairedDevice.sameMachine(device("s1", "m1"), null))
  }
}
