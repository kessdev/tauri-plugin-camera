package app.tauri.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Size
import android.view.Surface
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo as CameraXInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.tauri.PermissionState
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.Permission
import app.tauri.annotation.PermissionCallback
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Channel
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSArray
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val PERMISSION_ALIAS_CAMERA = "camera"
private const val PERMISSION_ALIAS_AUDIO = "microphone"

@InvokeArg
class StartPreviewOptions {
  var camera: String? = null
  var cameraId: String? = null
  var windowed: Boolean = false
}

@InvokeArg
class ResolutionArg {
  var width: Int = 0
  var height: Int = 0
}

@InvokeArg
class CaptureOptions {
  var flash: String? = null
  var resolution: ResolutionArg? = null
  var aspectRatio: String? = null
  var format: String? = null
  var quality: Double? = null
}

@InvokeArg
class FlashOptions {
  var mode: String? = null
}

@InvokeArg
class ZoomOptions {
  var factor: Double = 1.0
}

@InvokeArg
class SaveToGalleryOptions {
  var path: String? = null
  var type: String? = null
}

@InvokeArg
class StartRecordingOptions {
  var onProgress: Channel? = null
  var quality: String? = null
  var fps: Int? = null
  var bitrate: Long? = null
}

@InvokeArg
class StopRecordingOptions {
  var thumbnail: Boolean = false
}

@InvokeArg
class GetCapabilitiesOptions {
  var cameraId: String? = null
}

@InvokeArg
class TorchOptions {
  var on: Boolean = false
}

@InvokeArg
class FocusPointOptions {
  var x: Double = 0.5
  var y: Double = 0.5
}

@InvokeArg
class FocusModeOptions {
  var mode: String? = null
}

@InvokeArg
class ExposureOptions {
  var ev: Double = 0.0
}

@InvokeArg
class ExposureModeOptions {
  var mode: String? = null
}

@InvokeArg
class WhiteBalanceOptions {
  var mode: String? = null
  var temperatureK: Int? = null
}

@TauriPlugin(
  permissions = [
    Permission(strings = [Manifest.permission.CAMERA], alias = PERMISSION_ALIAS_CAMERA),
    Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = PERMISSION_ALIAS_AUDIO)
  ]
)
@OptIn(ExperimentalCamera2Interop::class)
class CameraPlugin(private val activity: Activity) : Plugin(activity) {
  private lateinit var webView: WebView

  private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
  private var cameraProvider: ProcessCameraProvider? = null
  private var previewView: PreviewView? = null
  private var camera: Camera? = null
  private var imageCapture: ImageCapture? = null
  private var videoCapture: VideoCapture<Recorder>? = null
  private var recording: Recording? = null
  private var recordingStartedAtNanos = 0L

  private var progressChannel: Channel? = null
  private var lastProgressEmitNanos = 0L
  private var lastProgressDurationNanos = -1L
  private var lastProgressBytes = -1L

  private var executor: ExecutorService = Executors.newSingleThreadExecutor()

  private var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK
  private var currentSelector: CameraSelector? = null
  private var currentVideoQuality: String = "hd"
  private var flashMode: Int = CameraMath.FLASH_OFF
  private var windowed = false
  private var webViewBackground: Drawable? = null

  private var captureInvoke: Invoke? = null
  private var stopRecordingInvoke: Invoke? = null
  private var pendingThumbnail = false

  private var requestPermissionResponse: JSObject? = null

  override fun load(webView: WebView) {
    super.load(webView)
    this.webView = webView
  }

  private fun hasCamera(): Boolean =
    activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

  // -------------------------------------------------------------------------
  // Preview
  // -------------------------------------------------------------------------

  private fun setupCamera(direction: String, cameraId: String?, windowed: Boolean, invoke: Invoke) {
    activity.runOnUiThread {
      val previewView = PreviewView(activity)
      previewView.layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      this.previewView = previewView

      val parent = webView.parent as ViewGroup
      parent.addView(previewView)

      this.windowed = windowed
      if (windowed) {
        webView.bringToFront()
        webViewBackground = webView.background
        webView.setBackgroundColor(Color.TRANSPARENT)
      }

      val future = ProcessCameraProvider.getInstance(activity)
      future.addListener(
        {
          try {
            val provider = future.get()
            val selector = buildSelector(provider, cameraId, direction)
            bindUseCases(provider, selector)
            this.cameraProvider = provider
            invoke.resolve()
          } catch (e: Exception) {
            invoke.reject(e.message ?: "Failed to start camera preview")
          }
        },
        ContextCompat.getMainExecutor(activity)
      )
      this.cameraProviderFuture = future
    }
  }

