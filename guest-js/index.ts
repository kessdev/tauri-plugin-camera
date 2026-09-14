import {
  invoke,
  convertFileSrc,
  requestPermissions as requestPermissions_,
  checkPermissions as checkPermissions_
} from '@tauri-apps/api/core'
import type { PermissionState } from '@tauri-apps/api/core'

export type { PermissionState } from '@tauri-apps/api/core'
export { convertFileSrc }

export type CameraDirection = 'front' | 'back'
export type FlashMode = 'on' | 'off' | 'auto'

export interface StartPreviewOptions {
  camera?: CameraDirection
  windowed?: boolean
}

export interface CaptureOptions {
  flash?: FlashMode
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
let recordedChunks: Blob[] = []
let recordingStartedAt = 0

async function ensureDesktopStream(options?: StartPreviewOptions): Promise<MediaStream> {
  if (activeStream) return activeStream

  const constraints: MediaStreamConstraints = {
    video: {
      facingMode: options?.camera === 'front' ? 'user' : 'environment'
    },
    audio: false
  }

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
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    mediaRecorder.stop()
  }
  mediaRecorder = null
  recordedChunks = []
  if (activeStream) {
    activeStream.getTracks().forEach((t) => t.stop())
  }
  activeStream = null
  activeVideo = null
}

function captureDesktopFrame(): Photo {
  if (!activeVideo) {
    throw new Error('Camera preview is not active. Call startPreview() first.')
  }
  const width = activeVideo.videoWidth
  const height = activeVideo.videoHeight
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    throw new Error('Failed to create canvas context for capture.')
  }
  ctx.drawImage(activeVideo, 0, 0, width, height)
  const dataUrl = canvas.toDataURL('image/jpeg')
  return {
    dataUrl,
    width,
    height,
    orientation: 1,
    format: 'jpeg',
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
    await invoke('plugin:camera|start_preview', { ...options })
    return
  }
  await ensureDesktopStream(options)
}

/** Stops the camera preview and releases the camera. */
export async function stopPreview(): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera|stop_preview')
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
    return await invoke<Photo>('plugin:camera|capture', { ...options })
  }
  return captureDesktopFrame()
}

// ---------------------------------------------------------------------------
// Camera controls
// ---------------------------------------------------------------------------

/** Switches between front and back camera (mobile). */
export async function flipCamera(): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera|flip_camera')
    return
  }
  throw new Error('flipCamera is not supported on desktop. Restart the preview with a different camera direction.')
}

/** Sets the flash mode (mobile). */
export async function setFlash(mode: FlashMode): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera|set_flash', { mode })
    return
  }
  throw new Error('setFlash is not supported on desktop.')
}

/** Sets the camera zoom factor (mobile). */
export async function setZoom(factor: number): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera|set_zoom', { factor })
    return
  }
  throw new Error('setZoom is not supported on desktop.')
}

// ---------------------------------------------------------------------------
// Video recording
// ---------------------------------------------------------------------------

/** Starts video recording (with audio). */
export async function startRecording(): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera|start_recording')
    return
  }

  if (activeStream) {
    activeStream.getTracks().forEach((t) => t.stop())
    activeStream = null
    activeVideo = null
  }

  const stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true })
  activeStream = stream

  const video = document.createElement('video')
  video.muted = true
  video.playsInline = true
  video.srcObject = stream
  await video.play()
  activeVideo = video

  recordedChunks = []
  mediaRecorder = new MediaRecorder(stream)
  mediaRecorder.ondataavailable = (event) => {
    if (event.data.size > 0) recordedChunks.push(event.data)
  }
  recordingStartedAt = performance.now()
  mediaRecorder.start()
}

/** Stops video recording and returns the recorded media. */
export async function stopRecording(): Promise<Video> {
  if (isMobile()) {
    return await invoke<Video>('plugin:camera|stop_recording')
  }

  if (!mediaRecorder) {
    throw new Error('Recording is not active. Call startRecording() first.')
  }

  const duration = (performance.now() - recordingStartedAt) / 1000

  return await new Promise<Video>((resolve, reject) => {
    mediaRecorder!.onstop = () => {
      const type = mediaRecorder!.mimeType || 'video/webm'
      const blob = new Blob(recordedChunks, { type })
      const url = URL.createObjectURL(blob)
      recordedChunks = []
      mediaRecorder = null
      resolve({ duration, blob, url })
    }
    mediaRecorder!.onerror = () => reject(new Error('Video recording failed.'))
    mediaRecorder!.stop()
  })
}

// ---------------------------------------------------------------------------
// Gallery
// ---------------------------------------------------------------------------

/**
 * Saves a captured image to the device photo gallery (mobile).
 *
 * @param path Absolute path to the image file.
 */
export async function saveToGallery(path: string): Promise<void> {
  if (isMobile()) {
    await invoke('plugin:camera|save_to_gallery', { path })
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
    return await checkPermissions_<{ camera: PermissionState }>('camera').then((r) => r.camera)
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
    return await requestPermissions_<{ camera: PermissionState }>('camera').then((r) => r.camera)
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
    await invoke('plugin:camera|open_app_settings')
    return
  }
  throw new Error('openAppSettings is not supported on desktop.')
}
