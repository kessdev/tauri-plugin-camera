const COMMANDS: &[&str] = &[
  "start_preview",
  "stop_preview",
  "capture",
  "flip_camera",
  "set_flash",
  "set_zoom",
  "start_recording",
  "stop_recording",
  "save_to_gallery",
  "request_permissions",
  "check_permissions",
  "open_app_settings",
];

fn main() {
  tauri_plugin::Builder::new(COMMANDS)
    .android_path("android")
    .ios_path("ios")
    .build();
}
