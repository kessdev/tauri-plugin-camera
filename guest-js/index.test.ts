import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(),
  convertFileSrc: (p: string) => `asset://${p}`,
  requestPermissions: vi.fn(),
  checkPermissions: vi.fn()
}))

import type { Channel } from '@tauri-apps/api/core'
import { invoke, requestPermissions, checkPermissions } from '@tauri-apps/api/core'
import {
  capture,
  startPreview,
  stopPreview,
  startRecording,
  stopRecording,
  flipCamera,
  setFlash,
  setZoom,
  getZoom,
  setTorch,
  setFocusPoint,
  setFocusMode,
  setExposureCompensation,
  setExposureMode,
  setWhiteBalance,
  getAvailableCameras,
  getCapabilities,
  saveToGallery,
  requestPermissions as requestCameraPermissions,
  checkPermissions as checkCameraPermissions,
  convertFileSrc,
  type RecordingProgress
} from './index'

const invokeMock = vi.mocked(invoke)
const requestPermissionsMock = vi.mocked(requestPermissions)
const checkPermissionsMock = vi.mocked(checkPermissions)

function setUserAgent(ua: string): void {
  vi.stubGlobal('navigator', { userAgent: ua })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('mobile dispatch', () => {
  beforeEach(() => {
    setUserAgent('Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36')
  })

  it('re-exports convertFileSrc', () => {
    expect(convertFileSrc('/tmp/a.jpg')).toBe('asset:///tmp/a.jpg')
  })

  it('capture invokes the mobile command with options', async () => {
    invokeMock.mockResolvedValue({
      path: '/tmp/a.jpg',
      width: 1920,
      height: 1080,
      orientation: 1,
      format: 'jpeg'
    })
    const photo = await capture({ flash: 'auto' })
    expect(invokeMock).toHaveBeenCalledWith('plugin:camera-kessdev|capture', { flash: 'auto' })
    expect(photo.path).toBe('/tmp/a.jpg')
  })

  it('startPreview invokes the mobile command', async () => {
    invokeMock.mockResolvedValue(undefined)
    await startPreview({ camera: 'front', cameraId: 'lens-2', windowed: true })
    expect(invokeMock).toHaveBeenCalledWith('plugin:camera-kessdev|start_preview', {
      camera: 'front',
      cameraId: 'lens-2',
      windowed: true
    })
  })

  it('flipCamera, setFlash and setZoom invoke mobile commands', async () => {
    invokeMock.mockResolvedValue(undefined)
    await flipCamera()
    await setFlash('on')
    await setZoom(2.5)
    expect(invokeMock).toHaveBeenNthCalledWith(1, 'plugin:camera-kessdev|flip_camera')
    expect(invokeMock).toHaveBeenNthCalledWith(2, 'plugin:camera-kessdev|set_flash', { mode: 'on' })
    expect(invokeMock).toHaveBeenNthCalledWith(3, 'plugin:camera-kessdev|set_zoom', { factor: 2.5 })
  })

  it('fine controls invoke mobile commands', async () => {
    invokeMock.mockResolvedValue(undefined)
    await setTorch(true)
    await setFocusPoint(0.5, 0.25)
    await setFocusMode('continuous')
    await setExposureCompensation(1.5)
    await setExposureMode('locked')
    await setWhiteBalance({ mode: 'locked', temperatureK: 5000 })
    expect(invokeMock).toHaveBeenNthCalledWith(1, 'plugin:camera-kessdev|set_torch', { on: true })
    expect(invokeMock).toHaveBeenNthCalledWith(2, 'plugin:camera-kessdev|set_focus_point', {
      x: 0.5,
      y: 0.25
    })
    expect(invokeMock).toHaveBeenNthCalledWith(3, 'plugin:camera-kessdev|set_focus_mode', {
      mode: 'continuous'
    })
    expect(invokeMock).toHaveBeenNthCalledWith(4, 'plugin:camera-kessdev|set_exposure_compensation', {
      ev: 1.5
    })
    expect(invokeMock).toHaveBeenNthCalledWith(5, 'plugin:camera-kessdev|set_exposure_mode', {
      mode: 'locked'
    })
    expect(invokeMock).toHaveBeenNthCalledWith(6, 'plugin:camera-kessdev|set_white_balance', {
      mode: 'locked',
      temperatureK: 5000
    })
  })

  it('getZoom invokes the mobile command', async () => {
    invokeMock.mockResolvedValueOnce(2)
    const zoom = await getZoom()
    expect(invokeMock).toHaveBeenNthCalledWith(1, 'plugin:camera-kessdev|get_zoom')
    expect(zoom).toBe(2)
  })

  it('getAvailableCameras invokes the mobile command', async () => {
    const cameras = [{ id: '0', facing: 'back', lens: 'wide' }]
    invokeMock.mockResolvedValueOnce(cameras)
    const result = await getAvailableCameras()
    expect(invokeMock).toHaveBeenNthCalledWith(1, 'plugin:camera-kessdev|get_available_cameras')
    expect(result).toEqual(cameras)
  })

  it('getCapabilities forwards cameraId to the mobile command', async () => {
    const caps = {
      cameraId: '1',
      hasFlash: true,
      hasTorch: true,
      zoomRange: { min: 1, max: 8 },
      supportsFocus: true,
      supportsPause: false
    }
    invokeMock.mockResolvedValueOnce(caps)
    const result = await getCapabilities('1')
    expect(invokeMock).toHaveBeenNthCalledWith(1, 'plugin:camera-kessdev|get_capabilities', {
      cameraId: '1'
    })
    expect(result).toEqual(caps)
  })

  it('saveToGallery forwards the media type override', async () => {
    invokeMock.mockResolvedValue(undefined)
    await saveToGallery('/tmp/a.mp4', { type: 'video' })
    expect(invokeMock).toHaveBeenCalledWith('plugin:camera-kessdev|save_to_gallery', {
      path: '/tmp/a.mp4',
      type: 'video'
    })
  })

  it('requestPermissions delegates to the core helper', async () => {
    requestPermissionsMock.mockResolvedValue({ camera: 'granted' })
    const state = await requestCameraPermissions()
    expect(requestPermissionsMock).toHaveBeenCalledWith('camera-kessdev')
    expect(state).toBe('granted')
  })

  it('checkPermissions delegates to the core helper', async () => {
    checkPermissionsMock.mockResolvedValue({ camera: 'denied' })
    const state = await checkCameraPermissions()
    expect(checkPermissionsMock).toHaveBeenCalledWith('camera-kessdev')
    expect(state).toBe('denied')
  })
})

describe('desktop degradation', () => {
  beforeEach(() => {
    setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64)')
  })

  it('flipCamera throws on desktop', async () => {
    await expect(flipCamera()).rejects.toThrow(/not supported on desktop/)
  })

  it('setFlash throws on desktop', async () => {
    await expect(setFlash('on')).rejects.toThrow(/not supported on desktop/)
  })

  it('fine controls throw on desktop', async () => {
    await expect(setTorch(true)).rejects.toThrow(/not supported on desktop/)
    await expect(setFocusPoint(0.5, 0.5)).rejects.toThrow(/not supported on desktop/)
    await expect(setFocusMode('auto')).rejects.toThrow(/not supported on desktop/)
    await expect(setExposureCompensation(1)).rejects.toThrow(/not supported on desktop/)
    await expect(setExposureMode('auto')).rejects.toThrow(/not supported on desktop/)
    await expect(setWhiteBalance({ mode: 'auto' })).rejects.toThrow(/not supported on desktop/)
  })

  it('getZoom throws on desktop', async () => {
    await expect(getZoom()).rejects.toThrow(/not supported on desktop/)
  })
})

