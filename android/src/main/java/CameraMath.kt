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

  /** CameraMetadata LENS_FACING_* values. */
  const val LENS_FACING_FRONT = 0
  const val LENS_FACING_BACK = 1
  const val LENS_FACING_EXTERNAL = 2

  fun facingFromLensFacing(lensFacing: Int?): String? = when (lensFacing) {
    LENS_FACING_FRONT -> "front"
    LENS_FACING_BACK -> "back"
    LENS_FACING_EXTERNAL -> "external"
    else -> null
  }

  /** CameraMetadata CONTROL_AF_MODE_* values. */
  const val AF_MODE_OFF = 0
  const val AF_MODE_AUTO = 1
  const val AF_MODE_MACRO = 2
  const val AF_MODE_CONTINUOUS_VIDEO = 3
  const val AF_MODE_CONTINUOUS_PICTURE = 4
  const val AF_MODE_EDOF = 5

  /** Maps Android AF modes to the plugin's declarative focus modes. */
  fun focusModesFromAfModes(modes: IntArray?): List<String> {
    if (modes == null) return emptyList()
    val result = LinkedHashSet<String>()
    for (mode in modes) {
      when (mode) {
        AF_MODE_AUTO, AF_MODE_MACRO -> result.add("auto")
        AF_MODE_CONTINUOUS_VIDEO, AF_MODE_CONTINUOUS_PICTURE -> result.add("continuous")
        else -> {}
      }
    }
    return result.toList()
  }

  /**
   * Best-effort lens hint from a 35mm-equivalent focal length (mm):
   * ultraWide < 24, wide 24..49, telephoto >= 50. Null when unknown.
   */
  fun lensFromEquivalentFocalLength(equivalentMm: Float): String? = when {
    equivalentMm <= 0f -> null
    equivalentMm < 24f -> "ultraWide"
    equivalentMm < 50f -> "wide"
    else -> "telephoto"
  }

  /** Converts a focal length (mm) to its 35mm equivalent, or null if unknown. */
  fun toEquivalentFocalLength(focalMm: Float, sensorWidthMm: Float): Float? =
    if (focalMm > 0f && sensorWidthMm > 0f) focalMm * 36f / sensorWidthMm else null

  /**
   * Resolves a requested camera id against the available ids. Returns null when no
   * id was requested, the id when present, or throws when it is not available.
   */
  fun resolveCameraId(availableIds: List<String>, requested: String?): String? {
    if (requested == null) return null
    if (requested in availableIds) return requested
    throw IllegalArgumentException("No camera available for cameraId: $requested")
  }

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
