import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(),
  convertFileSrc: (p: string) => `asset://${p}`,
  requestPermissions: vi.fn(),
  checkPermissions: vi.fn()
}))

import { invoke, requestPermissions, checkPermissions } from '@tauri-apps/api/core'
import {
  capture,
  startPreview,
  flipCamera,
  setFlash,
  setZoom,
  requestPermissions as requestCameraPermissions,
  checkPermissions as checkCameraPermissions,
  convertFileSrc
} from './index'

const invokeMock = vi.mocked(invoke)
const requestPermissionsMock = vi.mocked(requestPermissions)
const checkPermissionsMock = vi.mocked(checkPermissions)

function setUserAgent(ua: string): void {
  Object.defineProperty(globalThis.navigator, 'userAgent', {
    value: ua,
    configurable: true,
    writable: true
  })
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
})
