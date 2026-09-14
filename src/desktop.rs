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
/// On desktop the camera is driven by the webview through
/// `navigator.mediaDevices.getUserMedia` (see `guest-js/index.ts`), so this
/// type carries no capture state and exists only to keep the plugin API
/// symmetric across platforms.
pub struct Camera<R: Runtime>(AppHandle<R>);