  private fun buildSelector(
    provider: ProcessCameraProvider,
    cameraId: String?,
    direction: String
  ): CameraSelector {
    if (cameraId != null) {
      val availableIds = provider.availableCameraInfos.map { Camera2CameraInfo.from(it).cameraId }
      CameraMath.resolveCameraId(availableIds, cameraId)
      return CameraSelector.Builder()
        .addCameraFilter { infos ->
          infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
        }
        .build()
    }
    val facing = if (direction == "front") {
      CameraSelector.LENS_FACING_FRONT
    } else {
      CameraSelector.LENS_FACING_BACK
    }
    return CameraSelector.Builder().requireLensFacing(facing).build()
  }

  @Suppress("DEPRECATION")
  private fun bindUseCases(
    provider: ProcessCameraProvider,
    cameraSelector: CameraSelector,
    captureOptions: CaptureOptions? = null
  ) {
    val preview = Preview.Builder().build()
    preview.setSurfaceProvider(previewView?.surfaceProvider)

    val imageBuilder = ImageCapture.Builder()
      .setFlashMode(flashMode)
      .setTargetRotation(previewView?.display?.rotation ?: Surface.ROTATION_0)
    captureOptions?.resolution?.let { res ->
      if (res.width > 0 && res.height > 0) {
        imageBuilder.setTargetResolution(Size(res.width, res.height))
      }
    }
    captureOptions?.aspectRatio?.let { ratio ->
      when (ratio) {
        "4:3" -> imageBuilder.setTargetAspectRatio(AspectRatio.RATIO_4_3)
        "16:9" -> imageBuilder.setTargetAspectRatio(AspectRatio.RATIO_16_9)
        else -> {}
      }
    }
    val imageCapture = imageBuilder.build()
    this.imageCapture = imageCapture

    val recorder = Recorder.Builder()
      .setQualitySelector(QualitySelector.from(qualityOf(currentVideoQuality)))
      .build()
    val videoCapture = VideoCapture.withOutput(recorder)
    this.videoCapture = videoCapture

    camera = provider.bindToLifecycle(
      activity as LifecycleOwner,
      cameraSelector,
      preview,
      imageCapture,
      videoCapture
    )
    currentSelector = cameraSelector
    currentLensFacing = camera?.cameraInfo?.lensFacing ?: currentLensFacing
  }

  private fun qualityOf(name: String?): Quality = when (name) {
    "sd" -> Quality.SD
    "fhd" -> Quality.FHD
    "uhd" -> Quality.UHD
    else -> Quality.HD
  }

  private fun dismantleCamera() {
    activity.runOnUiThread {
      recording?.let {
        if (!it.isClosed) it.close()
      }
      recording = null
      clearProgressChannel()
      if (cameraProvider != null) {
        cameraProvider?.unbindAll()
        val parent = webView.parent as ViewGroup
        parent.removeView(previewView)
        camera = null
        previewView = null
        imageCapture = null
        videoCapture = null
      }
    }
  }

  private fun destroy() {
    dismantleCamera()
    captureInvoke = null
    stopRecordingInvoke = null
    if (windowed) {
      webView.background = webViewBackground
      webViewBackground = null
    }
  }

  @Command
  fun startPreview(invoke: Invoke) {
    val args = invoke.parseArgs(StartPreviewOptions::class.java)
    if (!hasCamera()) {
      invoke.reject("No camera available on this device")
      return
    }
    if (getPermissionState(PERMISSION_ALIAS_CAMERA) != PermissionState.GRANTED) {
      invoke.reject("No permission to use camera. Did you request it yet?")
      return
    }
    dismantleCamera()
    setupCamera(args.camera ?: "back", args.cameraId, args.windowed, invoke)
  }

  @Command
  fun stopPreview(invoke: Invoke) {
    destroy()
    invoke.resolve()
  }

  // -------------------------------------------------------------------------
  // Photo capture
  // -------------------------------------------------------------------------

