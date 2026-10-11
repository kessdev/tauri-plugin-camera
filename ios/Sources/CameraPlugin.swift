// Copyright 2024-present

import AVFoundation
import Photos
import Tauri
import UIKit
import WebKit

struct StartPreviewOptions: Decodable {
  var camera: String?
  var windowed: Bool?
}

struct CaptureOptions: Decodable {
  var flash: String?
}

struct FlashOptions: Decodable {
  var mode: String?
}

struct ZoomOptions: Decodable {
  var factor: Double?
}

struct SaveToGalleryOptions: Decodable {
  var path: String?
  var type: String?
}

struct StartRecordingOptions: Decodable {
  var onProgress: Channel?
}

struct StopRecordingOptions: Decodable {
  var thumbnail: Bool?
}

struct GetCapabilitiesOptions: Decodable {
  var cameraId: String?
}

/// Encodable payload for a camera entry returned by `getAvailableCameras`.
struct CameraInfoPayload: Encodable {
  let id: String
  let lens: String
  let label: String
  let facing: String?
}

private let videoExtensions: Set<String> = ["mp4", "mov", "m4v", "webm", "3gp", "mkv"]

/// Whether the file at `path` is a video, honoring an explicit type override.
func mediaIsVideo(path: String, override: String?) -> Bool {
  if override == "video" { return true }
  if override == "image" { return false }
  let ext = (path as NSString).pathExtension.lowercased()
  return videoExtensions.contains(ext)
}

/// Maps a capture device position to the plugin's facing value.
func facingName(for position: AVCaptureDevice.Position) -> String? {
  switch position {
  case .front: return "front"
  case .back: return "back"
  default: return nil
  }
}

/// Best-effort lens hint from an iOS capture device type.
func lensName(for deviceType: AVCaptureDevice.DeviceType) -> String {
  if deviceType == .builtInUltraWideCamera { return "ultraWide" }
  if deviceType == .builtInTelephotoCamera { return "telephoto" }
  return "wide"
}

/// Maps iOS focus modes to the plugin's declarative focus modes.
func focusModeNames(from modes: [AVCaptureDevice.FocusMode]) -> [String] {
  var result: [String] = []
  for mode in modes {
    let name: String?
    switch mode {
    case .autoFocus: name = "auto"
    case .continuousAutoFocus: name = "continuous"
    case .locked: name = "locked"
    default: name = nil
    }
    if let name = name, !result.contains(name) {
      result.append(name)
    }
  }
  return result
}

/// Live telemetry emitted while a video recording is in progress.
struct RecordingProgress: Codable, Equatable {
  let duration: Double
  let bytesRecorded: Int64?
  let bitrate: Double?
}

/// Builds a telemetry payload. `bytesRecorded` and `bitrate` are omitted while
/// the recorded size is unknown (zero).
func makeRecordingProgress(duration: Double, bytesRecorded: Int64) -> RecordingProgress {
  guard bytesRecorded > 0 else {
    return RecordingProgress(duration: duration, bytesRecorded: nil, bitrate: nil)
  }
  let bitrate = duration > 0 ? Double(bytesRecorded) * 8.0 / duration : nil
  return RecordingProgress(duration: duration, bytesRecorded: bytesRecorded, bitrate: bitrate)
}

enum CaptureError: Error {
  case cameraUnavailable
  case couldNotConfigureInput
}

class CameraPlugin: Plugin, AVCapturePhotoCaptureDelegate, AVCaptureFileOutputRecordingDelegate {
  var webView: WKWebView!
  var cameraView: CameraView!
  var captureSession: AVCaptureSession?
  var captureVideoPreviewLayer: AVCaptureVideoPreviewLayer?
  var photoOutput: AVCapturePhotoOutput?
  var movieOutput: AVCaptureMovieFileOutput?

  var frontCamera: AVCaptureDevice?
  var backCamera: AVCaptureDevice?
  var currentCamera: AVCaptureDevice?

  var windowed = false
  var previousBackgroundColor: UIColor? = UIColor.white
  var flashMode: AVCaptureDevice.FlashMode = .off

  var photoInvoke: Invoke?
  var stopRecordingInvoke: Invoke?
  var isRecording = false

  var progressChannel: Channel?
  var progressTimer: Timer?
  var pendingThumbnail = false

  public override func load(webview: WKWebView) {
    self.webView = webview
    loadCamera()
  }

