use serde::de::DeserializeOwned;
use tauri::{plugin::PluginApi, AppHandle, Runtime};

pub fn init<R: Runtime, C: DeserializeOwned>(
  app: &AppHandle<R>,
  _api: PluginApi<R, C>,
) -> crate::Result<Camera<R>> {
  Ok(Camera(app.clone()))
}

/// Access to the camera APIs on desktop.
///
/// By default the camera is driven by the webview through
/// `navigator.mediaDevices.getUserMedia` (see `guest-js/index.ts`). With the
/// `native-desktop` feature the plugin owns the camera natively (capture ->
/// preview frames + native encoder); see `desktop_native`.
pub struct Camera<R: Runtime>(#[allow(dead_code)] AppHandle<R>);

/// Whether the native desktop camera backend is compiled into this build.
///
/// The frontend uses this to decide between the native backend and the webview
/// fallback (`getCapabilities().supportsNativeRecording`).
#[tauri::command]
pub fn native_desktop_available() -> bool {
  cfg!(all(target_os = "linux", feature = "native-desktop"))
}
