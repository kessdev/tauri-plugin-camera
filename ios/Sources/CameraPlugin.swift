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

  // MARK: Video recording

  @objc private func startRecording(_ invoke: Invoke) {
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

    self.isRecording = true
    movieOutput.startRecording(to: fileURL, recordingDelegate: self)
    invoke.resolve()
  }

  @objc private func stopRecording(_ invoke: Invoke) {
    guard let movieOutput = self.movieOutput, isRecording else {
      invoke.reject("Recording is not active. Call startRecording() first.")
      return
    }
    self.stopRecordingInvoke = invoke
    movieOutput.stopRecording()
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
    }
    guard let invoke = self.stopRecordingInvoke else { return }

    if let error = error {
      invoke.reject("Video recording failed: \(error.localizedDescription)")
      return
    }

    let asset = AVURLAsset(url: outputFileURL)
    let duration = asset.duration.seconds

    invoke.resolve([
      "path": outputFileURL.path,
      "duration": duration
    ])
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

    PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
      if status != .authorized && status != .limited {
        invoke.reject("Photo library permission denied")
        return
      }
      PHPhotoLibrary.shared().performChanges {
        PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: fileURL)
      } completionHandler: { success, error in
        if success {
          invoke.resolve()
        } else {
          invoke.reject(error?.localizedDescription ?? "Failed to save image to gallery")
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
