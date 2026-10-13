import {
  invoke,
  convertFileSrc,
  requestPermissions as requestPermissions_,
  checkPermissions as checkPermissions_
} from '@tauri-apps/api/core'
import type { Channel, PermissionState } from '@tauri-apps/api/core'

export type { PermissionState } from '@tauri-apps/api/core'
export { convertFileSrc }

export type CameraDirection = 'front' | 'back'
export type FlashMode = 'on' | 'off' | 'auto'

export interface StartPreviewOptions {
  camera?: CameraDirection
  /** Specific camera/lens id from `getAvailableCameras()`. Takes precedence over `camera`. */
  cameraId?: string
  windowed?: boolean
}

export type PhotoFormat = 'jpeg' | 'png' | 'heif'
export type VideoQuality = 'sd' | 'hd' | 'fhd' | 'uhd'

/** A width/height pair in pixels. */
export interface Resolution {
  width: number
  height: number
}

export interface CaptureOptions {
  flash?: FlashMode
  /** Target resolution (best-effort: the nearest supported is used). */
  resolution?: Resolution
  /** Target aspect ratio (e.g. '4:3', '16:9', '1:1'). */
  aspectRatio?: string
  /** Image format. Mobile supports `jpeg`; desktop `jpeg`/`png`. */
  format?: PhotoFormat
  /** Compression quality (0..1) for lossy formats. */
  quality?: number
}

export interface Photo {
  /** Absolute path to the captured image (mobile). */
  path?: string
  /** Base64 data URL of the captured image (desktop). */
  dataUrl?: string
  /** Raw image blob (desktop). */
  blob?: Blob
  width: number
  height: number
  /** EXIF orientation value. */
  orientation: number
  format: string
  /** Optional base64-encoded thumbnail (mobile). */
  thumbnail?: string
}

export interface Video {
  /** Absolute path to the recorded video (mobile). */
  path?: string
  duration: number
  /** Raw video blob (desktop). */
  blob?: Blob
  /** Object URL for the recorded video (desktop). */
  url?: string
  /** Optional base64-encoded thumbnail, when requested via `stopRecording`. */
  thumbnail?: string
}

/** Supported zoom range of a camera (declarative, without the live value). */
export interface ZoomRange {
  min: number
  max: number
}

/** Orientation of a camera. */
export type CameraFacing = 'front' | 'back' | 'external'

/**
 * Best-effort hint of which lens a camera maps to. Platforms that cannot
 * determine it (notably desktop) omit the field.
 */
export type CameraLens = 'wide' | 'ultraWide' | 'telephoto' | (string & {})

/** Camera focus mode. */
export type FocusMode = 'auto' | 'continuous' | 'locked'

/** Camera exposure mode. */
export type ExposureMode = 'auto' | 'locked'

/** White balance mode. */
export type WhiteBalanceMode = 'auto' | 'locked'

/** Supported exposure compensation range (EV). */
export interface ExposureRange {
  minEv: number
  maxEv: number
}

/** Options for `setWhiteBalance`. */
export interface WhiteBalanceOptions {
  mode: WhiteBalanceMode
  /** Color temperature in Kelvin; only meaningful when `mode` is `locked`. */
  temperatureK?: number
}

/** A camera (physical or logical lens) available on the device. */
export interface CameraInfo {
  /** Native, session-stable identifier (usable to reference this camera). */
  id: string
  /** Orientation, when the platform can determine it. */
  facing?: CameraFacing
  /** Best-effort lens hint, when the platform can determine it. */
  lens?: CameraLens
  /** Human-readable label, when available. */
  label?: string
}

/**
 * Declarative capabilities of a camera: what the hardware/platform *can* do.
 *
 * Optional fields are omitted when the platform cannot determine them. Live
 * values (e.g. the current zoom factor) are exposed by dedicated getters such
 * as {@link getZoom}, never here.
 */
