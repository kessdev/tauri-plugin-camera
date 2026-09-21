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
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn deserializes_start_preview_options() {
    let opts: StartPreviewOptions =
      serde_json::from_str(r#"{"camera":"front","windowed":true}"#).unwrap();
    assert_eq!(opts.camera, Some(CameraDirection::Front));
    assert_eq!(opts.windowed, Some(true));
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
  }
}
