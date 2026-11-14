package com.claude.drawterm.session

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One photo for /dev/camN.jpg, on a drawterm thread: open the camera, let
 * exposure settle on a small preview stream, shoot, close.
 */
object CameraCapture {
    private const val TAG = "drawterm-camera"
    private const val MAX_PIXELS = 5_000_000L // plenty for the server; keeps mobile data down

    fun count(ctx: Context): Int = try {
        ctx.getSystemService(CameraManager::class.java).cameraIdList.size
    } catch (e: Exception) {
        0
    }

    @SuppressLint("MissingPermission") // Session checks it
    fun take(ctx: Context, index: Int, displayRotation: Int): ByteArray? {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = try {
            cm.cameraIdList.getOrNull(index)
        } catch (e: Exception) {
            null
        } ?: return null
        val chars = cm.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: return null
        val jpegSize = jpegSizes.filter { it.area() <= MAX_PIXELS }.maxByOrNull { it.area() }
            ?: jpegSizes.minByOrNull { it.area() } ?: return null
        val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { it.width <= 1280 && it.height <= 720 }
            ?.maxByOrNull { it.area() } ?: Size(640, 480)

        val thread = HandlerThread("camera").apply { start() }
        val handler = Handler(thread.looper)
        val result = AtomicReference<ByteArray?>(null)
        val done = CountDownLatch(1)
        val device = AtomicReference<CameraDevice?>(null)
        val session = AtomicReference<CameraCaptureSession?>(null)
        val reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        val texture = SurfaceTexture(false).apply {
            setDefaultBufferSize(previewSize.width, previewSize.height)
        }
        val preview = Surface(texture)
        try {
            reader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.use { img ->
                    val buf = img.planes[0].buffer
                    result.set(ByteArray(buf.remaining()).also { buf.get(it) })
                }
                done.countDown()
            }, handler)

            val opened = CountDownLatch(1)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device.set(d)
                    opened.countDown()
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    opened.countDown()
                    done.countDown()
                }

                override fun onError(d: CameraDevice, error: Int) {
                    Log.w(TAG, "camera $id error $error")
                    d.close()
                    opened.countDown()
                    done.countDown()
                }
            }, handler)
            if (!opened.await(5, TimeUnit.SECONDS)) return null
            val dev = device.get() ?: return null

            val orientation = jpegOrientation(chars, displayRotation)
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            val continuousAf = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes

            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session.set(s)
                    meterThenShoot(dev, s, preview, reader.surface, orientation, continuousAf, handler, done)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.w(TAG, "camera $id: session configuration failed")
                    done.countDown()
                }
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val executor = Executor { handler.post(it) }
                dev.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(preview), OutputConfiguration(reader.surface)),
                        executor,
                        stateCallback,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                dev.createCaptureSession(listOf(preview, reader.surface), stateCallback, handler)
            }
            done.await(10, TimeUnit.SECONDS)
            return result.get()
        } catch (e: Exception) {
            Log.w(TAG, "capture failed", e)
            return null
        } finally {
            runCatching { session.get()?.close() }
            runCatching { device.get()?.close() }
            runCatching { reader.close() }
            preview.release()
            texture.release()
            thread.quitSafely()
        }
    }

    private fun meterThenShoot(
        dev: CameraDevice,
        s: CameraCaptureSession,
        preview: Surface,
        jpeg: Surface,
        orientation: Int,
        continuousAf: Boolean,
        handler: Handler,
        done: CountDownLatch,
    ) {
        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (continuousAf) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
            }.build()
            val start = SystemClock.uptimeMillis()
            var shot = false
            s.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (shot) return
                    val elapsed = SystemClock.uptimeMillis() - start
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    val settled = ae == null ||
                        ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                        ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                        ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                    if ((settled && elapsed > 300) || elapsed > 2000) {
                        shot = true
                        shoot(dev, session, jpeg, orientation, continuousAf, handler, done)
                    }
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "metering failed", e)
            done.countDown()
        }
    }

    private fun shoot(
        dev: CameraDevice,
        s: CameraCaptureSession,
        jpeg: Surface,
        orientation: Int,
        continuousAf: Boolean,
        handler: Handler,
        done: CountDownLatch,
    ) {
        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpeg)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (continuousAf) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
                set(CaptureRequest.JPEG_QUALITY, 90.toByte())
            }.build()
            s.stopRepeating()
            s.capture(req, null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "still capture failed", e)
            done.countDown()
        }
    }

    /** JPEG rotation that makes the photo upright for the display rotation. */
    private fun jpegOrientation(chars: CameraCharacteristics, displayRotation: Int): Int {
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val rotation = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // device orientation as OrientationEventListener would report it
        var device = (360 - rotation) % 360
        if (front) device = -device
        return (sensor + device + 360) % 360
    }

    private fun Size.area(): Long = width.toLong() * height
}
