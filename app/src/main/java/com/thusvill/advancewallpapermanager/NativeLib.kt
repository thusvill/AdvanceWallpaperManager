/*
 * Copyright (C) 2026 Advance Wallpaper Manager
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
