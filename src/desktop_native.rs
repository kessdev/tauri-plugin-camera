//! Native desktop camera backend (Linux / GStreamer).
//!
//! Owns the camera and runs everything on GStreamer's own threads so the UI is
//! never blocked: a live preview pipeline fanning out JPEG frames to the
//! frontend, and a recording pipeline that encodes to an MP4 file.
#![cfg(all(target_os = "linux", feature = "native-desktop"))]

use std::sync::Mutex;

use base64::Engine as _;
use gstreamer as gst;
use gstreamer::prelude::*;
use gstreamer_app::AppSink;
use tauri::{ipc::Channel, AppHandle, Manager, Runtime};

use crate::desktop::Camera;
use crate::models::PreviewFrame;

/// Holds the running GStreamer pipelines. Each is `None` when not active.
#[derive(Default)]
pub struct NativeCamera {
  preview: Mutex<Option<gst::Pipeline>>,
  recording: Mutex<Option<gst::Pipeline>>,
}

impl NativeCamera {
  pub fn new() -> Self {
    Self::default()
  }
}

fn video_source() -> Result<gst::Element, String> {
  let monitor = gst::DeviceMonitor::new();
  monitor.add_filter(Some("Video/Source"), None);
  monitor.start().map_err(|e| e.to_string())?;
  let device = monitor
    .devices()
    .into_iter()
    .next()
    .ok_or_else(|| "No camera available".to_string())?;
  device.create_element(None).map_err(|e| e.to_string())
}

fn element(factory: &str) -> Result<gst::Element, String> {
  gst::ElementFactory::make(factory)
    .build()
    .map_err(|e| format!("Failed to create GStreamer element '{factory}': {e}"))
}

impl NativeCamera {
  fn start_preview(&self, on_frame: Channel<PreviewFrame>) -> Result<(), String> {
    let mut guard = self.preview.lock().unwrap();
    if guard.is_some() {
      return Ok(());
    }
    gst::init().map_err(|e| e.to_string())?;

    let source = video_source()?;
    let convert = element("videoconvert")?;
    let scale = element("videoscale")?;
    let filter = element("capsfilter")?;
    filter.set_property(
      "caps",
      gst::Caps::builder("video/x-raw")
        .field("width", 640i32)
        .build(),
    );
    let jpeg = element("jpegenc")?;
    let sink = element("appsink")?;

    let pipeline = gst::Pipeline::new();
    pipeline
      .add_many([&source, &convert, &scale, &filter, &jpeg, &sink])
      .map_err(|e| e.to_string())?;
    gst::Element::link_many([&source, &convert, &scale, &filter, &jpeg, &sink])
      .map_err(|e| e.to_string())?;

    let appsink = sink
      .downcast::<AppSink>()
      .map_err(|_| "appsink".to_string())?;
    appsink.set_callbacks(
      gstreamer_app::AppSinkCallbacks::builder()
        .new_sample(move |sink| {
          let sample = sink.pull_sample().map_err(|_| gst::FlowError::Eos)?;
          let buffer = sample.buffer().ok_or(gst::FlowError::Error)?;
          let map = buffer.map_readable().map_err(|_| gst::FlowError::Error)?;
          let data = base64::engine::general_purpose::STANDARD.encode(map.as_slice());
          let (width, height) = sample
            .caps()
            .and_then(|caps| caps.structure(0))
            .map(|s| {
              (
                s.get::<i32>("width").unwrap_or(0).max(0) as u32,
                s.get::<i32>("height").unwrap_or(0).max(0) as u32,
              )
            })
            .unwrap_or((0, 0));
          let _ = on_frame.send(PreviewFrame {
            data,
            width,
            height,
          });
          Ok(gst::FlowSuccess::Ok)
        })
        .build(),
    );

    pipeline
      .set_state(gst::State::Playing)
      .map_err(|e| e.to_string())?;
    *guard = Some(pipeline);
    Ok(())
  }

  fn stop_preview(&self) -> Result<(), String> {
    let mut guard = self.preview.lock().unwrap();
    if let Some(pipeline) = guard.take() {
      let _ = pipeline.set_state(gst::State::Null);
    }
    Ok(())
  }

