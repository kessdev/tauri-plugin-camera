import XCTest
@testable import tauri_plugin_camera

final class CameraPluginTests: XCTestCase {

  func testDecodesStartPreviewOptions() throws {
    let data = Data(#"{"camera":"front","windowed":true}"#.utf8)
    let options = try JSONDecoder().decode(StartPreviewOptions.self, from: data)
    XCTAssertEqual(options.camera, "front")
    XCTAssertEqual(options.windowed, true)
  }

  func testDecodesCaptureOptions() throws {
    let data = Data(#"{"flash":"auto"}"#.utf8)
    let options = try JSONDecoder().decode(CaptureOptions.self, from: data)
    XCTAssertEqual(options.flash, "auto")
  }

  func testDecodesZoomOptions() throws {
    let data = Data(#"{"factor":2.5}"#.utf8)
    let options = try JSONDecoder().decode(ZoomOptions.self, from: data)
    XCTAssertEqual(options.factor, 2.5)
  }

  func testDecodesSaveToGalleryOptions() throws {
    let data = Data(#"{"path":"/tmp/photo.jpg"}"#.utf8)
    let options = try JSONDecoder().decode(SaveToGalleryOptions.self, from: data)
    XCTAssertEqual(options.path, "/tmp/photo.jpg")
  }

  func testStartRecordingOptionsDecodesWithoutChannel() throws {
    let data = Data("{}".utf8)
    let options = try JSONDecoder().decode(StartRecordingOptions.self, from: data)
    XCTAssertNil(options.onProgress)
  }

  func testRecordingProgressOmitsUnknownSize() {
    let progress = makeRecordingProgress(duration: 1.5, bytesRecorded: 0)
    XCTAssertEqual(progress.duration, 1.5)
    XCTAssertNil(progress.bytesRecorded)
    XCTAssertNil(progress.bitrate)
  }

  func testRecordingProgressIncludesSizeAndBitrate() {
    let progress = makeRecordingProgress(duration: 2.0, bytesRecorded: 1000)
    XCTAssertEqual(progress.bytesRecorded, 1000)
    XCTAssertEqual(progress.bitrate ?? 0, 4000, accuracy: 1e-9)
  }

  func testRecordingProgressEncodesCamelCaseAndOmitsNil() throws {
    let progress = makeRecordingProgress(duration: 1.0, bytesRecorded: 0)
    let data = try JSONEncoder().encode(progress)
    let json = String(decoding: data, as: UTF8.self)
    XCTAssertEqual(json, #"{"duration":1}"#)
  }

  func testMediaIsVideoInfersFromExtensionAndOverride() {
    XCTAssertTrue(mediaIsVideo(path: "/tmp/a.mp4", override: nil))
    XCTAssertTrue(mediaIsVideo(path: "/tmp/a.MOV", override: nil))
    XCTAssertTrue(mediaIsVideo(path: "/tmp/a.webm", override: nil))
    XCTAssertFalse(mediaIsVideo(path: "/tmp/a.jpg", override: nil))
    XCTAssertTrue(mediaIsVideo(path: "/tmp/a.jpg", override: "video"))
    XCTAssertFalse(mediaIsVideo(path: "/tmp/a.mp4", override: "image"))
  }

  func testDecodesStopRecordingOptions() throws {
    let data = Data(#"{"thumbnail":true}"#.utf8)
    let options = try JSONDecoder().decode(StopRecordingOptions.self, from: data)
    XCTAssertEqual(options.thumbnail, true)
  }

  func testDecodesSaveToGalleryTypeOverride() throws {
    let data = Data(#"{"path":"/tmp/a.mp4","type":"video"}"#.utf8)
    let options = try JSONDecoder().decode(SaveToGalleryOptions.self, from: data)
    XCTAssertEqual(options.type, "video")
  }

  func testDecodesGetCapabilitiesOptions() throws {
    let withId = try JSONDecoder().decode(
      GetCapabilitiesOptions.self, from: Data(#"{"cameraId":"abc"}"#.utf8))
    XCTAssertEqual(withId.cameraId, "abc")

    let withoutId = try JSONDecoder().decode(GetCapabilitiesOptions.self, from: Data("{}".utf8))
    XCTAssertNil(withoutId.cameraId)
  }

  func testFacingNameMapsPositions() {
    XCTAssertEqual(facingName(for: .front), "front")
    XCTAssertEqual(facingName(for: .back), "back")
    XCTAssertNil(facingName(for: .unspecified))
  }

  func testLensNameMapsDeviceTypes() {
    XCTAssertEqual(lensName(for: .builtInUltraWideCamera), "ultraWide")
    XCTAssertEqual(lensName(for: .builtInTelephotoCamera), "telephoto")
    XCTAssertEqual(lensName(for: .builtInWideAngleCamera), "wide")
    XCTAssertEqual(lensName(for: .builtInTrueDepthCamera), "wide")
  }

  func testFocusModeNamesMapsAndDeduplicates() {
    XCTAssertEqual(focusModeNames(from: []), [])
    XCTAssertEqual(focusModeNames(from: [.autoFocus]), ["auto"])
    XCTAssertEqual(focusModeNames(from: [.locked, .continuousAutoFocus]), ["locked", "continuous"])
    XCTAssertEqual(
      focusModeNames(from: [.autoFocus, .autoFocus, .continuousAutoFocus]),
      ["auto", "continuous"]
    )
  }

  func testResolveCameraId() throws {
    XCTAssertNil(try resolveCameraId(availableIds: ["a"], requested: nil))
    XCTAssertEqual(try resolveCameraId(availableIds: ["a", "b"], requested: "b"), "b")
    XCTAssertThrowsError(try resolveCameraId(availableIds: ["a"], requested: "z"))
  }

  func testClampExposureBias() {
    XCTAssertEqual(clampExposureBias(5, minBias: -2, maxBias: 2), 2)
    XCTAssertEqual(clampExposureBias(-5, minBias: -2, maxBias: 2), -2)
    XCTAssertEqual(clampExposureBias(1, minBias: -2, maxBias: 2), 1)
  }

  func testClampWhiteBalanceGain() {
    XCTAssertEqual(clampWhiteBalanceGain(0.5, maxGain: 4), 1)
    XCTAssertEqual(clampWhiteBalanceGain(9, maxGain: 4), 4)
    XCTAssertEqual(clampWhiteBalanceGain(2, maxGain: 4), 2)
  }

  func testFocusModeFromName() {
    XCTAssertEqual(focusMode(fromName: "auto"), .autoFocus)
    XCTAssertEqual(focusMode(fromName: "continuous"), .continuousAutoFocus)
    XCTAssertEqual(focusMode(fromName: "locked"), .locked)
    XCTAssertEqual(focusMode(fromName: nil), .locked)
  }
}
