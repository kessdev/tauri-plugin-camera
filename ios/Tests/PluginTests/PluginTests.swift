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
}
