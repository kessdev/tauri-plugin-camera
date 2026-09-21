//! Grants webview media permissions (camera / microphone) on desktop.
//!
//! wry only registers a webview permission handler for the clipboard, so
//! WebView2 (Windows) denies `getUserMedia` without showing a prompt, and
//! webkitgtk (Linux) may do the same. This module installs a handler from the
//! plugin so camera and microphone requests are allowed, matching what the
//! plugin advertises in its README. The real gate remains the operating
//! system privacy settings.

/// Whether the plugin should grant a webview permission request.
///
/// Only camera and microphone are granted; every other permission is left to
/// the host's default handling.
pub(crate) fn should_grant(is_camera: bool, is_microphone: bool) -> bool {
  is_camera || is_microphone
}

/// Installs the media permission handler for the given desktop webview.
#[cfg(desktop)]
pub(crate) fn install<R: tauri::Runtime>(webview: &tauri::Webview<R>) {
  #[cfg(target_os = "windows")]
  install_windows(webview);

  #[cfg(target_os = "linux")]
  install_linux(webview);

  #[cfg(not(any(target_os = "windows", target_os = "linux")))]
  {
    // macOS: wry already handles `requestMediaCapturePermission`; the app only
    // needs `NSCameraUsageDescription` / `NSMicrophoneUsageDescription`.
    let _ = webview;
  }
}

#[cfg(target_os = "windows")]
fn install_windows<R: tauri::Runtime>(webview: &tauri::Webview<R>) {
  use webview2_com::Microsoft::Web::WebView2::Win32::*;
  use webview2_com::PermissionRequestedEventHandler;

  let _ = webview.with_webview(|platform| unsafe {
    let Ok(core) = platform.controller().CoreWebView2() else {
      return;
    };
    let mut token = 0;
    let _ = core.add_PermissionRequested(
      &PermissionRequestedEventHandler::create(Box::new(|_, args| {
        let Some(args) = args else { return Ok(()) };
        let mut kind = COREWEBVIEW2_PERMISSION_KIND::default();
        args.PermissionKind(&mut kind)?;
        let is_camera = kind == COREWEBVIEW2_PERMISSION_KIND_CAMERA;
        let is_microphone = kind == COREWEBVIEW2_PERMISSION_KIND_MICROPHONE;
        if should_grant(is_camera, is_microphone) {
          args.SetState(COREWEBVIEW2_PERMISSION_STATE_ALLOW)?;
        }
        Ok(())
      })),
      &mut token,
    );
  });
}

#[cfg(target_os = "linux")]
fn install_linux<R: tauri::Runtime>(webview: &tauri::Webview<R>) {
  use webkit2gtk::glib::prelude::*;
  use webkit2gtk::{
    PermissionRequestExt, UserMediaPermissionRequest, UserMediaPermissionRequestExt, WebViewExt,
  };

  let _ = webview.with_webview(|platform| {
    let webview = platform.inner();
    webview.connect_permission_request(|_, request| {
      if let Some(media) = request.downcast_ref::<UserMediaPermissionRequest>() {
        let is_camera = media.is_for_video_device();
        let is_microphone = media.is_for_audio_device();
        if should_grant(is_camera, is_microphone) {
          request.allow();
          return true;
        }
      }
      false
    });
  });
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn grants_camera() {
    assert!(should_grant(true, false));
  }

  #[test]
  fn grants_microphone() {
    assert!(should_grant(false, true));
  }

  #[test]
  fn grants_camera_and_microphone() {
    assert!(should_grant(true, true));
  }

  #[test]
  fn denies_unrelated_permissions() {
    assert!(!should_grant(false, false));
  }
}
