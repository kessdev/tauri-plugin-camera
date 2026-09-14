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
}