export interface CameraCapabilities {
  /** Identifier of the camera these capabilities describe, when known. */
  cameraId?: string
  hasFlash: boolean
  hasTorch: boolean
  zoomRange?: ZoomRange
  supportsFocus: boolean
  focusModes?: string[]
  exposureRange?: ExposureRange
  exposureModes?: ExposureMode[]
  whiteBalanceModes?: WhiteBalanceMode[]
  /** Whether an arbitrary color temperature (Kelvin) can be applied. */
  supportsWhiteBalanceTemperature?: boolean
  photoResolutions?: Resolution[]
  photoAspects?: string[]
  videoQualities?: VideoQuality[]
  videoFps?: number[]
  photoFormats?: string[]
  supportsPause: boolean
}

/**
 * Live telemetry emitted while a video recording is in progress.
 *
 * Delivered through the optional `onProgress` channel passed to
 * `startRecording()`. The `bytesRecorded` and `bitrate` fields are omitted
 * when the platform cannot determine them at that moment.
 */
export interface RecordingProgress {
  /** Elapsed recording time in seconds. */
  duration: number
  /** Bytes written to the output so far. */
  bytesRecorded?: number
  /** Average bitrate in bits per second, derived from `bytesRecorded`. */
  bitrate?: number
}

export interface StartRecordingOptions {
  /** Receives live recording telemetry while the recording is active. */
  onProgress?: Channel<RecordingProgress>
  /** Video quality preset. */
  quality?: VideoQuality
  /** Target frame rate. */
  fps?: number
  /** Target bitrate in bits per second. */
  bitrate?: number
}

export interface StopRecordingOptions {
  /** When true, the result includes a base64-encoded `thumbnail`. */
  thumbnail?: boolean
}

export interface SaveToGalleryOptions {
  /** Force the media type instead of inferring it from the file. */
  type?: 'image' | 'video'
}

/**
 * Detects whether the current runtime is a Tauri mobile webview (Android/iOS).
 * Desktop runtimes fall through to the getUserMedia-based backend.
 */
function isMobile(): boolean {
  if (typeof navigator === 'undefined') return false
  const ua = navigator.userAgent
  return /android/i.test(ua) || /iphone|ipad|ipod/i.test(ua)
}

// ---------------------------------------------------------------------------
// Desktop backend state (getUserMedia + canvas + MediaRecorder)
// ---------------------------------------------------------------------------

let activeStream: MediaStream | null = null
let activeVideo: HTMLVideoElement | null = null
let mediaRecorder: MediaRecorder | null = null
let recordingAudioStream: MediaStream | null = null
let recordedChunks: Blob[] = []
let recordedBytes = 0
let recordingStartedAt = 0
let progressTimer: ReturnType<typeof setInterval> | null = null

/** Minimum interval between desktop progress messages (~4 Hz). */
const RECORDING_PROGRESS_INTERVAL_MS = 250
/** Ask MediaRecorder for data chunks at least this often so size is known live. */
const RECORDING_TIMESLICE_MS = 1000

/** Picks an explicit recording mime type supported by this browser, if any. */
function pickDesktopRecordingMimeType(): string | undefined {
  if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') {
    return undefined
  }
  const candidates = [
    'video/webm;codecs=vp9,opus',
    'video/webm;codecs=vp8,opus',
    'video/webm',
    'video/mp4'
  ]
  return candidates.find((type) => MediaRecorder.isTypeSupported(type))
}

function buildRecordingProgress(duration: number, bytes: number): RecordingProgress {
  // Omit the size-derived fields until a real chunk has arrived, matching the
  // mobile backends (do not report a misleading 0).
  const progress: RecordingProgress = { duration }
  if (bytes > 0) {
    progress.bytesRecorded = bytes
    if (duration > 0) {
      progress.bitrate = Math.round((bytes * 8) / duration)
    }
  }
  return progress
}

function progressesAreEqual(a: RecordingProgress | null, b: RecordingProgress): boolean {
  return (
    a != null &&
    a.duration === b.duration &&
    a.bytesRecorded === b.bytesRecorded &&
    a.bitrate === b.bitrate
  )
}

function stopProgressTimer(): void {
  if (progressTimer !== null) {
    clearInterval(progressTimer)
    progressTimer = null
  }
}