describe('desktop introspection', () => {
  beforeEach(() => {
    setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64)')
  })

  afterEach(async () => {
    await stopPreview()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('enumerates video input devices', async () => {
    const enumerateDevices = vi.fn().mockResolvedValue([
      { kind: 'videoinput', deviceId: 'cam1', label: 'Front Camera (user)' },
      { kind: 'audioinput', deviceId: 'mic1', label: 'Microphone' },
      { kind: 'videoinput', deviceId: 'cam2', label: 'Back Camera (environment)' }
    ])
    vi.stubGlobal('navigator', {
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      mediaDevices: { enumerateDevices }
    })

    const cameras = await getAvailableCameras()
    expect(cameras).toEqual([
      { id: 'cam1', facing: 'front', label: 'Front Camera (user)' },
      { id: 'cam2', facing: 'back', label: 'Back Camera (environment)' }
    ])
  })

  it('omits hardware capabilities without an active stream', async () => {
    await stopPreview()
    const caps = await getCapabilities()
    expect(caps.hasFlash).toBe(false)
    expect(caps.hasTorch).toBe(false)
    expect(caps.supportsPause).toBe(true)
    expect(caps.zoomRange).toBeUndefined()
    expect(caps.focusModes).toBeUndefined()
  })

  it('reads hardware capabilities from the active track', async () => {
    const track = {
      stop: vi.fn(),
      getCapabilities: () => ({
        zoom: { min: 1, max: 4 },
        torch: true,
        focusMode: ['continuous', 'locked']
      })
    }
    const stream = { getTracks: () => [track], getVideoTracks: () => [track] }
    vi.stubGlobal('navigator', {
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      mediaDevices: { getUserMedia: vi.fn().mockResolvedValue(stream) }
    })
    vi.stubGlobal('document', {
      createElement: () => ({ muted: false, playsInline: false, play: vi.fn().mockResolvedValue(undefined) })
    })

    await startPreview()
    const caps = await getCapabilities('cam1')
    expect(caps.cameraId).toBe('cam1')
    expect(caps.zoomRange).toEqual({ min: 1, max: 4 })
    expect(caps.hasTorch).toBe(true)
    expect(caps.supportsFocus).toBe(true)
    expect(caps.focusModes).toEqual(['continuous', 'locked'])
  })

  it('reports native backend availability in capabilities', async () => {
    await stopPreview()
    invokeMock.mockResolvedValue(true)
    const caps = await getCapabilities()
    expect(invokeMock).toHaveBeenCalledWith('plugin:camera-kessdev|native_desktop_available')
    expect(caps.supportsNativeRecording).toBe(true)
  })

  it('startPreview selects a camera by deviceId on desktop', async () => {
    const track = { stop: vi.fn() }
    const stream = { getTracks: () => [track], getVideoTracks: () => [track] }
    const getUserMedia = vi.fn().mockResolvedValue(stream)
    vi.stubGlobal('navigator', {
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      mediaDevices: { getUserMedia }
    })
    vi.stubGlobal('document', {
      createElement: () => ({ muted: false, playsInline: false, play: vi.fn().mockResolvedValue(undefined) })
    })

    await startPreview({ cameraId: 'cam2' })
    expect(getUserMedia).toHaveBeenCalledWith({
      video: { deviceId: { exact: 'cam2' } },
      audio: false
    })
  })
})

describe('desktop recording telemetry', () => {
  class MockMediaRecorder {
    static instances: MockMediaRecorder[] = []
    stream: unknown
    options: unknown
    mimeType = 'video/webm'
    state = 'inactive'
    ondataavailable: ((e: { data: { size: number } }) => void) | null = null
    onstop: (() => void) | null = null
    onerror: (() => void) | null = null
    constructor(stream: unknown, options?: unknown) {
      this.stream = stream
      this.options = options
      MockMediaRecorder.instances.push(this)
    }
    start(): void {
      this.state = 'recording'
    }
    stop(): void {
      this.state = 'inactive'
      this.onstop?.()
    }
  }

  let clock = 0

  function fakeChannel(): { onmessage: ReturnType<typeof vi.fn> } {
    return { onmessage: vi.fn() }
  }

  beforeEach(() => {
    vi.useFakeTimers()
    clock = 0
    vi.spyOn(performance, 'now').mockImplementation(() => clock)
    MockMediaRecorder.instances = []

    function fakeStream(): unknown {
      const video = { stop: vi.fn(), kind: 'video' }
      const audio = { stop: vi.fn(), kind: 'audio' }
      return {
        getTracks: () => [video, audio],
        getVideoTracks: () => [video],
        getAudioTracks: () => [audio]
      }
    }
    vi.stubGlobal('navigator', {
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      mediaDevices: {
        getUserMedia: vi.fn().mockImplementation(() => Promise.resolve(fakeStream()))
      }
    })
    vi.stubGlobal(
      'MediaStream',
      class {
        tracks: unknown[]
        constructor(tracks: unknown[] = []) {
          this.tracks = tracks
        }
      }
    )
    vi.stubGlobal('document', {
      createElement: (tag: string) => {
        if (tag === 'canvas') {
          return {
            width: 0,
            height: 0,
            getContext: () => ({ drawImage: vi.fn() }),
            toDataURL: () => 'data:image/jpeg;base64,thumb'
          }
        }
        return {
          muted: false,
          playsInline: false,
          videoWidth: 640,
          videoHeight: 480,
          play: vi.fn().mockResolvedValue(undefined)
        }
      }
    })
    vi.stubGlobal('MediaRecorder', MockMediaRecorder)
    vi.stubGlobal('URL', { ...URL, createObjectURL: vi.fn(() => 'blob:mock') })
  })

  afterEach(async () => {
    await stopPreview()
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('emits telemetry to the onProgress channel', async () => {
    const channel = fakeChannel()
    await startRecording({ onProgress: channel as unknown as Channel<RecordingProgress> })

    MockMediaRecorder.instances[0].ondataavailable?.({ data: { size: 2000 } })
    clock = 1000
    vi.advanceTimersByTime(250)

    expect(channel.onmessage).toHaveBeenCalledTimes(1)
    expect(channel.onmessage).toHaveBeenCalledWith({
      duration: 1,
      bytesRecorded: 2000,
      bitrate: 16000
    })

    await stopRecording()
  })

  it('stops emitting after recording ends', async () => {
    const channel = fakeChannel()
    await startRecording({ onProgress: channel as unknown as Channel<RecordingProgress> })
    MockMediaRecorder.instances[0].ondataavailable?.({ data: { size: 500 } })
    clock = 1000
    vi.advanceTimersByTime(250)
    const callsBefore = channel.onmessage.mock.calls.length

    await stopRecording()
    clock = 5000
    vi.advanceTimersByTime(2000)

    expect(channel.onmessage.mock.calls.length).toBe(callsBefore)
  })

  it('records without telemetry when no channel is provided', async () => {
    await startRecording()
    MockMediaRecorder.instances[0].ondataavailable?.({ data: { size: 100 } })
    clock = 1000
    vi.advanceTimersByTime(250)

    const video = await stopRecording()
    expect(video.url).toBe('blob:mock')
  })

  it('includes a thumbnail only when requested', async () => {
    await startRecording()
    MockMediaRecorder.instances[0].ondataavailable?.({ data: { size: 100 } })
    const withoutThumbnail = await stopRecording()
    expect(withoutThumbnail.thumbnail).toBeUndefined()

    await startRecording()
    MockMediaRecorder.instances[1].ondataavailable?.({ data: { size: 100 } })
    const withThumbnail = await stopRecording({ thumbnail: true })
    expect(withThumbnail.thumbnail).toBe('data:image/jpeg;base64,thumb')
  })

  it('applies quality, fps and bitrate on desktop', async () => {
    const getUserMedia = vi.mocked(navigator.mediaDevices.getUserMedia)
    await startRecording({ quality: 'fhd', fps: 30, bitrate: 2000000 })
    expect(getUserMedia).toHaveBeenCalledWith({
      video: { height: { ideal: 1080 }, frameRate: { ideal: 30 } },
      audio: false
    })
    expect(getUserMedia).toHaveBeenCalledWith({ audio: true })
    expect(MockMediaRecorder.instances[0].options).toEqual({ videoBitsPerSecond: 2000000 })
    await stopRecording()
  })

  it('omits bytesRecorded until a chunk arrives', async () => {
    const channel = fakeChannel()
    await startRecording({ onProgress: channel as unknown as Channel<RecordingProgress> })
    clock = 250
    vi.advanceTimersByTime(250)

    const first = channel.onmessage.mock.calls[0][0]
    expect(first.bytesRecorded).toBeUndefined()
    expect(first.bitrate).toBeUndefined()
    await stopRecording()
  })
})

describe('desktop capture config', () => {
  beforeEach(() => {
    setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64)')
  })

  afterEach(async () => {
    await stopPreview()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  function stubStream(): void {
    const track = { stop: vi.fn() }
    const stream = { getTracks: () => [track], getVideoTracks: () => [track] }
    vi.stubGlobal('navigator', {
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      mediaDevices: { getUserMedia: vi.fn().mockResolvedValue(stream) }
    })
  }

  it('resizes and encodes the capture per options', async () => {
    stubStream()
    const toDataURL = vi.fn(() => 'data:image/png;base64,x')
    const canvas = {
      width: 0,
      height: 0,
      getContext: () => ({ drawImage: vi.fn() }),
      toDataURL
    }
    vi.stubGlobal('document', {
      createElement: (tag: string) =>
        tag === 'canvas'
          ? canvas
          : {
              muted: false,
              playsInline: false,
              videoWidth: 640,
              videoHeight: 480,
              play: vi.fn().mockResolvedValue(undefined)
            }
    })

    await startPreview()
    const photo = await capture({ resolution: { width: 320, height: 240 }, format: 'png', quality: 0.5 })
    expect(canvas.width).toBe(320)
    expect(canvas.height).toBe(240)
    expect(toDataURL).toHaveBeenCalledWith('image/png', 0.5)
    expect(photo.format).toBe('png')
  })

  it('rejects an unsupported desktop format', async () => {
    stubStream()
    vi.stubGlobal('document', {
      createElement: (tag: string) =>
        tag === 'canvas'
          ? { width: 0, height: 0, getContext: () => ({ drawImage: vi.fn() }), toDataURL: () => '' }
          : {
              muted: false,
              playsInline: false,
              videoWidth: 640,
              videoHeight: 480,
              play: vi.fn().mockResolvedValue(undefined)
            }
    })

    await startPreview()
    await expect(capture({ format: 'heif' })).rejects.toThrow(/not supported on desktop/)
  })
})
