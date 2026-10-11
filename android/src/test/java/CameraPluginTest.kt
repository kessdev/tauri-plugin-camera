package app.tauri.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPluginTest {

  @Test
  fun flashModeMapsToCameraXConstants() {
    assertEquals(CameraMath.FLASH_ON, CameraMath.flashModeCode("on"))
    assertEquals(CameraMath.FLASH_AUTO, CameraMath.flashModeCode("auto"))
    assertEquals(CameraMath.FLASH_OFF, CameraMath.flashModeCode("off"))
    assertEquals(CameraMath.FLASH_OFF, CameraMath.flashModeCode(null))
  }

  @Test
  fun zoomIsClampedToSupportedRange() {
    assertEquals(1.0, CameraMath.clampZoom(0.0, max = 8.0), 0.0)
    assertEquals(4.0, CameraMath.clampZoom(4.0, max = 8.0), 0.0)
    assertEquals(8.0, CameraMath.clampZoom(100.0, max = 8.0), 0.0)
  }

  @Test
  fun cameraDirectionDefaultsToBack() {
    assertEquals(true, CameraMath.cameraDirectionBack("back"))
    assertEquals(true, CameraMath.cameraDirectionBack(null))
    assertEquals(false, CameraMath.cameraDirectionBack("front"))
  }

  @Test
  fun recordingProgressOmitsSizeWhenUnknown() {
    val payload = RecordingMath.progressPayload(durationNanos = 1_500_000_000L, bytesRecorded = 0L)
    assertEquals(1.5, payload["duration"] as Double, 1e-9)
    assertFalse(payload.containsKey("bytesRecorded"))
    assertFalse(payload.containsKey("bitrate"))
  }

  @Test
  fun recordingProgressIncludesSizeAndBitrate() {
    val payload = RecordingMath.progressPayload(durationNanos = 2_000_000_000L, bytesRecorded = 1000L)
    assertEquals(2.0, payload["duration"] as Double, 1e-9)
    assertEquals(1000L, payload["bytesRecorded"])
    assertEquals(4000.0, payload["bitrate"] as Double, 1e-9)
  }

  @Test
  fun progressThrottlingSkipsFrequentAndUnchangedMessages() {
    assertTrue(RecordingMath.progressDue(lastEmitNanos = 0L, nowNanos = 1L, changed = true))
    assertFalse(
      RecordingMath.progressDue(lastEmitNanos = 1_000_000_000L, nowNanos = 1_100_000_000L, changed = true)
    )
    assertTrue(
      RecordingMath.progressDue(lastEmitNanos = 1_000_000_000L, nowNanos = 1_300_000_000L, changed = true)
    )
    assertFalse(
      RecordingMath.progressDue(lastEmitNanos = 1_000_000_000L, nowNanos = 2_000_000_000L, changed = false)
    )
  }
}
