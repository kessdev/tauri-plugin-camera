package app.tauri.camera

import org.junit.Assert.assertEquals
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
}
