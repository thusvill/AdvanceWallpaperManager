package com.thusvill.advancewallpapermanager

import android.graphics.Bitmap
import android.view.Surface
import java.nio.ByteBuffer

object NativeLib {
    init {
        System.loadLibrary("advancewallpapermanager")
    }

    external fun renderNativeFrame(
        surface: Surface,
        timeText: String,
        baseBitmap: Bitmap?,
        maskBitmap: Bitmap?,
        timeBitmap: Bitmap?,
        clockX: Float,
        clockY: Float,
        wallpaperScale: Float,
        offsetX: Float,
        offsetY: Float,
        wallpaperRotation: Float = 0f,
        clockRotation: Float = 0f,
        clockDepth: Float = 1.0f
    )

    external fun extractMlKitSubjectNative(
        originalBitmap: Bitmap,
        maskBuffer: ByteBuffer,
        maskW: Int,
        maskH: Int,
        outputBitmap: Bitmap,
        threshold: Float,
        featherRadius: Int,
        expansionPx: Int
    ): Boolean

    external fun extractMaskNative(
        originalBitmap: Bitmap,
        maskBuffer: ByteBuffer,
        maskW: Int,
        maskH: Int,
        outputBitmap: Bitmap
    ): Boolean

    external fun extractEdgesNative(
        originalBitmap: Bitmap,
        outputBitmap: Bitmap,
        threshold: Float
    ): Boolean

    external fun extractHybridMaskNative(
        originalBitmap: Bitmap,
        maskBuffer: ByteBuffer,
        maskW: Int,
        maskH: Int,
        outputBitmap: Bitmap,
        confidenceThreshold: Float,
        edgeThreshold: Float,
        featherRadius: Int
    ): Boolean

    external fun extractSaliencyMatteNative(
        originalBitmap: Bitmap,
        maskBuffer: ByteBuffer,
        maskW: Int,
        maskH: Int,
        outputBitmap: Bitmap,
        matteThreshold: Float,
        featherRadius: Int,
        cleanupRadius: Int,
        edgeLock: Float
    ): Boolean
}
