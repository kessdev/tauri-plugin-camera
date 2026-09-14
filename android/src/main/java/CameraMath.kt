package app.tauri.camera

/**
 * Pure helpers for camera control logic. Kept free of Android framework
 * dependencies so they can be unit-tested with plain JUnit.
 */
internal object CameraMath {

  /** CameraX ImageCapture flash mode constants. */
  const val FLASH_OFF = 0
  const val FLASH_ON = 1
  const val FLASH_AUTO = 2

  fun flashModeCode(mode: String?): Int = when (mode) {
    "on" -> FLASH_ON
    "auto" -> FLASH_AUTO
    else -> FLASH_OFF
  }

  fun clampZoom(factor: Double, min: Double = 1.0, max: Double): Double =
    factor.coerceIn(min, max)

  fun cameraDirectionBack(direction: String?): Boolean = direction != "front"
}