  private func loadCamera() {
    cameraView = CameraView(
      frame: CGRect(
        x: 0, y: 0, width: UIScreen.main.bounds.width, height: UIScreen.main.bounds.height))
    cameraView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
  }

  private func discoverCaptureDevices() -> [AVCaptureDevice] {
    let discoverySession = AVCaptureDevice.DiscoverySession(
      deviceTypes: [.builtInWideAngleCamera],
      mediaType: .video,
      position: .unspecified
    )
    return discoverySession.devices
  }

  private func setupSession(direction: String, windowed: Bool) throws {
    if windowed {
      webView.superview?.insertSubview(cameraView, belowSubview: webView)
    } else {
      webView.superview?.insertSubview(cameraView, aboveSubview: webView)
    }

    for device in discoverCaptureDevices() {
      if device.position == .back { backCamera = device }
      else if device.position == .front { frontCamera = device }
    }

    var selected = direction
    if selected == "back" && backCamera == nil { selected = "front" }
    if selected == "front" && frontCamera == nil { selected = "back" }

    guard let cameraDevice = selected == "front" ? frontCamera : backCamera else {
      throw CaptureError.cameraUnavailable
    }
    currentCamera = cameraDevice

    let session = AVCaptureSession()
    session.beginConfiguration()

    let cameraInput = try AVCaptureDeviceInput(device: cameraDevice)
    guard session.canAddInput(cameraInput) else {
      throw CaptureError.couldNotConfigureInput
    }
    session.addInput(cameraInput)

    let photoOutput = AVCapturePhotoOutput()
    if session.canAddOutput(photoOutput) {
      session.addOutput(photoOutput)
      self.photoOutput = photoOutput
    }

    let movieOutput = AVCaptureMovieFileOutput()
    if session.canAddOutput(movieOutput) {
      session.addOutput(movieOutput)
      self.movieOutput = movieOutput
    }

    // Microphone input (video recording with audio).
    if let mic = AVCaptureDevice.default(for: .audio) {
      if let micInput = try? AVCaptureDeviceInput(device: mic), session.canAddInput(micInput) {
        session.addInput(micInput)
      }
    }

    session.commitConfiguration()

    let previewLayer = AVCaptureVideoPreviewLayer(session: session)
    cameraView.addPreviewLayer(previewLayer)
    self.captureVideoPreviewLayer = previewLayer
    self.captureSession = session

    self.windowed = windowed
    if windowed {
      self.previousBackgroundColor = self.webView.backgroundColor
      self.webView.isOpaque = false
      self.webView.backgroundColor = UIColor.clear
      self.webView.scrollView.backgroundColor = UIColor.clear
    }
  }

  private func dismantleCamera() {
    if let session = captureSession {
      session.stopRunning()
      cameraView.removePreviewLayer()
      cameraView.removeFromSuperview()
      captureVideoPreviewLayer = nil
      photoOutput = nil
      movieOutput = nil
      captureSession = nil
      frontCamera = nil
      backCamera = nil
      currentCamera = nil
    }
  }

  private func destroy() {
    dismantleCamera()
    stopProgressTimer()
    progressChannel = nil
    photoInvoke = nil
    stopRecordingInvoke = nil
    isRecording = false
    if windowed {
      let backgroundColor = previousBackgroundColor ?? UIColor.white
      webView.isOpaque = true
      webView.backgroundColor = backgroundColor
      webView.scrollView.backgroundColor = backgroundColor
    }
  }

  private func getPermissionState(_ mediaType: AVMediaType) -> String {
    switch AVCaptureDevice.authorizationStatus(for: mediaType) {
    case .authorized: return "granted"
    case .denied: return "denied"
    default: return "prompt"
    }
  }

  // MARK: Preview

  @objc private func startPreview(_ invoke: Invoke) throws {
    let args = try invoke.parseArgs(StartPreviewOptions.self)

    let availableDevices = discoverCaptureDevices()
    if availableDevices.isEmpty {
      invoke.reject("No camera available on this device (e.g., iOS Simulator)")
      return
    }

    if getPermissionState(.video) != "granted" {
      invoke.reject("Camera permission denied or not yet requested")
      return
    }

    DispatchQueue.main.async { [self] in
      self.loadCamera()
      self.dismantleCamera()
      do {
        try self.setupSession(direction: args.camera ?? "back", windowed: args.windowed ?? false)
        self.captureSession?.startRunning()
        invoke.resolve()
      } catch {
        invoke.reject("Failed to start camera preview: \(error)")
      }
    }
  }

