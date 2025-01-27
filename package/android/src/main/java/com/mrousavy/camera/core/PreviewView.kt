package com.mrousavy.camera.core

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.facebook.react.bridge.UiThreadUtil
import com.mrousavy.camera.extensions.getMaximumPreviewSize
import com.mrousavy.camera.types.ResizeMode
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class PreviewView(context: Context, callback: SurfaceHolder.Callback) : SurfaceView(context) {
  var size: Size = getMaximumPreviewSize()
    set(value) {
      field = value
      UiThreadUtil.runOnUiThread {
        Log.i(TAG, "Setting PreviewView Surface Size to $width x $height...")
        holder.setFixedSize(value.height, value.width)
        requestLayout()
        invalidate()
      }
    }
  var resizeMode: ResizeMode = ResizeMode.COVER
    set(value) {
      field = value
      UiThreadUtil.runOnUiThread {
        requestLayout()
        invalidate()
      }
    }

  init {
    Log.i(TAG, "Creating PreviewView...")
    layoutParams = FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.MATCH_PARENT,
      FrameLayout.LayoutParams.MATCH_PARENT,
      Gravity.CENTER
    )
    holder.addCallback(callback)
  }

  @SuppressLint("DrawAllocation")
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)

    val viewSize = Size(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))

    Log.i(TAG, "PreviewView is $viewSize. Resizing to: $viewSize ($resizeMode)")
    setMeasuredDimension(viewSize.width, viewSize.height)
  }

  companion object {
    private const val TAG = "PreviewView"
  }
}
