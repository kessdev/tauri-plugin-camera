use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum CameraDirection {
  Front,
  Back,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum FlashMode {
  On,
  Off,
  Auto,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StartPreviewOptions {
  pub camera: Option<CameraDirection>,
  pub camera_id: Option<String>,
  pub windowed: Option<bool>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CaptureOptions {
  pub flash: Option<FlashMode>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FlashOptions {
  pub mode: FlashMode,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ZoomOptions {
  pub factor: f64,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TorchOptions {
  pub on: bool,
}

/// Normalized focus point (0..1, top-left origin).
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FocusPointOptions {
  pub x: f64,
  pub y: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum FocusMode {
  Auto,
  Continuous,
  Locked,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FocusModeOptions {
  pub mode: FocusMode,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExposureOptions {
  pub ev: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ExposureMode {
  Auto,
  Locked,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExposureModeOptions {
  pub mode: ExposureMode,
}

/// Supported exposure compensation range (EV).
#[derive(Debug, Clone, Default, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExposureRange {
  pub min_ev: f64,
  pub max_ev: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum WhiteBalanceMode {
  Auto,
  Locked,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WhiteBalanceOptions {
  pub mode: WhiteBalanceMode,
  #[serde(skip_serializing_if = "Option::is_none")]
  pub temperature_k: Option<u32>,
}

/// Result of a photo capture.
///
/// On mobile, `path` points to a temporary file written by the native backend
/// and `thumbnail` optionally holds a base64-encoded preview. On desktop the
/// fields are populated by the webview backend (see `guest-js/index.ts`).
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Photo {
  pub path: String,
  pub width: u32,
  pub height: u32,
  pub orientation: u32,
  pub format: String,
  pub thumbnail: Option<String>,
}

/// Result of a video recording.
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Video {
  pub path: String,
  pub duration: f64,
  /// Optional base64-encoded thumbnail (JPEG).
  #[serde(default, skip_serializing_if = "Option::is_none")]
  pub thumbnail: Option<String>,
}

/// Supported zoom range of a camera (declarative, without the live value).
#[derive(Debug, Clone, Default, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ZoomRange {
  pub min: f64,
  pub max: f64,
}

/// Orientation of a camera.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum CameraFacing {
  Front,
  Back,
  External,
}

/// A camera (physical or logical lens) available on the device.
#[derive(Debug, Clone, Default, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CameraInfo {
  /// Native, session-stable identifier.
  pub id: String,
  #[serde(skip_serializing_if = "Option::is_none")]
  pub facing: Option<CameraFacing>,
  #[serde(skip_serializing_if = "Option::is_none")]
  pub lens: Option<String>,
  #[serde(skip_serializing_if = "Option::is_none")]
  pub label: Option<String>,
}

/// Declarative capabilities of a camera: what the hardware/platform *can* do.
///
/// Optional fields are omitted when the platform cannot determine them. Live
/// values (e.g. the current zoom factor) are exposed by dedicated getters.
#[derive(Debug, Clone, Default, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CameraCapabilities {
  #[serde(skip_serializing_if = "Option::is_none")]
  pub camera_id: Option<String>,
  pub has_flash: bool,
  pub has_torch: bool,
  #[serde(skip_serializing_if = "Option::is_none")]
  pub zoom_range: Option<ZoomRange>,
  pub supports_focus: bool,
  #[serde(skip_serializing_if = "Option::is_none")]
  pub focus_modes: Option<Vec<String>>,
  #[serde(skip_serializing_if = "Option::is_none")]
  pub exposure_range: Option<ExposureRange>,
  #[serde(skip_serializing_if = "Option::is_none")]
  pub exposure_modes: Option<Vec<ExposureMode>>,
  #[serde(skip_serializing_if = "Option::is_none")]
  pub white_balance_modes: Option<Vec<WhiteBalanceMode>>,
  #[serde(skip_serializing_if = "Option::is_none")]
  pub supports_white_balance_temperature: Option<bool>,
  #[serde(skip_serializing_if = "Option::is_none")]
  pub photo_formats: Option<Vec<String>>,
  pub supports_pause: bool,
}

/// Live telemetry emitted while a video recording is in progress.
///
/// Delivered through the optional `onProgress` channel passed to
/// `start_recording`. Optional fields are omitted when the platform cannot
/// determine them at that moment.
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecordingProgress {
  pub duration: f64,
  #[serde(skip_serializing_if = "Option::is_none")]
  pub bytes_recorded: Option<u64>,
  #[serde(skip_serializing_if = "Option::is_none")]
  pub bitrate: Option<f64>,
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn deserializes_start_preview_options() {
    let opts: StartPreviewOptions =
      serde_json::from_str(r#"{"camera":"front","windowed":true}"#).unwrap();
    assert_eq!(opts.camera, Some(CameraDirection::Front));
    assert_eq!(opts.camera_id, None);
    assert_eq!(opts.windowed, Some(true));
  }

  #[test]
  fn deserializes_start_preview_camera_id() {
    let opts: StartPreviewOptions =
      serde_json::from_str(r#"{"cameraId":"lens-2","camera":"back"}"#).unwrap();
    assert_eq!(opts.camera_id.as_deref(), Some("lens-2"));
    assert_eq!(opts.camera, Some(CameraDirection::Back));
  }

  #[test]
  fn serializes_flash_mode_lowercase() {
    assert_eq!(
      serde_json::to_string(&FlashMode::Auto).unwrap(),
      r#""auto""#
    );
    assert_eq!(
      serde_json::to_string(&CameraDirection::Back).unwrap(),
      r#""back""#
    );
  }

  #[test]
  fn deserializes_focus_point_options() {
    let opts: FocusPointOptions = serde_json::from_str(r#"{"x":0.5,"y":0.25}"#).unwrap();
    assert_eq!(opts.x, 0.5);
    assert_eq!(opts.y, 0.25);
  }

  #[test]
  fn serializes_control_modes_lowercase() {
    assert_eq!(
      serde_json::to_string(&FocusMode::Continuous).unwrap(),
      r#""continuous""#
    );
    assert_eq!(
      serde_json::to_string(&ExposureMode::Locked).unwrap(),
      r#""locked""#
    );
    assert_eq!(
      serde_json::to_string(&WhiteBalanceMode::Auto).unwrap(),
      r#""auto""#
    );
  }

  #[test]
  fn deserializes_white_balance_options_with_temperature() {
    let opts: WhiteBalanceOptions =
      serde_json::from_str(r#"{"mode":"locked","temperatureK":5000}"#).unwrap();
    assert_eq!(opts.mode, WhiteBalanceMode::Locked);
    assert_eq!(opts.temperature_k, Some(5000));
  }

  #[test]
  fn serializes_capabilities_with_exposure_and_white_balance() {
    let caps = CameraCapabilities {
      exposure_range: Some(ExposureRange {
        min_ev: -2.0,
        max_ev: 2.0,
      }),
      exposure_modes: Some(vec![ExposureMode::Auto, ExposureMode::Locked]),
      white_balance_modes: Some(vec![WhiteBalanceMode::Auto, WhiteBalanceMode::Locked]),
      ..Default::default()
    };
    let json = serde_json::to_string(&caps).unwrap();
    assert!(json.contains(r#""exposureRange":{"minEv":-2.0,"maxEv":2.0}"#));
    assert!(json.contains(r#""exposureModes":["auto","locked"]"#));
    assert!(json.contains(r#""whiteBalanceModes":["auto","locked"]"#));
  }

  #[test]
  fn deserializes_photo_camel_case() {
    let photo: Photo =
      serde_json::from_str(r#"{"path":"/tmp/a.jpg","width":1920,"height":1080,"orientation":1,"format":"jpeg","thumbnail":"base64"}"#)
        .unwrap();
    assert_eq!(photo.width, 1920);
    assert_eq!(photo.thumbnail.as_deref(), Some("base64"));
  }

  #[test]
  fn deserializes_video() {
    let video: Video = serde_json::from_str(r#"{"path":"/tmp/a.mp4","duration":3.5}"#).unwrap();
    assert_eq!(video.duration, 3.5);
    assert_eq!(video.thumbnail, None);
  }

  #[test]
  fn serializes_video_omitting_absent_thumbnail() {
    let video = Video {
      path: "/tmp/a.mp4".into(),
      duration: 3.5,
      thumbnail: None,
    };
    assert_eq!(
      serde_json::to_string(&video).unwrap(),
      r#"{"path":"/tmp/a.mp4","duration":3.5}"#
    );
  }

  #[test]
  fn deserializes_video_with_thumbnail() {
    let video: Video =
      serde_json::from_str(r#"{"path":"/tmp/a.mp4","duration":3.5,"thumbnail":"base64"}"#).unwrap();
    assert_eq!(video.thumbnail.as_deref(), Some("base64"));
  }

  #[test]
  fn round_trips_zoom_range_camel_case() {
    let range = ZoomRange { min: 1.0, max: 8.0 };
    let json = serde_json::to_string(&range).unwrap();
    assert_eq!(json, r#"{"min":1.0,"max":8.0}"#);
    let parsed: ZoomRange = serde_json::from_str(&json).unwrap();
    assert_eq!(parsed, range);
  }

  #[test]
  fn serializes_camera_info_omitting_absent_fields() {
    let camera = CameraInfo {
      id: "0".into(),
      facing: Some(CameraFacing::Back),
      lens: Some("wide".into()),
      label: None,
    };
    assert_eq!(
      serde_json::to_string(&camera).unwrap(),
      r#"{"id":"0","facing":"back","lens":"wide"}"#
    );
  }

  #[test]
  fn deserializes_camera_info_minimal() {
    let camera: CameraInfo = serde_json::from_str(r#"{"id":"1"}"#).unwrap();
    assert_eq!(camera.id, "1");
    assert_eq!(camera.facing, None);
    assert_eq!(camera.label, None);
  }

  #[test]
  fn serializes_capabilities_omitting_unknown_fields() {
    let caps = CameraCapabilities {
      camera_id: Some("0".into()),
      has_flash: true,
      has_torch: false,
      zoom_range: Some(ZoomRange { min: 1.0, max: 8.0 }),
      supports_focus: false,
      focus_modes: None,
      exposure_range: None,
      exposure_modes: None,
      white_balance_modes: None,
      supports_white_balance_temperature: None,
      photo_formats: None,
      supports_pause: false,
    };
    assert_eq!(
      serde_json::to_string(&caps).unwrap(),
      r#"{"cameraId":"0","hasFlash":true,"hasTorch":false,"zoomRange":{"min":1.0,"max":8.0},"supportsFocus":false,"supportsPause":false}"#
    );
  }

  #[test]
  fn deserializes_capabilities_minimal() {
    let caps: CameraCapabilities = serde_json::from_str(
      r#"{"hasFlash":false,"hasTorch":false,"supportsFocus":false,"supportsPause":true}"#,
    )
    .unwrap();
    assert!(!caps.has_flash);
    assert!(caps.supports_pause);
    assert_eq!(caps.zoom_range, None);
    assert_eq!(caps.focus_modes, None);
  }

  #[test]
  fn omits_absent_recording_progress_fields() {
    let progress = RecordingProgress {
      duration: 1.5,
      bytes_recorded: Some(2048),
      bitrate: None,
    };
    assert_eq!(
      serde_json::to_string(&progress).unwrap(),
      r#"{"duration":1.5,"bytesRecorded":2048}"#
    );
  }

  #[test]
  fn deserializes_recording_progress_camel_case() {
    let progress: RecordingProgress =
      serde_json::from_str(r#"{"duration":2.0,"bytesRecorded":1024,"bitrate":4096.0}"#).unwrap();
    assert_eq!(progress.duration, 2.0);
    assert_eq!(progress.bytes_recorded, Some(1024));
    assert_eq!(progress.bitrate, Some(4096.0));
  }
}
