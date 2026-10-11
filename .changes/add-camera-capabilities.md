---
"tauri-plugin-camera-kessdev": "minor"
"tauri-plugin-camera-kessdev-js": "minor"
---

Add `getAvailableCameras()` and `getCapabilities()` for camera introspection.
Remove `isFlashAvailable()` and `getZoomRange()` in favor of the unified
declarative capabilities surface (use `getCapabilities().hasFlash` /
`getCapabilities().zoomRange`, and `getZoom()` for the live value).
