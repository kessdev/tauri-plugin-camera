---
"tauri-plugin-camera-kessdev": "minor"
"tauri-plugin-camera-kessdev-js": "minor"
---

Add an opt-in native desktop camera backend (Cargo feature `native-desktop`);
it owns the camera and captures once, fanning out to a live preview (JPEG frames
delivered to the frontend) and a native encoder, so the UI is not blocked and
`stopRecording()` does not freeze. Expose `startPreview({ onFrame })`, the
`PreviewFrame` type and `getCapabilities().supportsNativeRecording`.