async function ensureDesktopStream(options?: StartPreviewOptions): Promise<MediaStream> {
  if (activeStream) return activeStream

  const videoConstraints: MediaTrackConstraints = {}
  if (options?.cameraId) {
    videoConstraints.deviceId = { exact: options.cameraId }
  } else {
    videoConstraints.facingMode = options?.camera === 'front' ? 'user' : 'environment'
  }

  const constraints: MediaStreamConstraints = { video: videoConstraints, audio: false }

  const stream = await navigator.mediaDevices.getUserMedia(constraints)
  activeStream = stream

  const video = document.createElement('video')
  video.muted = true
  video.playsInline = true
  video.srcObject = stream
  await video.play()
  activeVideo = video

  return stream
}

function teardownDesktopStream(): void {
  stopProgressTimer()
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    mediaRecorder.stop()
  }
  mediaRecorder = null
  recordingAudioStream?.getTracks().forEach((t) => t.stop())
  recordingAudioStream = null
  recordedChunks = []
  recordedBytes = 0
  if (activeStream) {
    activeStream.getTracks().forEach((t) => t.stop())
  }
  activeStream = null
  activeVideo = null
}

/** Parses an aspect ratio like '4:3' into a number (4/3), or null. */
function parseAspectRatio(value: string): number | null {
  const [w, h] = value.split(':').map((n) => Number(n))
  if (!w || !h) return null
  return w / h
}

/** Target capture height (px) for a video quality preset (desktop best-effort). */
function qualityHeight(quality: VideoQuality): number {
  switch (quality) {
    case 'sd':
      return 480
    case 'hd':
      return 720
    case 'fhd':
      return 1080
    case 'uhd':
      return 2160
  }
}

/** MIME type for a photo format on desktop, or null when unsupported. */
function desktopPhotoMime(format: PhotoFormat): string | null {
  switch (format) {
    case 'jpeg':
      return 'image/jpeg'
    case 'png':
      return 'image/png'
    default:
      return null
  }
}

function captureDesktopFrame(options?: CaptureOptions): Photo {
  if (!activeVideo) {
    throw new Error('Camera preview is not active. Call startPreview() first.')
  }
  const format = options?.format ?? 'jpeg'
  const mime = desktopPhotoMime(format)
  if (!mime) {
    throw new Error(`${format} is not supported on desktop.`)
  }
  const sourceWidth = activeVideo.videoWidth
  const sourceHeight = activeVideo.videoHeight
  let width = options?.resolution?.width ?? sourceWidth
  let height = options?.resolution?.height ?? sourceHeight
  if (!options?.resolution && options?.aspectRatio) {
    const ratio = parseAspectRatio(options.aspectRatio)
    if (ratio) height = Math.round(width / ratio)
  }
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    throw new Error('Failed to create canvas context for capture.')
  }
  ctx.drawImage(activeVideo, 0, 0, width, height)
  const dataUrl = canvas.toDataURL(mime, options?.quality)
  return {
    dataUrl,
    width,
    height,
    orientation: 1,
    format,
    thumbnail: dataUrl
  }
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

/**
 * Starts the camera preview.
 *
 * On mobile this starts a native preview over the webview; pass `windowed: true`
 * to keep the webview transparent so your UI renders on top of the camera.
 * On desktop this acquires the camera via `getUserMedia` and stores the stream
 * for `capture()` / `startRecording()`. Use `getPreviewStream()` to attach the
 * desktop stream to a `<video>` element.
 */
export async function startPreview(options?: StartPreviewOptions): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|start_preview', { ...options })
    return
  }
  await ensureDesktopStream(options)
}

/** Stops the camera preview and releases the camera. */
export async function stopPreview(): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|stop_preview')
    return
  }
  teardownDesktopStream()
}

/**
 * Returns the active desktop preview stream so the application can render it in
 * a `<video>` element. Returns `null` on mobile (the native preview is used).
 */
export async function getPreviewStream(): Promise<MediaStream | null> {
  if (isMobile()) return null
  return activeStream
}

// ---------------------------------------------------------------------------
// Photo capture
// ---------------------------------------------------------------------------