  @objc private func stopPreview(_ invoke: Invoke) {
    DispatchQueue.main.async { [self] in
      self.destroy()
      invoke.resolve()
    }
  }

  // MARK: Photo capture

  @objc private func capture(_ invoke: Invoke) throws {
    let args = try invoke.parseArgs(CaptureOptions.self)

    guard let photoOutput = self.photoOutput else {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }

    let settings = AVCapturePhotoSettings()
    if let flash = args.flash {
      switch flash {
      case "on": settings.flashMode = .on
      case "auto": settings.flashMode = .auto
      default: settings.flashMode = .off
      }
    } else {
      settings.flashMode = self.flashMode
    }

    self.photoInvoke = invoke
    photoOutput.capturePhoto(with: settings, delegate: self)
  }

  public func photoOutput(
    _ output: AVCapturePhotoOutput,
    didFinishProcessingPhoto photo: AVCapturePhoto,
    error: Error?
  ) {
    defer { self.photoInvoke = nil }
    guard let invoke = self.photoInvoke else { return }

    if let error = error {
      invoke.reject("Failed to capture photo: \(error.localizedDescription)")
      return
    }

    guard let data = photo.fileDataRepresentation() else {
      invoke.reject("Failed to read captured photo data")
      return
    }

    let fileURL = FileManager.default.temporaryDirectory
      .appendingPathComponent("tauri-camera-\(Int(Date().timeIntervalSince1970 * 1000)).jpg")

    do {
      try data.write(to: fileURL)
      let (width, height) = imageDimensions(data)
      var result: [String: Any] = [
        "path": fileURL.path,
        "width": width,
        "height": height,
        "orientation": orientationFromPhoto(photo),
        "format": "jpeg"
      ]
      if let thumbnail = makeThumbnail(data) {
        result["thumbnail"] = thumbnail
      }
      invoke.resolve(result)
    } catch {
      invoke.reject("Failed to write photo to file: \(error.localizedDescription)")
    }
  }

  // MARK: Controls

  @objc private func flipCamera(_ invoke: Invoke) {
    guard let session = captureSession else {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }

    let newDirection: String = (currentCamera?.position == .back) ? "front" : "back"

    DispatchQueue.main.async { [self] in
      self.dismantleCamera()
      do {
        try self.setupSession(direction: newDirection, windowed: self.windowed)
        self.captureSession?.startRunning()
        invoke.resolve()
      } catch {
        invoke.reject("Failed to flip camera: \(error)")
      }
    }
  }

  @objc private func setFlash(_ invoke: Invoke) throws {
    let args = try invoke.parseArgs(FlashOptions.self)
    switch args.mode {
    case "on": flashMode = .on
    case "auto": flashMode = .auto
    default: flashMode = .off
    }
    invoke.resolve()
  }

  @objc private func setZoom(_ invoke: Invoke) throws {
    let args = try invoke.parseArgs(ZoomOptions.self)
    guard let device = currentCamera else {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }
    let factor = args.factor ?? 1.0
    do {
      try device.lockForConfiguration()
      device.videoZoomFactor = CGFloat(min(max(factor, 1.0), device.activeFormat.videoMaxZoomFactor))
      device.unlockForConfiguration()
      invoke.resolve()
    } catch {
      invoke.reject("Failed to set zoom: \(error.localizedDescription)")
    }
  }

  @objc private func getZoom(_ invoke: Invoke) {
    guard let device = currentCamera else {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }
    invoke.resolve(Double(device.videoZoomFactor))
  }

  // MARK: Introspection

  /// All physical lenses exposed by the plugin (wide, ultra-wide, telephoto, front).
  func availableLenses() -> [AVCaptureDevice] {
    let deviceTypes: [AVCaptureDevice.DeviceType] = [
      .builtInWideAngleCamera,
      .builtInUltraWideCamera,
      .builtInTelephotoCamera,
      .builtInTrueDepthCamera,
    ]
    let discoverySession = AVCaptureDevice.DiscoverySession(
      deviceTypes: deviceTypes,
      mediaType: .video,
      position: .unspecified
    )
    return discoverySession.devices
  }

  private func defaultBackDevice() -> AVCaptureDevice? {
    let devices = availableLenses()
    return devices.first { $0.position == .back && $0.deviceType == .builtInWideAngleCamera }
      ?? devices.first { $0.position == .back }
  }

