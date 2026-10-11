package app.tauri.camera

/**
 * Pure helpers for recording progress telemetry. Kept free of Android
 * framework dependencies so they can be unit-tested with plain JUnit.
 */
internal object RecordingMath {

  /** Minimum interval between progress messages (~4 Hz). */
  const val MIN_PROGRESS_INTERVAL_NANOS = 250_000_000L

  /**
   * Returns true when a progress message should be emitted: the values must
   * have changed and enough time must have passed since the last emission.
   */
  fun progressDue(lastEmitNanos: Long, nowNanos: Long, changed: Boolean): Boolean {
    if (!changed) return false
    if (lastEmitNanos == 0L) return true
    return nowNanos - lastEmitNanos >= MIN_PROGRESS_INTERVAL_NANOS
  }

  /**
   * Builds the telemetry payload. `bytesRecorded` and `bitrate` are omitted
   * while the recorded size is unknown (zero).
   */
  fun progressPayload(durationNanos: Long, bytesRecorded: Long): Map<String, Any> {
    val durationSec = durationNanos / 1_000_000_000.0
    val payload = mutableMapOf<String, Any>("duration" to durationSec)
    if (bytesRecorded > 0) {
      payload["bytesRecorded"] = bytesRecorded
      if (durationSec > 0) {
        payload["bitrate"] = bytesRecorded * 8.0 / durationSec
      }
    }
    return payload
  }
}