/**
 * Captures a photo.
 *
 * On mobile returns the temporary file path (plus an optional base64
 * thumbnail). Use `convertFileSrc(photo.path)` to display it. On desktop
 * returns a base64 data URL and a Blob.
 */
export async function capture(options?: CaptureOptions): Promise<Photo> {
  if (isMobile()) {
    return await invoke<Photo>('plugin:camera-kessdev|capture', { ...options })
  }
  return captureDesktopFrame(options)
}

// ---------------------------------------------------------------------------
// Camera controls
// ---------------------------------------------------------------------------

/** Switches between front and back camera (mobile). */
export async function flipCamera(): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|flip_camera')
    return
  }
  throw new Error('flipCamera is not supported on desktop. Restart the preview with a different camera direction.')
}

/** Sets the flash mode (mobile). */
export async function setFlash(mode: FlashMode): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|set_flash', { mode })
    return
  }
  throw new Error('setFlash is not supported on desktop.')
}

/** Sets the camera zoom factor (mobile). */
export async function setZoom(factor: number): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|set_zoom', { factor })
    return
  }
  throw new Error('setZoom is not supported on desktop.')
}

/** Gets the current camera zoom factor (mobile). */
export async function getZoom(): Promise<number> {
  if (isMobile()) {
    return await invoke<number>('plugin:camera-kessdev|get_zoom')
  }
  throw new Error('getZoom is not supported on desktop.')
}

// ---------------------------------------------------------------------------
// Introspection (available cameras + declarative capabilities)
// ---------------------------------------------------------------------------

/** Best-effort facing detection from a device label (desktop). */
function facingFromLabel(label: string): CameraFacing | undefined {
  const value = label.toLowerCase()
  if (value.includes('front') || value.includes('user')) return 'front'
  if (value.includes('back') || value.includes('rear') || value.includes('environment')) return 'back'
  return undefined
}

/** Enumerates video input devices on desktop via `enumerateDevices()`. */
async function enumerateDesktopCameras(): Promise<CameraInfo[]> {
  const devices = await navigator.mediaDevices.enumerateDevices()
  return devices
    .filter((device) => device.kind === 'videoinput')
    .map((device) => {
      const info: CameraInfo = { id: device.deviceId }
      const facing = facingFromLabel(device.label)
      if (facing) info.facing = facing
      if (device.label) info.label = device.label
      return info
    })
}

/** Subset of the non-standard `MediaTrackCapabilities` (image capture) fields. */
interface TrackImageCapabilities {
  zoom?: { min: number; max: number }
  torch?: boolean
  focusMode?: string[]
}

/**
 * Declarative capabilities on desktop. Hardware fields (`zoomRange`, `hasTorch`,
 * `focusModes`) are only known while a preview/recording stream is active; they
 * are omitted otherwise.
 */
function desktopCapabilities(cameraId?: string): CameraCapabilities {
  const caps: CameraCapabilities = {
    hasFlash: false,
    hasTorch: false,
    supportsFocus: false,
    supportsPause: true,
    photoFormats: ['jpeg', 'png', 'webp']
  }
  if (cameraId) caps.cameraId = cameraId

  const track = activeStream?.getVideoTracks?.()[0]
  const trackCaps = track?.getCapabilities?.() as unknown as TrackImageCapabilities | undefined
  if (trackCaps) {
    if (trackCaps.zoom) {
      caps.zoomRange = { min: trackCaps.zoom.min, max: trackCaps.zoom.max }
    }
    if (typeof trackCaps.torch === 'boolean') {
      caps.hasTorch = trackCaps.torch
    }
    if (trackCaps.focusMode && trackCaps.focusMode.length > 0) {
      caps.supportsFocus = true
      caps.focusModes = trackCaps.focusMode
    }
  }
  return caps
}

/**
 * Lists the cameras (physical or logical lenses) available on the device.
 *
 * Works without an active preview on every platform. On desktop the labels may
 * be empty until camera permission has been granted at least once.
 */
export async function getAvailableCameras(): Promise<CameraInfo[]> {
  if (isMobile()) {
    return await invoke<CameraInfo[]>('plugin:camera-kessdev|get_available_cameras')
  }
  return enumerateDesktopCameras()
}

