# tauri-plugin-camera (iOS)

Swift implementation of the camera plugin for iOS, using AVFoundation.

## Requirements

Add the following usage descriptions to the app's `Info.plist`:

- `NSCameraUsageDescription`
- `NSMicrophoneUsageDescription` (required for video recording with audio)
- `NSPhotoLibraryAddUsageDescription` (required for `saveToGallery`)
