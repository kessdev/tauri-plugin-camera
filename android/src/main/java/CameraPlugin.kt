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
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
import app.tauri.Logger
import app.tauri.PermissionState
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.Permission
import app.tauri.annotation.PermissionCallback
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val PERMISSION_ALIAS_CAMERA = "camera"
private const val PERMISSION_ALIAS_AUDIO = "microphone"

@InvokeArg
class StartPreviewOptions {
  var camera: String? = null
  var windowed: Boolean = false
}

@InvokeArg
class CaptureOptions {
  var flash: String? = null
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
}

@TauriPlugin(
  permissions = [
    Permission(strings = [Manifest.permission.CAMERA], alias = PERMISSION_ALIAS_CAMERA),
    Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = PERMISSION_ALIAS_AUDIO)
  ]
)
class CameraPlugin(private val activity: Activity) : Plugin(activity) {
  private lateinit var webView: WebView

  private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
  private var cameraProvider: ProcessCameraProvider? = null
  private var previewView: PreviewView? = null
  private var camera: Camera? = null
  private var imageCapture: ImageCapture? = null
  private var videoCapture: VideoCapture<Recorder>? = null
  private var recording: Recording? = null

  private var executor: ExecutorService = Executors.newSingleThreadExecutor()

  private var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK
  private var flashMode: Int = CameraMath.FLASH_OFF
  private var windowed = false
  private var webViewBackground: Drawable? = null

  private var captureInvoke: Invoke? = null
  private var stopRecordingInvoke: Invoke? = null

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

  private fun setupCamera(direction: String, windowed: Boolean) {
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
            bindUseCases(
              provider,
              if (direction == "front") CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            )
            this.cameraProvider = provider
          } catch (e: InterruptedException) {
            // ignored
          } catch (_: ExecutionException) {
            // ignored
          }
        },
        ContextCompat.getMainExecutor(activity)
      )
      this.cameraProviderFuture = future
    }
  }

  private fun bindUseCases(provider: ProcessCameraProvider, lensFacing: Int) {
    val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

    val preview = Preview.Builder().build()
    preview.setSurfaceProvider(previewView?.surfaceProvider)

    val imageCapture = ImageCapture.Builder()
      .setFlashMode(flashMode)
      .build()
    this.imageCapture = imageCapture

    val recorder = Recorder.Builder()
      .setQualitySelector(QualitySelector.from(Quality.HD))
      .build()
    val videoCapture = VideoCapture.withOutput(recorder)
    this.videoCapture = videoCapture

    try {
      camera = provider.bindToLifecycle(
        activity as LifecycleOwner,
        cameraSelector,
        preview,
        imageCapture,
        videoCapture
      )
      currentLensFacing = lensFacing
    } catch (e: Exception) {
      Logger.error(e.message ?: e.toString())
    }
  }

  private fun dismantleCamera() {
    activity.runOnUiThread {
      recording?.let {
        if (!it.isClosed) it.close()
      }
      recording = null
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
    setupCamera(args.camera ?: "back", args.windowed)
    invoke.resolve()
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
      bindUseCases(provider, nextFacing)
      invoke.resolve()
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

  // -------------------------------------------------------------------------
  // Video recording
  // -------------------------------------------------------------------------

  @Command
  fun startRecording(invoke: Invoke) {
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
    recording = pendingRecording.start(executor) { event ->
      when (event) {
        is VideoRecordEvent.Finalize -> {
          if (event.hasError()) {
            stopRecordingInvoke?.reject(event.cause?.message ?: "Video recording failed")
          } else {
            val durationSec = event.outputResults.durationNs / 1_000_000_000.0
            val jsObject = JSObject()
            jsObject.put("path", file.absolutePath)
            jsObject.put("duration", durationSec)
            stopRecordingInvoke?.resolve(jsObject)
          }
          stopRecordingInvoke = null
        }
        else -> {}
      }
    }
    invoke.resolve()
  }

  @Command
  fun stopRecording(invoke: Invoke) {
    val activeRecording = recording
    if (activeRecording == null) {
      invoke.reject("Recording is not active. Call startRecording() first.")
      return
    }
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

    try {
      val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES
          )
          put(MediaStore.Images.Media.IS_PENDING, 1)
        }
      }
      val resolver = activity.contentResolver
      val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw Exception("Failed to insert image into gallery")
      resolver.openOutputStream(uri)?.use { output ->
        file.inputStream().use { input -> input.copyTo(output) }
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
      }
      invoke.resolve()
    } catch (e: Exception) {
      invoke.reject(e.message ?: "Failed to save image to gallery")
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
