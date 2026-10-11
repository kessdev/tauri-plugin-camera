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

  private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm", "3gp", "mkv")

  /** Infers the media kind ("image" | "video") from the path, honoring an override. */
  fun mediaKind(path: String?, override: String?): String {
    if (override == "image" || override == "video") return override
    val extension = path?.substringAfterLast('.', "")?.lowercase() ?: ""
    return if (extension in VIDEO_EXTENSIONS) "video" else "image"
  }

  /** MIME type for the gallery insert, based on the file extension and media kind. */
  fun mimeType(path: String?, kind: String): String {
    val extension = path?.substringAfterLast('.', "")?.lowercase() ?: ""
    return when (extension) {
      "mp4", "m4v" -> "video/mp4"
      "mov" -> "video/quicktime"
      "webm" -> "video/webm"
      "3gp" -> "video/3gpp"
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      else -> if (kind == "video") "video/mp4" else "image/jpeg"
    }
  }
}