  @Command
  fun capture(invoke: Invoke) {
    val args = invoke.parseArgs(CaptureOptions::class.java)
    if (args.format != null && args.format != "jpeg") {
      invoke.reject("Format ${args.format} is not supported on Android.")
      return
    }
    val provider = cameraProvider
    val selector = currentSelector
    if (provider != null && selector != null && (args.resolution != null || args.aspectRatio != null)) {
      try {
        bindUseCases(provider, selector, args)
      } catch (e: Exception) {
        invoke.reject(e.message ?: "Failed to configure capture")
        return
      }
    }
    val imageCapture = imageCapture
    if (imageCapture == null) {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }
    if (args.flash != null) {
      imageCapture.flashMode = CameraMath.flashModeCode(args.flash)
    }

    val file = File(activity.cacheDir, "tauri-camera-${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

    captureInvoke = invoke
    imageCapture.takePicture(
      outputOptions,
      executor,
      object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
          val (width, height) = imageDimensions(file)
          val jsObject = JSObject()
          jsObject.put("path", file.absolutePath)
          jsObject.put("width", width)
          jsObject.put("height", height)
          jsObject.put("orientation", 1)
          jsObject.put("format", "jpeg")
          jsObject.put("thumbnail", makeThumbnail(file))
          captureInvoke?.resolve(jsObject)
          captureInvoke = null
        }

        override fun onError(exception: ImageCaptureException) {
          captureInvoke?.reject(exception.message ?: "Failed to capture photo")
          captureInvoke = null
        }
      }
    )
  }

  private fun imageDimensions(file: File): Pair<Int, Int> {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    return Pair(opts.outWidth, opts.outHeight)
  }

  private fun makeThumbnail(file: File, maxDim: Int = 256): String? {
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      var sample = 1
      while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
        sample *= 2
      }
      val opts = BitmapFactory.Options().apply { inSampleSize = sample }
      val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
      val out = ByteArrayOutputStream()
      bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
      bmp.recycle()
      Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) {
      null
    }
  }

  private fun encodeThumbnail(bitmap: Bitmap, maxDim: Int = 256): String {
    val scale = minOf(
      maxDim.toFloat() / bitmap.width,
      maxDim.toFloat() / bitmap.height,
      1f
    )
    val scaled = if (scale < 1f) {
      Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true
      )
    } else {
      bitmap
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    if (scaled !== bitmap) scaled.recycle()
    bitmap.recycle()
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }

  private fun makeVideoThumbnail(file: File): String? {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      val frame = retriever.getFrameAtTime(100_000) ?: retriever.getFrameAtTime(0) ?: return null
      encodeThumbnail(frame)
    } catch (_: Exception) {
      null
    } finally {
      try {
        retriever.release()
      } catch (_: Exception) {
        // ignored
      }
    }
  }

  // -------------------------------------------------------------------------
  // Camera controls
  // -------------------------------------------------------------------------

  @Command
  fun flipCamera(invoke: Invoke) {
    val provider = cameraProvider ?: run {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }
    val nextFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
      CameraSelector.LENS_FACING_FRONT
    } else {
      CameraSelector.LENS_FACING_BACK
    }
    activity.runOnUiThread {
      try {
        bindUseCases(provider, CameraSelector.Builder().requireLensFacing(nextFacing).build())
        invoke.resolve()
      } catch (e: Exception) {
        invoke.reject(e.message ?: "Failed to flip camera")
      }
    }
  }

  @Command
  fun setFlash(invoke: Invoke) {
    val args = invoke.parseArgs(FlashOptions::class.java)
    flashMode = CameraMath.flashModeCode(args.mode)
    imageCapture?.flashMode = flashMode
    invoke.resolve()
  }

  @Command
  fun setZoom(invoke: Invoke) {
    val args = invoke.parseArgs(ZoomOptions::class.java)
    val camera = camera
    if (camera == null) {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }
    val maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
    val factor = CameraMath.clampZoom(args.factor, max = maxZoom.toDouble()).toFloat()
    camera.cameraControl.setZoomRatio(factor)
    invoke.resolve()
  }

  @Command
  fun getZoom(invoke: Invoke) {
    val camera = camera
    if (camera == null) {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }
    val zoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
    invoke.resolveObject(zoom.toDouble())
  }

  @Command
  fun setTorch(invoke: Invoke) {
    val args = invoke.parseArgs(TorchOptions::class.java)
    val camera = camera ?: run {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }
    camera.cameraControl.enableTorch(args.on)
    invoke.resolve()
  }

  @Command
  fun setFocusPoint(invoke: Invoke) {
    val args = invoke.parseArgs(FocusPointOptions::class.java)
    val camera = camera
    val previewView = previewView
    if (camera == null || previewView == null) {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }
    val factory = previewView.meteringPointFactory
    val point = factory.createPoint(
      (args.x.coerceIn(0.0, 1.0) * previewView.width).toFloat(),
      (args.y.coerceIn(0.0, 1.0) * previewView.height).toFloat()
    )
    camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    invoke.resolve()
  }

  @Command
  fun setFocusMode(invoke: Invoke) {
    val args = invoke.parseArgs(FocusModeOptions::class.java)
    val camera = camera ?: run {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }
    setCaptureRequestOption(
      camera, CaptureRequest.CONTROL_AF_MODE, CameraMath.afModeFromFocusMode(args.mode))
    invoke.resolve()
  }

  @Command
  fun setExposureCompensation(invoke: Invoke) {
    val args = invoke.parseArgs(ExposureOptions::class.java)
    val camera = camera ?: run {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }
    val info = camera.cameraInfo
    val step = characteristic(info, CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.0
    val range = characteristic(info, CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    val index = CameraMath.evToCompensationIndex(args.ev, step, range?.lower ?: 0, range?.upper ?: 0)
    camera.cameraControl.setExposureCompensationIndex(index)
    invoke.resolve()
  }

  @Command
  fun setExposureMode(invoke: Invoke) {
    val args = invoke.parseArgs(ExposureModeOptions::class.java)
    val camera = camera ?: run {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }
    setCaptureRequestOption(camera, CaptureRequest.CONTROL_AE_LOCK, args.mode == "locked")
    invoke.resolve()
  }

  @Command
  fun setWhiteBalance(invoke: Invoke) {
    val args = invoke.parseArgs(WhiteBalanceOptions::class.java)
    val camera = camera ?: run {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }
    if (args.mode == "locked") {
      val builder = CaptureRequestOptions.Builder()
        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMath.AWB_MODE_OFF)
      val kelvin = args.temperatureK
      if (kelvin != null && CameraMath.supportsManualAwb(
          characteristic(camera.cameraInfo, CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        )
      ) {
        val gains = CameraMath.awbGainsFromKelvin(kelvin)
        builder.setCaptureRequestOption(
          CaptureRequest.COLOR_CORRECTION_MODE,
          CameraMath.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
        )
        builder.setCaptureRequestOption(
          CaptureRequest.COLOR_CORRECTION_GAINS,
          RggbChannelVector(gains[0], gains[1], gains[2], gains[3])
        )
      }
      camera2Control(camera).setCaptureRequestOptions(builder.build())
    } else {
      setCaptureRequestOption(camera, CaptureRequest.CONTROL_AWB_MODE, CameraMath.AWB_MODE_AUTO)
    }
    invoke.resolve()
  }

  // -------------------------------------------------------------------------
  // Introspection
  // -------------------------------------------------------------------------

  private fun ensureCameraProvider(
    onReady: (ProcessCameraProvider) -> Unit,
    onError: (String) -> Unit
  ) {
    val existing = cameraProvider
    if (existing != null) {
      onReady(existing)
      return
    }
    val future = ProcessCameraProvider.getInstance(activity)
    future.addListener(
      {
        try {
          val provider = future.get()
          cameraProvider = provider
          onReady(provider)
        } catch (e: Exception) {
          onError(e.message ?: "Failed to access the camera provider")
        }
      },
      ContextCompat.getMainExecutor(activity)
    )
  }

  private fun <T> characteristic(info: CameraXInfo, key: CameraCharacteristics.Key<T>): T? {
    return try {
      Camera2CameraInfo.from(info).getCameraCharacteristic(key)
    } catch (_: Exception) {
      null
    }
  }

  private fun camera2Control(camera: Camera): Camera2CameraControl =
    Camera2CameraControl.from(camera.cameraControl)

  private fun <T> setCaptureRequestOption(
    camera: Camera,
    key: CaptureRequest.Key<T>,
    value: T
  ) {
    val options = CaptureRequestOptions.Builder().setCaptureRequestOption(key, value).build()
    camera2Control(camera).setCaptureRequestOptions(options)
  }

  private fun lensName(info: CameraXInfo): String? {
    val focal = characteristic(info, CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
      ?.firstOrNull() ?: return null
    val sensor = characteristic(info, CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
    val equivalent = CameraMath.toEquivalentFocalLength(focal, sensor.width) ?: return null
    return CameraMath.lensFromEquivalentFocalLength(equivalent)
  }

  private fun cameraInfoToMap(info: CameraXInfo): Map<String, Any> {
    val entry = mutableMapOf<String, Any>()
    entry["id"] = Camera2CameraInfo.from(info).cameraId
    CameraMath.facingFromLensFacing(info.lensFacing)?.let { entry["facing"] = it }
    lensName(info)?.let { entry["lens"] = it }
    return entry
  }

  private fun selectCameraInfo(provider: ProcessCameraProvider, cameraId: String?): CameraXInfo? {
    val infos = provider.availableCameraInfos
    if (cameraId != null) {
      return infos.firstOrNull { Camera2CameraInfo.from(it).cameraId == cameraId }
    }
    val facing = if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
      CameraSelector.LENS_FACING_FRONT
    } else {
      CameraSelector.LENS_FACING_BACK
    }
    return infos.firstOrNull { it.lensFacing == facing } ?: infos.firstOrNull()
  }

  private fun zoomRangeOf(info: CameraXInfo): Pair<Double, Double>? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val range = characteristic(info, CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
      if (range != null) return Pair(range.lower.toDouble(), range.upper.toDouble())
    }
    val max = characteristic(info, CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
    return if (max != null && max > 0f) Pair(1.0, max.toDouble()) else null
  }

  private fun capabilitiesToJsObject(info: CameraXInfo): JSObject {
    val obj = JSObject()
    obj.put("cameraId", Camera2CameraInfo.from(info).cameraId)

    val hasFlash = characteristic(info, CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
    obj.put("hasFlash", hasFlash)
    obj.put("hasTorch", hasFlash)

    zoomRangeOf(info)?.let { (min, max) ->
      val range = JSObject()
      range.put("min", min)
      range.put("max", max)
      obj.put("zoomRange", range)
    }

    val afModes = characteristic(info, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
    val focusModes = CameraMath.focusModesFromAfModes(afModes)
    obj.put("supportsFocus", focusModes.isNotEmpty())
    if (focusModes.isNotEmpty()) {
      val modes = JSArray()
      focusModes.forEach { modes.put(it) }
      obj.put("focusModes", modes)
    }

    val compRange = characteristic(info, CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    val compStep = characteristic(info, CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble()
    if (compRange != null && compStep != null && compStep > 0.0) {
      val range = JSObject()
      range.put("minEv", compRange.lower * compStep)
      range.put("maxEv", compRange.upper * compStep)
      obj.put("exposureRange", range)
    }
    val exposureModes = JSArray()
    exposureModes.put("auto")
    exposureModes.put("locked")
    obj.put("exposureModes", exposureModes)

    val wbModes = JSArray()
    wbModes.put("auto")
    wbModes.put("locked")
    obj.put("whiteBalanceModes", wbModes)
    obj.put(
      "supportsWhiteBalanceTemperature",
      CameraMath.supportsManualAwb(characteristic(info, CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES))
    )

    val streamConfig = characteristic(info, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val sizes = streamConfig?.getOutputSizes(ImageFormat.JPEG)
    if (sizes != null) {
      val resolutions = JSArray()
      val aspects = LinkedHashSet<String>()
      for (size in sizes) {
        val entry = JSObject()
        entry.put("width", size.width)
        entry.put("height", size.height)
        resolutions.put(entry)
        val g = CameraMath.gcd(size.width, size.height)
        aspects.add("${size.width / g}:${size.height / g}")
      }
      obj.put("photoResolutions", resolutions)
      val aspectArr = JSArray()
      for (a in listOf("4:3", "16:9", "1:1")) {
        if (aspects.contains(a)) aspectArr.put(a)
      }
      if (aspectArr.length() > 0) obj.put("photoAspects", aspectArr)
    }
    val qualities = JSArray()
    qualities.put("sd")
    qualities.put("hd")
    qualities.put("fhd")
    qualities.put("uhd")
    obj.put("videoQualities", qualities)
    val fpsArr = JSArray()
    fpsArr.put(30)
    obj.put("videoFps", fpsArr)

    val formats = JSArray()
    formats.put("jpeg")
    obj.put("photoFormats", formats)
    obj.put("supportsPause", true)
    return obj
  }

  @Command
  fun getAvailableCameras(invoke: Invoke) {
    ensureCameraProvider(
      onReady = { provider ->
        val result = mutableListOf<Map<String, Any>>()
        for (info in provider.availableCameraInfos) {
          result.add(cameraInfoToMap(info))
        }
        invoke.resolveObject(result)
      },
      onError = { message -> invoke.reject(message) }
    )
  }

  @Command
  fun getCapabilities(invoke: Invoke) {
    val args = invoke.parseArgs(GetCapabilitiesOptions::class.java)
    ensureCameraProvider(
      onReady = { provider ->
        val info = selectCameraInfo(provider, args.cameraId)
        if (info == null) {
          invoke.reject("No camera available for the requested id")
        } else {
          invoke.resolve(capabilitiesToJsObject(info))
        }
      },
      onError = { message -> invoke.reject(message) }
    )
  }

  // -------------------------------------------------------------------------
  // Video recording
  // -------------------------------------------------------------------------

  private fun resetProgressTracking() {
    lastProgressEmitNanos = 0L
    lastProgressDurationNanos = -1L
    lastProgressBytes = -1L
  }

  private fun clearProgressChannel() {
    progressChannel = null
    resetProgressTracking()
  }

  private fun maybeEmitProgress(durationNanos: Long, bytesRecorded: Long) {
    val channel = progressChannel ?: return
    val now = System.nanoTime()
    val changed =
      durationNanos != lastProgressDurationNanos || bytesRecorded != lastProgressBytes
    if (!RecordingMath.progressDue(lastProgressEmitNanos, now, changed)) return
    lastProgressEmitNanos = now
    lastProgressDurationNanos = durationNanos
    lastProgressBytes = bytesRecorded
    channel.sendObject(RecordingMath.progressPayload(durationNanos, bytesRecorded))
  }

  @Command
  fun startRecording(invoke: Invoke) {
    val args = invoke.parseArgs(StartRecordingOptions::class.java)
    val provider = cameraProvider
    val selector = currentSelector
    val requestedQuality = args.quality?.let { CameraMath.videoQualityName(it) }
    if (provider != null && selector != null && requestedQuality != null &&
      requestedQuality != currentVideoQuality
    ) {
      currentVideoQuality = requestedQuality
      try {
        bindUseCases(provider, selector)
      } catch (e: Exception) {
        invoke.reject(e.message ?: "Failed to configure recording")
        return
      }
    }
    val videoCapture = videoCapture
    if (videoCapture == null) {
      invoke.reject("Camera preview is not active. Call startPreview() first.")
      return
    }
    if (getPermissionState(PERMISSION_ALIAS_AUDIO) != PermissionState.GRANTED) {
      invoke.reject("No permission to use microphone. Did you request it yet?")
      return
    }
    val file = File(activity.cacheDir, "tauri-camera-${System.currentTimeMillis()}.mp4")
    val outputOptions = FileOutputOptions.Builder(file).build()

    val pendingRecording = videoCapture.output
      .prepareRecording(activity, outputOptions)
      .withAudioEnabled()

    stopRecordingInvoke = null
    progressChannel = args.onProgress
    resetProgressTracking()
    recordingStartedAtNanos = System.nanoTime()
    recording = pendingRecording.start(executor) { event ->
      when (event) {
        is VideoRecordEvent.Status -> {
          val stats = event.recordingStats
          maybeEmitProgress(stats.recordedDurationNanos, stats.numBytesRecorded)
        }
        is VideoRecordEvent.Finalize -> {
          clearProgressChannel()
          if (event.hasError()) {
            stopRecordingInvoke?.reject(event.cause?.message ?: "Video recording failed")
          } else {
            val durationSec = (System.nanoTime() - recordingStartedAtNanos) / 1_000_000_000.0
            val jsObject = JSObject()
            jsObject.put("path", file.absolutePath)
            jsObject.put("duration", durationSec)
            if (pendingThumbnail) {
              makeVideoThumbnail(file)?.let { jsObject.put("thumbnail", it) }
            }
            stopRecordingInvoke?.resolve(jsObject)
          }
          pendingThumbnail = false
          stopRecordingInvoke = null
        }
        else -> {}
      }
    }
    invoke.resolve()
  }

  @Command
  fun stopRecording(invoke: Invoke) {
    val args = invoke.parseArgs(StopRecordingOptions::class.java)
    val activeRecording = recording
    if (activeRecording == null) {
      invoke.reject("Recording is not active. Call startRecording() first.")
      return
    }
    pendingThumbnail = args.thumbnail
    stopRecordingInvoke = invoke
    activeRecording.stop()
  }

  // -------------------------------------------------------------------------
  // Gallery
  // -------------------------------------------------------------------------

  @Command
  fun saveToGallery(invoke: Invoke) {
    val args = invoke.parseArgs(SaveToGalleryOptions::class.java)
    val path = args.path
    if (path.isNullOrEmpty()) {
      invoke.reject("A file path is required")
      return
    }
    val file = File(path)
    if (!file.exists()) {
      invoke.reject("File does not exist: $path")
      return
    }

    val kind = CameraMath.mediaKind(path, args.type)
    val isVideo = kind == "video"
    val collection = if (isVideo) {
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val directory = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

    try {
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, CameraMath.mimeType(path, kind))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
          put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
      }
      val resolver = activity.contentResolver
      val uri = resolver.insert(collection, values)
        ?: throw Exception("Failed to insert media into gallery")
      resolver.openOutputStream(uri)?.use { output ->
        file.inputStream().use { input -> input.copyTo(output) }
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
      }
      invoke.resolve()
    } catch (e: Exception) {
      invoke.reject(e.message ?: "Failed to save media to gallery")
    }
  }

  // -------------------------------------------------------------------------
  // Permissions
  // -------------------------------------------------------------------------

  @SuppressLint("ObsoleteSdkInt")
  private fun permissionState(alias: String): PermissionState {
    return if (getPermissionState(alias) === PermissionState.GRANTED) PermissionState.GRANTED
    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PermissionState.DENIED
    else PermissionState.GRANTED
  }

  @SuppressLint("ObsoleteSdkInt")
  @PermissionCallback
  fun cameraPermissionCallback(invoke: Invoke) {
    val response = requestPermissionResponse ?: return
    response.put(PERMISSION_ALIAS_CAMERA, permissionState(PERMISSION_ALIAS_CAMERA))

    if (getPermissionState(PERMISSION_ALIAS_AUDIO) !== PermissionState.GRANTED &&
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    ) {
      requestPermissionForAlias(PERMISSION_ALIAS_AUDIO, invoke, "audioPermissionCallback")
      return
    }
    response.put(PERMISSION_ALIAS_AUDIO, permissionState(PERMISSION_ALIAS_AUDIO))
    invoke.resolve(response)
    requestPermissionResponse = null
  }

  @SuppressLint("ObsoleteSdkInt")
  @PermissionCallback
  fun audioPermissionCallback(invoke: Invoke) {
    val response = requestPermissionResponse ?: return
    response.put(PERMISSION_ALIAS_AUDIO, permissionState(PERMISSION_ALIAS_AUDIO))
    invoke.resolve(response)
    requestPermissionResponse = null
  }

  @SuppressLint("ObsoleteSdkInt")
  @Command
  override fun requestPermissions(invoke: Invoke) {
    val response = JSObject()
    requestPermissionResponse = response

    if (getPermissionState(PERMISSION_ALIAS_CAMERA) === PermissionState.GRANTED) {
      response.put(PERMISSION_ALIAS_CAMERA, PermissionState.GRANTED)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      requestPermissionForAlias(PERMISSION_ALIAS_CAMERA, invoke, "cameraPermissionCallback")
      return
    } else {
      response.put(PERMISSION_ALIAS_CAMERA, PermissionState.GRANTED)
    }

    if (getPermissionState(PERMISSION_ALIAS_AUDIO) === PermissionState.GRANTED) {
      response.put(PERMISSION_ALIAS_AUDIO, PermissionState.GRANTED)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      requestPermissionForAlias(PERMISSION_ALIAS_AUDIO, invoke, "audioPermissionCallback")
      return
    } else {
      response.put(PERMISSION_ALIAS_AUDIO, PermissionState.GRANTED)
    }

    invoke.resolve(response)
    requestPermissionResponse = null
  }

  @Command
  fun openAppSettings(invoke: Invoke) {
    val intent = Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.fromParts("package", activity.packageName, null)
    )
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivityForResult(invoke, intent, "openSettingsResult")
  }

  @app.tauri.annotation.ActivityCallback
  private fun openSettingsResult(invoke: Invoke, result: androidx.activity.result.ActivityResult) {
    invoke.resolve()
  }
}
