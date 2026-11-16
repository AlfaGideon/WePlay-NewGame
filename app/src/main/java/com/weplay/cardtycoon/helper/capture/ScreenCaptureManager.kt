package com.weplay.cardtycoon.helper.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Manages Android MediaProjection + VirtualDisplay + ImageReader to capture live screen Bitmaps
 * safely on Android 8 through Android 14+.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val onProjectionStopped: () -> Unit
) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400
    private var screenDensity: Int = 420
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
            onProjectionStopped()
        }
    }

    fun start(resultCode: Int, resultData: Intent): Boolean {
        stop()
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, resultData) ?: return false
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        readDisplayMetrics()

        val reader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "CardTycoonCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )
        return true
    }

    /**
     * Grabs the latest screen frame as an ARGB_8888 Bitmap (caller owns recycling).
     * Returns null if no frame is available yet.
     */
    fun captureFrame(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return null

        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val paddedWidth = screenWidth + rowPadding / pixelStride
            val fullBitmap = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            fullBitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                fullBitmap
            } else {
                val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, screenWidth, screenHeight)
                fullBitmap.recycle()
                cropped
            }
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    fun stop() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    private fun readDisplayMetrics() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            screenWidth = bounds.width().coerceAtLeast(360)
            screenHeight = bounds.height().coerceAtLeast(640)
            screenDensity = context.resources.displayMetrics.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels.coerceAtLeast(360)
            screenHeight = metrics.heightPixels.coerceAtLeast(640)
            screenDensity = metrics.densityDpi
        }
    }
}