  @objc private func getAvailableCameras(_ invoke: Invoke) {
    let cameras: [CameraInfoPayload] = availableLenses().map { device in
      CameraInfoPayload(
        id: device.uniqueID,
        lens: lensName(for: device.deviceType),
        label: device.localizedName,
        facing: facingName(for: device.position)
      )
    }
    invoke.resolve(cameras)
  }

  @objc private func getCapabilities(_ invoke: Invoke) throws {
    let args = try invoke.parseArgs(GetCapabilitiesOptions.self)

    let device: AVCaptureDevice?
    if let cameraId = args.cameraId {
      device = AVCaptureDevice(uniqueID: cameraId)
    } else if let current = currentCamera {
      device = current
    } else {
      device = defaultBackDevice()
    }
    guard let camera = device else {
      invoke.reject("No camera available for the requested id")
      return
    }

    let modes = focusModeNames(from: camera.focusModes)
    var result: [String: Any] = [
      "cameraId": camera.uniqueID,
      "hasFlash": camera.hasFlash,
      "hasTorch": camera.hasTorch,
      "supportsFocus": !modes.isEmpty,
      "supportsPause": false,
      "photoFormats": ["jpeg", "heic"],
      "zoomRange": [
        "min": Double(camera.minAvailableVideoZoomFactor),
        "max": Double(camera.activeFormat.videoMaxZoomFactor),
      ],
    ]
    if !modes.isEmpty {
      result["focusModes"] = modes
    }
    invoke.resolve(result)
  }

  // MARK: Video recording

