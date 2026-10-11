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
  startRecording,
  stopRecording,
  flipCamera,
  setFlash,
  setZoom,
  getZoom,
  getZoomRange,
  isFlashAvailable,
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
    await startPreview({ camera: 'front', windowed: true })
    expect(invokeMock).toHaveBeenCalledWith('plugin:camera-kessdev|start_preview', {
      camera: 'front',
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

  it('getZoom, getZoomRange and isFlashAvailable invoke mobile commands', async () => {
    invokeMock.mockResolvedValueOnce(2).mockResolvedValueOnce({ min: 1, max: 8, current: 2 })
    const zoom = await getZoom()
    const range = await getZoomRange()
    expect(invokeMock).toHaveBeenNthCalledWith(1, 'plugin:camera-kessdev|get_zoom')
    expect(invokeMock).toHaveBeenNthCalledWith(2, 'plugin:camera-kessdev|get_zoom_range')
    expect(zoom).toBe(2)
    expect(range).toEqual({ min: 1, max: 8, current: 2 })

    invokeMock.mockResolvedValueOnce(true)
    const flash = await isFlashAvailable()
    expect(invokeMock).toHaveBeenNthCalledWith(3, 'plugin:camera-kessdev|is_flash_available')
    expect(flash).toBe(true)
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

  it('getZoom and getZoomRange throw on desktop', async () => {
    await expect(getZoom()).rejects.toThrow(/not supported on desktop/)
    await expect(getZoomRange()).rejects.toThrow(/not supported on desktop/)
  })

  it('isFlashAvailable resolves to false on desktop', async () => {
    await expect(isFlashAvailable()).resolves.toBe(false)
  })
})

describe('desktop recording telemetry', () => {
  class MockMediaRecorder {
    static instances: MockMediaRecorder[] = []
    stream: unknown
    mimeType = 'video/webm'
    state = 'inactive'
    ondataavailable: ((e: { data: { size: number } }) => void) | null = null
    onstop: (() => void) | null = null
    onerror: (() => void) | null = null
    constructor(stream: unknown) {
      this.stream = stream
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

    const stream = { getTracks: () => [{ stop: vi.fn() }] }
    vi.stubGlobal('navigator', {
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      mediaDevices: { getUserMedia: vi.fn().mockResolvedValue(stream) }
    })
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

  afterEach(() => {
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
})
