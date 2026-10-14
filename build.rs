const COMMANDS: &[&str] = &[
  "start_preview",
  "stop_preview",
  "capture",
  "flip_camera",
  "set_flash",
  "set_zoom",
  "get_zoom",
  "set_torch",
  "set_focus_point",
  "set_focus_mode",
  "set_exposure_compensation",
  "set_exposure_mode",
  "set_white_balance",
  "get_available_cameras",
  "get_capabilities",
  "start_recording",
  "stop_recording",
  "save_to_gallery",
  "request_permissions",
  "check_permissions",
  "open_app_settings",
  "native_desktop_available",
];

fn main() {
  tauri_plugin::Builder::new(COMMANDS)
    .android_path("android")
    .ios_path("ios")
    .build();
}
