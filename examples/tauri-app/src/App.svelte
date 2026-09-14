<script>
  import { onMount } from 'svelte'
  import {
    startPreview,
    stopPreview,
    capture,
    startRecording,
    stopRecording,
    flipCamera,
    checkPermissions,
    requestPermissions,
    getPreviewStream,
    convertFileSrc
  } from 'tauri-plugin-camera'

  let log = $state('')
  let photoSrc = $state('')
  let videoSrc = $state('')
  let recording = $state(false)
  let videoEl

  function isMobile() {
    return /android/i.test(navigator.userAgent) || /iphone|ipad|ipod/i.test(navigator.userAgent)
  }

  function updateLog(value) {
    log += `[${new Date().toLocaleTimeString()}] ` +
      (typeof value === 'string' ? value : JSON.stringify(value)) + '<br>'
  }

  onMount(async () => {
    try {
      const stream = await getPreviewStream()
      if (stream && videoEl) videoEl.srcObject = stream
    } catch {}
  })

  async function _startPreview() {
    try {
      await startPreview({ windowed: true })
      updateLog('preview started')
      if (!isMobile()) {
        const stream = await getPreviewStream()
        if (stream && videoEl) videoEl.srcObject = stream
      }
    } catch (e) { updateLog(e) }
  }

  async function _capture() {
    try {
      const photo = await capture()
      updateLog(photo)
      if (photo.path) {
        photoSrc = convertFileSrc(photo.path)
      } else if (photo.dataUrl) {
        photoSrc = photo.dataUrl
      }
    } catch (e) { updateLog(e) }
  }

  async function _toggleRecording() {
    try {
      if (!recording) {
        await startRecording()
        recording = true
        updateLog('recording started')
      } else {
        const video = await stopRecording()
        recording = false
        updateLog(video)
        videoSrc = video.path ? convertFileSrc(video.path) : (video.url ?? '')
      }
    } catch (e) { updateLog(e) }
  }

  async function _flip() {
    try { await flipCamera(); updateLog('camera flipped') } catch (e) { updateLog(e) }
  }

  async function _check() {
    try { updateLog(await checkPermissions()) } catch (e) { updateLog(e) }
  }

  async function _request() {
    try { updateLog(await requestPermissions()) } catch (e) { updateLog(e) }
  }

  async function _stop() {
    try { await stopPreview(); updateLog('preview stopped') } catch (e) { updateLog(e) }
  }
</script>

<main class="container">
  <h1>Tauri Camera Plugin</h1>

  <video bind:this={videoEl} autoplay muted playsinline></video>

  <div class="row">
    <button onclick={_startPreview}>Start Preview</button>
    <button onclick={_capture}>Capture Photo</button>
    <button onclick={_toggleRecording}>{recording ? 'Stop Recording' : 'Start Recording'}</button>
    <button onclick={_flip}>Flip Camera</button>
    <button onclick={_stop}>Stop Preview</button>
  </div>

  <div class="row">
    <button onclick={_check}>Check Permissions</button>
    <button onclick={_request}>Request Permissions</button>
  </div>

  {#if photoSrc}
    <img src={photoSrc} alt="captured" />
  {/if}

  {#if videoSrc}
    <video src={videoSrc} controls></video>
  {/if}

  <div class="log">{@html log}</div>
</main>

<style>
  video, img {
    width: 100%;
    max-width: 480px;
    background: #000;
  }
  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin: 8px 0;
  }
  .log {
    font-family: monospace;
    font-size: 12px;
    white-space: pre-wrap;
  }
</style>