/**
 * Returns the declarative capabilities of a camera.
 *
 * Without `cameraId` it describes the active camera, or the default (back)
 * camera when no preview is active. On mobile it works without an active
 * preview; on desktop hardware fields are omitted until a stream is active.
 * Live values are exposed by dedicated getters (e.g. {@link getZoom}).
 */
export async function getCapabilities(cameraId?: string): Promise<CameraCapabilities> {
  if (isMobile()) {
    return await invoke<CameraCapabilities>('plugin:camera-kessdev|get_capabilities', { cameraId })
  }
  return desktopCapabilities(cameraId)
}

// ---------------------------------------------------------------------------
// Fine controls (torch, focus, exposure, white balance)
// ---------------------------------------------------------------------------

/** Turns the torch (continuous light) on or off (mobile). */
export async function setTorch(on: boolean): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|set_torch', { on })
    return
  }
  throw new Error('setTorch is not supported on desktop.')
}

/** Sets the focus point in normalized coordinates (0..1, top-left origin) (mobile). */
export async function setFocusPoint(x: number, y: number): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|set_focus_point', { x, y })
    return
  }
  throw new Error('setFocusPoint is not supported on desktop.')
}

/** Sets the focus mode (mobile). */
export async function setFocusMode(mode: FocusMode): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|set_focus_mode', { mode })
    return
  }
  throw new Error('setFocusMode is not supported on desktop.')
}

/** Sets the exposure compensation in EV (mobile). */
export async function setExposureCompensation(ev: number): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|set_exposure_compensation', { ev })
    return
  }
  throw new Error('setExposureCompensation is not supported on desktop.')
}

/** Sets the exposure mode (mobile). */
export async function setExposureMode(mode: ExposureMode): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|set_exposure_mode', { mode })
    return
  }
  throw new Error('setExposureMode is not supported on desktop.')
}

/** Sets the white balance mode (with an optional Kelvin temperature when `locked`) (mobile). */
export async function setWhiteBalance(options: WhiteBalanceOptions): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|set_white_balance', { ...options })
    return
  }
  throw new Error('setWhiteBalance is not supported on desktop.')
}

// ---------------------------------------------------------------------------
// Video recording
// ---------------------------------------------------------------------------

/** Starts video recording (with audio). */
export async function startRecording(options?: StartRecordingOptions): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|start_recording', { ...options })
    return
  }

  // Reuse the active preview video track when possible so the preview is not
  // invalidated (which would freeze the app's <video> element).
  let videoTrack = activeStream?.getVideoTracks()[0]
  if (!videoTrack) {
    const videoConstraints: MediaTrackConstraints = {}
    if (options?.quality) videoConstraints.height = { ideal: qualityHeight(options.quality) }
    if (options?.fps) videoConstraints.frameRate = { ideal: options.fps }
    const videoStream = await navigator.mediaDevices.getUserMedia({
      video: videoConstraints,
      audio: false
    })
    activeStream = videoStream
    videoTrack = videoStream.getVideoTracks()[0]
  }
  if (!videoTrack) {
    throw new Error('No video track available for recording.')
  }

  if (!activeVideo) {
    const video = document.createElement('video')
    video.muted = true
    video.playsInline = true
    video.srcObject = activeStream
    await video.play()
    activeVideo = video
  }

  recordingAudioStream = await navigator.mediaDevices.getUserMedia({ audio: true })
  const recordStream = new MediaStream([videoTrack, ...recordingAudioStream.getAudioTracks()])

  recordedChunks = []
  recordedBytes = 0
  const recorderOptions: MediaRecorderOptions = {}
  if (options?.bitrate) recorderOptions.videoBitsPerSecond = options.bitrate
  const mimeType = pickDesktopRecordingMimeType()
  if (mimeType) recorderOptions.mimeType = mimeType
  mediaRecorder = new MediaRecorder(recordStream, recorderOptions)
  mediaRecorder.ondataavailable = (event) => {
    if (event.data.size > 0) {
      recordedChunks.push(event.data)
      recordedBytes += event.data.size
    }
  }
  recordingStartedAt = performance.now()
  mediaRecorder.start(RECORDING_TIMESLICE_MS)

  const onProgress = options?.onProgress
  if (onProgress) {
    stopProgressTimer()
    let last: RecordingProgress | null = null
    progressTimer = setInterval(() => {
      const progress = buildRecordingProgress(
        (performance.now() - recordingStartedAt) / 1000,
        recordedBytes
      )
      if (!progressesAreEqual(last, progress)) {
        last = progress
        onProgress.onmessage(progress)
      }
    }, RECORDING_PROGRESS_INTERVAL_MS)
  }
}