  private func startProgressTimer() {
    stopProgressTimer()
    DispatchQueue.main.async { [weak self] in
      guard let self = self, self.progressChannel != nil else { return }
      self.progressTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) {
        [weak self] _ in
        self?.emitRecordingProgress()
      }
    }
  }

  private func stopProgressTimer() {
    progressTimer?.invalidate()
    progressTimer = nil
  }

  private func emitRecordingProgress() {
    guard let channel = progressChannel, let output = movieOutput else { return }
    let seconds = output.recordedDuration.seconds
    guard seconds.isFinite, seconds >= 0 else { return }
    let payload = makeRecordingProgress(duration: seconds, bytesRecorded: output.recordedFileSize)
    try? channel.send(payload)
  }

  @objc private func startRecording(_ invoke: Invoke) {
    let args = try? invoke.parseArgs(StartRecordingOptions.self)
    guard let movieOutput = self.movieOutput, !isRecording else {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }

    if getPermissionState(.audio) != "granted" {
      invoke.reject("Microphone permission denied or not yet requested")
      return
    }

    let fileURL = FileManager.default.temporaryDirectory
      .appendingPathComponent("tauri-camera-\(Int(Date().timeIntervalSince1970 * 1000)).mp4")

    progressChannel = args?.onProgress
    self.isRecording = true
    movieOutput.startRecording(to: fileURL, recordingDelegate: self)
    if progressChannel != nil {
      startProgressTimer()
    }
    invoke.resolve()
  }

  @objc private func stopRecording(_ invoke: Invoke) {
    let args = try? invoke.parseArgs(StopRecordingOptions.self)
    guard let movieOutput = self.movieOutput, isRecording else {
      invoke.reject("Recording is not active. Call startRecording() first.")
      return
    }
    self.pendingThumbnail = args?.thumbnail ?? false
    self.stopRecordingInvoke = invoke
    movieOutput.stopRecording()
  }

  private func makeVideoThumbnail(_ url: URL) -> String? {
    let asset = AVURLAsset(url: url)
    let generator = AVAssetImageGenerator(asset: asset)
    generator.appliesPreferredTrackTransform = true
    generator.maximumSize = CGSize(width: 256, height: 256)
    let time = CMTime(seconds: 0.1, preferredTimescale: 600)
    do {
      let cgImage = try generator.copyCGImage(at: time, actualTime: nil)
      return UIImage(cgImage: cgImage).jpegData(compressionQuality: 0.8)?.base64EncodedString()
    } catch {
      return nil
    }
  }

  public func fileOutput(
    _ output: AVCaptureFileOutput,
    didFinishRecordingTo outputFileURL: URL,
    from connections: [AVCaptureConnection],
    error: Error?
  ) {
    defer {
      self.isRecording = false
      self.stopRecordingInvoke = nil
      self.stopProgressTimer()
      self.progressChannel = nil
      self.pendingThumbnail = false
    }
    guard let invoke = self.stopRecordingInvoke else { return }

    if let error = error {
      invoke.reject("Video recording failed: \(error.localizedDescription)")
      return
    }

    let asset = AVURLAsset(url: outputFileURL)
    let duration = asset.duration.seconds

    var result: JsonObject = [
      "path": outputFileURL.path,
      "duration": duration
    ]
    if self.pendingThumbnail, let thumbnail = makeVideoThumbnail(outputFileURL) {
      result["thumbnail"] = thumbnail
    }

    invoke.resolve(result)
  }

  // MARK: Gallery

  @objc private func saveToGallery(_ invoke: Invoke) throws {
    let args = try invoke.parseArgs(SaveToGalleryOptions.self)
    guard let path = args.path, !path.isEmpty else {
      invoke.reject("A file path is required")
      return
    }

    let fileURL = URL(fileURLWithPath: path)
    guard FileManager.default.fileExists(atPath: path) else {
      invoke.reject("File does not exist: \(path)")
      return
    }

    let isVideo = mediaIsVideo(path: path, override: args.type)

    let save: () -> Void = {
      PHPhotoLibrary.shared().performChanges {
        if isVideo {
          PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: fileURL)
        } else {
          PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: fileURL)
        }
      } completionHandler: { success, error in
        if success {
          invoke.resolve()
        } else {
          invoke.reject(error?.localizedDescription ?? "Failed to save image to gallery")
        }
      }
    }

    if #available(iOS 14, *) {
      PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
        if status == .authorized || status == .limited {
          save()
        } else {
          invoke.reject("Photo library permission denied")
        }
      }
    } else {
      PHPhotoLibrary.requestAuthorization { status in
        if status == .authorized {
          save()
        } else {
          invoke.reject("Photo library permission denied")
        }
      }
    }
  }

  // MARK: Permissions

  @objc override func checkPermissions(_ invoke: Invoke) {
    invoke.resolve([
      "camera": getPermissionState(.video),
      "microphone": getPermissionState(.audio)
    ])
  }

  @objc override func requestPermissions(_ invoke: Invoke) {
    let videoState = getPermissionState(.video)
    let audioState = getPermissionState(.audio)

    if videoState == "prompt" {
      AVCaptureDevice.requestAccess(for: .video) { videoGranted in
        let camera = videoGranted ? "granted" : "denied"
        if audioState == "prompt" {
          AVCaptureDevice.requestAccess(for: .audio) { audioGranted in
            invoke.resolve(["camera": camera, "microphone": audioGranted ? "granted" : "denied"])
          }
        } else {
          invoke.resolve(["camera": camera, "microphone": audioState])
        }
      }
    } else if audioState == "prompt" {
      AVCaptureDevice.requestAccess(for: .audio) { audioGranted in
        invoke.resolve(["camera": videoState, "microphone": audioGranted ? "granted" : "denied"])
      }
    } else {
      invoke.resolve(["camera": videoState, "microphone": audioState])
    }
  }

  @objc func openAppSettings(_ invoke: Invoke) {
    guard let settingsUrl = URL(string: UIApplication.openSettingsURLString) else {
      return
    }
    DispatchQueue.main.async {
      if UIApplication.shared.canOpenURL(settingsUrl) {
        UIApplication.shared.open(settingsUrl) { _ in invoke.resolve() }
      }
    }
  }
}

// MARK: Helpers

private func imageDimensions(_ data: Data) -> (Int, Int) {
  guard let source = CGImageSourceCreateWithData(data as CFData, nil),
    let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
  else {
    return (0, 0)
  }
  let width = props[kCGImagePropertyPixelWidth] as? Int ?? 0
  let height = props[kCGImagePropertyPixelHeight] as? Int ?? 0
  return (width, height)
}

private func orientationFromPhoto(_ photo: AVCapturePhoto) -> Int {
  if let orientation = photo.metadata[kCGImagePropertyOrientation as String] as? Int {
    return orientation
  }
  return 1
}

private func makeThumbnail(_ data: Data, maxDim: CGFloat = 256.0) -> String? {
  guard let image = UIImage(data: data) else { return nil }
  let scale = min(maxDim / image.size.width, maxDim / image.size.height, 1.0)
  let newSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
  UIGraphicsBeginImageContext(newSize)
  image.draw(in: CGRect(origin: .zero, size: newSize))
  let resized = UIGraphicsGetImageFromCurrentImageContext()
  UIGraphicsEndImageContext()
  return resized?.jpegData(compressionQuality: 0.8)?.base64EncodedString()
}

@_cdecl("init_plugin_camera")
func initPlugin() -> Plugin {
  return CameraPlugin()
}