  fn start_recording(&self, path: &str) -> Result<(), String> {
    let mut guard = self.recording.lock().unwrap();
    if guard.is_some() {
      return Ok(());
    }
    gst::init().map_err(|e| e.to_string())?;

    let vsource = video_source()?;
    let vconvert = element("videoconvert")?;
    let vqueue = element("queue")?;
    let venc = element("x264enc")?;
    let vparse = element("h264parse")?;

    let asource = element("autoaudiosrc")?;
    let aconvert = element("audioconvert")?;
    let aqueue = element("queue")?;
    let aenc = element("aacenc")?;
    let aparse = element("aacparse")?;

    let mux = element("mp4mux")?;
    let sink = element("filesink")?;
    sink.set_property("location", path);

    let pipeline = gst::Pipeline::new();
    pipeline
      .add_many([
        &vsource, &vconvert, &vqueue, &venc, &vparse, &asource, &aconvert, &aqueue, &aenc, &aparse,
        &mux, &sink,
      ])
      .map_err(|e| e.to_string())?;
    gst::Element::link_many([&vsource, &vconvert, &vqueue, &venc, &vparse])
      .map_err(|e| e.to_string())?;
    gst::Element::link_many([&asource, &aconvert, &aqueue, &aenc, &aparse])
      .map_err(|e| e.to_string())?;
    vparse.link(&mux).map_err(|e| e.to_string())?;
    aparse.link(&mux).map_err(|e| e.to_string())?;
    mux.link(&sink).map_err(|e| e.to_string())?;

    pipeline
      .set_state(gst::State::Playing)
      .map_err(|e| e.to_string())?;
    *guard = Some(pipeline);
    Ok(())
  }

  fn stop_recording(&self) -> Result<(), String> {
    let pipeline = {
      let mut guard = self.recording.lock().unwrap();
      guard.take()
    };
    if let Some(pipeline) = pipeline {
      // EOS finalizes the MP4 container; wait for it on GStreamer's own threads.
      let _ = pipeline.send_event(gst::event::Eos::new());
      if let Some(bus) = pipeline.bus() {
        let _ = bus.timed_pop_filtered(
          gst::ClockTime::from_seconds(10),
          &[gst::MessageType::Eos, gst::MessageType::Error],
        );
      }
      let _ = pipeline.set_state(gst::State::Null);
    }
    Ok(())
  }
}

fn camera<R: Runtime>(app: &AppHandle<R>) -> tauri::State<'_, Camera<R>> {
  app.state::<Camera<R>>()
}

/// Starts the native preview and streams JPEG frames to the frontend.
#[tauri::command]
pub async fn native_start_preview<R: Runtime>(
  app: AppHandle<R>,
  on_frame: Channel<PreviewFrame>,
) -> Result<(), String> {
  camera(&app).native.start_preview(on_frame)
}

/// Stops the native preview.
#[tauri::command]
pub async fn native_stop_preview<R: Runtime>(app: AppHandle<R>) -> Result<(), String> {
  camera(&app).native.stop_preview()
}

/// Starts native recording to `path` (or a temporary file when omitted).
#[tauri::command]
pub async fn native_start_recording<R: Runtime>(
  app: AppHandle<R>,
  path: Option<String>,
) -> Result<String, String> {
  let target = path.unwrap_or_else(|| {
    let dir = std::env::temp_dir();
    dir
      .join(format!("tauri-camera-{}.mp4", timestamp()))
      .to_string_lossy()
      .into_owned()
  });
  camera(&app).native.start_recording(&target)?;
  Ok(target)
}

/// Stops native recording and finalizes the file.
#[tauri::command]
pub async fn native_stop_recording<R: Runtime>(app: AppHandle<R>) -> Result<(), String> {
  camera(&app).native.stop_recording()
}

fn timestamp() -> u128 {
  std::time::SystemTime::now()
    .duration_since(std::time::UNIX_EPOCH)
    .map(|d| d.as_millis())
    .unwrap_or(0)
}