/** Stops video recording and returns the recorded media. */
export async function stopRecording(options?: StopRecordingOptions): Promise<Video> {
  if (isMobile()) {
    return await invoke<Video>('plugin:camera-kessdev|stop_recording', { ...options })
  }

  if (!mediaRecorder) {
    throw new Error('Recording is not active. Call startRecording() first.')
  }

  const duration = (performance.now() - recordingStartedAt) / 1000
  const thumbnail = options?.thumbnail ? captureDesktopThumbnail() : undefined
  stopProgressTimer()

  return await new Promise<Video>((resolve, reject) => {
    mediaRecorder!.onstop = () => {
      const mimeType = mediaRecorder!.mimeType || 'video/webm'
      const type = mimeType.split(';')[0]
      const blob = new Blob(recordedChunks, { type })
      const url = URL.createObjectURL(blob)
      recordingAudioStream?.getTracks().forEach((t) => t.stop())
      recordingAudioStream = null
      recordedChunks = []
      recordedBytes = 0
      mediaRecorder = null
      const result: Video = { duration, blob, url }
      if (thumbnail) result.thumbnail = thumbnail
      resolve(result)
    }
    mediaRecorder!.onerror = () => reject(new Error('Video recording failed.'))
    mediaRecorder!.stop()
  })
}

/** Captures the current desktop preview frame as a base64 JPEG thumbnail. */
function captureDesktopThumbnail(): string | undefined {
  if (!activeVideo || !activeVideo.videoWidth || !activeVideo.videoHeight) {
    return undefined
  }
  try {
    const canvas = document.createElement('canvas')
    canvas.width = activeVideo.videoWidth
    canvas.height = activeVideo.videoHeight
    const ctx = canvas.getContext('2d')
    if (!ctx) return undefined
    ctx.drawImage(activeVideo, 0, 0, canvas.width, canvas.height)
    return canvas.toDataURL('image/jpeg')
  } catch {
    return undefined
  }
}

// ---------------------------------------------------------------------------
// Gallery
// ---------------------------------------------------------------------------

/**
 * Saves a captured photo or video to the device gallery (mobile).
 *
 * The media type is inferred from the file extension; pass `options.type` to
 * force it. On desktop this is not supported.
 *
 * @param path Absolute path to the media file.
 */
export async function saveToGallery(path: string, options?: SaveToGalleryOptions): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|save_to_gallery', { path, ...options })
    return
  }
  throw new Error('saveToGallery is not supported on desktop.')
}

// ---------------------------------------------------------------------------
// Permissions
// ---------------------------------------------------------------------------

/** Gets the current camera permission state. */
export async function checkPermissions(): Promise<PermissionState> {
  if (isMobile()) {
    return await checkPermissions_<{ camera: PermissionState }>('camera-kessdev').then((r) => r.camera)
  }
  try {
    const status = await navigator.permissions.query({ name: 'camera' as PermissionName })
    return (status.state as string) as PermissionState
  } catch {
    return 'prompt'
  }
}

/** Requests camera permission. */
export async function requestPermissions(): Promise<PermissionState> {
  if (isMobile()) {
    return await requestPermissions_<{ camera: PermissionState }>('camera-kessdev').then((r) => r.camera)
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ video: true })
    stream.getTracks().forEach((t) => t.stop())
    return 'granted'
  } catch {
    return 'denied'
  }
}

/** Opens the application settings screen (mobile). */
export async function openAppSettings(): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera-kessdev|open_app_settings')
    return
  }
  throw new Error('openAppSettings is not supported on desktop.')
}
