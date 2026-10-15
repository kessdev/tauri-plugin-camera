use tauri::{
  plugin::{Builder, TauriPlugin},
  Manager, Runtime,
};

pub use models::*;

#[cfg(desktop)]
mod desktop;
#[cfg(all(desktop, feature = "native-desktop"))]
mod desktop_native;
#[cfg(mobile)]
mod mobile;

#[cfg(desktop)]
mod desktop_permissions;

mod error;
mod models;

pub use error::{Error, Result};

#[cfg(desktop)]
use desktop::Camera;
#[cfg(mobile)]
use mobile::Camera;

/// Extensions to [`tauri::App`], [`tauri::AppHandle`], [`tauri::WebviewWindow`],
/// [`tauri::Webview`] and [`tauri::Window`] to access the camera APIs.
pub trait CameraExt<R: Runtime> {
  fn camera(&self) -> &Camera<R>;
}

impl<R: Runtime, T: Manager<R>> crate::CameraExt<R> for T {
  fn camera(&self) -> &Camera<R> {
    self.state::<Camera<R>>().inner()
  }
}

/// Initializes the plugin.
pub fn init<R: Runtime>() -> TauriPlugin<R> {
  let builder = Builder::new("camera-kessdev")
    .setup(|app, api| {
      #[cfg(mobile)]
      let camera = mobile::init(app, api)?;
      #[cfg(desktop)]
      let camera = desktop::init(app, api)?;
      app.manage(camera);
      Ok(())
    })
    .on_webview_ready(|webview| {
      #[cfg(desktop)]
      desktop_permissions::install(&webview);
      #[cfg(not(desktop))]
      let _ = webview;
    });

  #[cfg(all(desktop, feature = "native-desktop"))]
  let builder = builder.invoke_handler(tauri::generate_handler![
    desktop::native_desktop_available,
    desktop_native::native_start_preview,
    desktop_native::native_stop_preview,
    desktop_native::native_start_recording,
    desktop_native::native_stop_recording,
    desktop_native::native_capture
  ]);

  #[cfg(all(desktop, not(feature = "native-desktop")))]
  let builder = builder.invoke_handler(tauri::generate_handler![desktop::native_desktop_available]);

  builder.build()
}
