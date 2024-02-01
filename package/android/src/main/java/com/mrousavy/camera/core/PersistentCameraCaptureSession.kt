package com.mrousavy.camera.core

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import com.mrousavy.camera.core.outputs.SurfaceOutput
import com.mrousavy.camera.extensions.capture
import com.mrousavy.camera.extensions.createCaptureSession
import com.mrousavy.camera.extensions.isValid
import com.mrousavy.camera.extensions.openCamera
import com.mrousavy.camera.extensions.tryAbortCaptures
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/**
 * A [CameraCaptureSession] wrapper that safely handles interruptions and remains open whenever available.
 */
class PersistentCameraCaptureSession(private val cameraManager: CameraManager, private val callback: Callback) : Closeable {
  companion object {
    private const val TAG = "PersistentCameraCaptureSession"
  }

  // Inputs/Dependencies
  private var cameraId: String? = null
  private var outputs: List<SurfaceOutput> = emptyList()
  private var repeatingRequest: RepeatingRequest? = null
  private var isActive = false

  // State/Dependants
  private var device: CameraDevice? = null // depends on [cameraId]
  private var session: CameraCaptureSession? = null // depends on [device, surfaceOutputs]
  private var captureRequest: CaptureRequest? = null // depends on [cameraId, repeatingRequest]
  private var cameraDeviceDetails: CameraDeviceDetails? = null // depends on [device]
    get() {
      val device = device ?: return null
      if (field == null || field?.cameraId != device.id) {
        field = CameraDeviceDetails(cameraManager, device.id)
      }
      return field
    }

  private val mutex = Mutex()

  val isRunning: Boolean
    get() = isActive && session != null && device != null

  private suspend fun getOrCreateDevice(cameraId: String): CameraDevice {
    val currentDevice = device
    if (currentDevice?.id == cameraId && currentDevice.isValid) {
      return currentDevice
    }

    val newDevice = cameraManager.openCamera(cameraId, { device, error ->
      if (this.device == device) {
        this.session?.tryAbortCaptures()
        this.session = null
        this.device = null
        this.isActive = false
      }
      if (error != null) {
        callback.onError(error)
      }
    }, CameraQueues.cameraQueue)
    this.device = newDevice
    return newDevice
  }

  private suspend fun getOrCreateSession(device: CameraDevice, outputs: List<SurfaceOutput>): CameraCaptureSession {
    val currentSession = session
    if (currentSession?.device == device) {
      return currentSession
    }

    if (outputs.isEmpty()) {
      throw Error("Cannot configure PersistentCameraCaptureSession without outputs!")
    }

    val newSession = device.createCaptureSession(cameraManager, outputs, { session ->
      if (this.session == session) {
        this.session?.tryAbortCaptures()
        this.session = null
        this.isActive = false
      }
    }, CameraQueues.cameraQueue)
    session = newSession
    return newSession
  }

  private suspend fun configure() {
    val cameraId = cameraId ?: return
    val repeatingRequest = repeatingRequest ?: return
    val cameraDeviceDetails = cameraDeviceDetails ?: return
    val outputs = outputs
    if (outputs.isEmpty()) {
      return
    }

    val device = getOrCreateDevice(cameraId)
    val session = getOrCreateSession(device, outputs)

    if (isActive) {
      val captureRequest = repeatingRequest.toRepeatingRequest(device, cameraDeviceDetails, outputs)
      session.setRepeatingRequest(captureRequest, null, null)
    } else {
      session.stopRepeating()
    }
  }

  private fun assertLocked(method: String) {
    if (!mutex.isLocked) {
      throw SessionIsNotLockedError("Failed to call $method, session is not locked! Call beginConfiguration() first.")
    }
  }

  suspend fun withConfiguration(block: suspend () -> Unit) {
    mutex.withLock {
      block()
      configure()
    }
  }

  fun setInput(cameraId: String) {
    assertLocked("setInput")
    if (this.cameraId != cameraId || device?.id != cameraId) {
      this.cameraId = cameraId

      // Abort any captures in the session so we get the onCaptureFailed handler for any outstanding photos
      session?.tryAbortCaptures()
      session = null
      // Closing the device will also close the session above - even faster than manually closing it.
      device?.close()
      device = null
    }
  }

  fun setOutputs(outputs: List<SurfaceOutput>) {
    assertLocked("setOutputs")
    if (this.outputs != outputs) {
      this.outputs = outputs

      if (outputs.isNotEmpty()) {
        // Outputs have changed to something else, we don't wanna destroy the session directly
        // so the outputs can be kept warm. The session that gets created next will take over the outputs.
        session?.tryAbortCaptures()
      } else {
        // Just stop it, we don't have any outputs
        session?.close()
      }
      session = null
    }
  }

  fun setRepeatingRequest(request: RepeatingRequest) {
    assertLocked("setRepeatingRequest")
    if (this.repeatingRequest != request) {
      this.repeatingRequest = request
    }
  }

  fun setIsActive(isActive: Boolean) {
    assertLocked("setIsActive")
    if (this.isActive != isActive) {
      this.isActive = isActive
    }
  }

  suspend fun capture(request: CaptureRequest, enableShutterSound: Boolean): TotalCaptureResult {
    val session = session ?: throw CameraNotReadyError()
    return session.capture(request, enableShutterSound)
  }

  override fun close() {
    session?.tryAbortCaptures()
    device?.close()
  }

  interface Callback {
    fun onError(error: Throwable)
  }

  class SessionIsNotLockedError(message: String): Error(message)
}
