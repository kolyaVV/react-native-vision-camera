package com.mrousavy.camera.utils

import com.facebook.react.bridge.Callback
import com.mrousavy.camera.core.CameraError
import com.mrousavy.camera.core.UnknownCameraError

inline fun withCallback(callback: Callback, closure: () -> Any?) {
  try {
    val result = closure()
    callback.invoke(result, null)
  } catch (e: Throwable) {
    e.printStackTrace()
    val error = if (e is CameraError) e else UnknownCameraError(e)
    val errorMap = makeErrorMap(error)
    callback.invoke(null, errorMap)
  }
}