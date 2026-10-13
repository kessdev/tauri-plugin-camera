---
"tauri-plugin-camera-kessdev": "patch"
"tauri-plugin-camera-kessdev-js": "patch"
---

Fix desktop video recording: keep the preview stream alive (reuse its video
track instead of stopping it), pick an explicit supported recording mime type
instead of falling back to WebM, and omit `bytesRecorded`/`bitrate` telemetry
until a real chunk arrives.
