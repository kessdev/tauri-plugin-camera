# Changelog

## [0.2.0-beta.1]

- [`3852d23`](https://github.com/kessdev/tauri-plugin-camera/commit/3852d23b084ba635cfe7f5d2719144b05c8b4d16) Add fine camera controls: torch (continuous light), tap-to-focus and focus mode,
    exposure compensation and mode, and white balance (mode + Kelvin temperature).
    Extend `getCapabilities()` with `exposureRange`, `exposureModes` and
    `whiteBalanceModes`.
- [`700bb9b`](https://github.com/kessdev/tauri-plugin-camera/commit/700bb9b40ae2ad685bd4b70855e83794e0f8f1aa) Add `startPreview({ cameraId })` to open the preview with a specific camera/lens
    (id from `getAvailableCameras()`); `cameraId` takes precedence over `camera`
    (front/back).
- [`700bb9b`](https://github.com/kessdev/tauri-plugin-camera/commit/700bb9b40ae2ad685bd4b70855e83794e0f8f1aa) Add capture configuration: `capture({ resolution, aspectRatio, format, quality })`
    and `startRecording({ quality, fps, bitrate })`. Extend `getCapabilities()` with
    `photoResolutions`, `photoAspects`, `videoQualities` and `videoFps`.

## [0.2.0-beta.0]

- [`b5ef30d`](https://github.com/kessdev/tauri-plugin-camera/commit/b5ef30d40496f223f47137173b6de22b867e5ca6) Add `getAvailableCameras()` and `getCapabilities()` for camera introspection.
    Remove `isFlashAvailable()` and `getZoomRange()` in favor of the unified
    declarative capabilities surface (use `getCapabilities().hasFlash` /
    `getCapabilities().zoomRange`, and `getZoom()` for the live value).

## [0.1.1-beta.4]

- [`768f012`](https://github.com/kessdev/tauri-plugin-camera/commit/768f01258a9d631e539390a744cfee79e4e77ec3) Add `getZoom`/`getZoomRange`/`isFlashAvailable`, save videos to the gallery,
    and opt-in video thumbnails via `stopRecording({ thumbnail: true })`.
- [`768f012`](https://github.com/kessdev/tauri-plugin-camera/commit/768f01258a9d631e539390a744cfee79e4e77ec3) Add optional live recording progress telemetry via an `onProgress` channel in
    `startRecording`.

## [0.1.1-beta.3]

- [`ab91377`](https://github.com/kessdev/tauri-plugin-camera/commit/ab913779ac92c4cca0864d0804e4b49324d01aa1) Align the desktop `webview2-com` dependency with the version wry links against,
    restoring the Windows build after the `tauri` 2.12 upgrade.

## [0.1.1-beta.2]

- [`95cc201`](https://github.com/kessdev/tauri-plugin-camera/commit/95cc201f19ae623bfcedf3e5b9e4569f17e3010f) Restore the desktop camera and microphone webview permission handler that was
    dropped from main and shipped without it in 0.1.1-beta.1.

## [0.1.1-beta.1]

- [`196aca2`](https://github.com/kessdev/tauri-plugin-camera/commit/196aca2e1949af3284d3e998de1c2068f4063942) Grant camera and microphone webview permission on desktop (Windows and Linux).

## [0.1.1-beta.0]

- [`6e012b4`](https://github.com/kessdev/tauri-plugin-camera/commit/6e012b42e14af9b6b47c996544e5af3a3cc78b22) First beta release to validate the automated release pipeline.
