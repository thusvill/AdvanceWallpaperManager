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

import android.os.Parcel
import android.os.Parcelable

data class WallpaperConfig(
    var id: String = "default",
    var clockX: Float = 0.5f,
    var clockY: Float = 0.4f,
    var fontSize: Float = 200f,
    var fontColor: Int = 0xFFFFFFFF.toInt(),
    var fontThickness: Float = 0f,
    var clockHeightScale: Float = 1.0f,
    var clockMode: ClockMode = ClockMode.HORIZONTAL,
    var deepLabTargetClassIndex: Int = 15,
    var deepLabMinConfidence: Float = 0f,
    var saliencyThreshold: Float = 0.35f,
    var saliencyFeatherRadius: Int = 10,
    var saliencyCleanupRadius: Int = 2,
    var saliencyEdgeLock: Float = 0.5f,
    var mlKitFeatherRadius: Int = 10,
    var mlKitThreshold: Float = 0.5f,
    var mlKitExpansionPx: Int = 0,
    var clockDepth: Float = 1.0f,
    var accuracyLevel: Float = 1.0f,
    var isEnabled: Boolean = true,
    var fontFamily: String = "sans-serif-condensed",
    var letterSpacing: Float = 0f,
    var lineSpacing: Float = 0f,
    var use24HourFormat: Boolean = true,
    var showAmPm: Boolean = false,
    var isCustomFont: Boolean = false,
    var customFontName: String = "",
    var wallpaperScale: Float = 1.0f,
    var wallpaperOffsetX: Float = 0f,
    var wallpaperOffsetY: Float = 0f,
    var wallpaperRotation: Float = 0f,
    var clockRotation: Float = 0f
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "default",
        parcel.readFloat(),
        parcel.readFloat(),
        parcel.readFloat(),
        parcel.readInt(),
        parcel.readFloat(),
        parcel.readFloat(),
        ClockMode.valueOf(parcel.readString() ?: ClockMode.HORIZONTAL.name),
        parcel.readInt(),
        parcel.readFloat(),
        parcel.readFloat(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readFloat(),
        parcel.readInt(),
        try { parcel.readFloat() } catch (_: Exception) { 0.5f },
        try { parcel.readInt() } catch (_: Exception) { 0 },
        try { parcel.readFloat() } catch (_: Exception) { 1.0f },
        parcel.readFloat(),
        parcel.readByte() != 0.toByte(),
        parcel.readString() ?: "sans-serif-condensed",
        parcel.readFloat(),
        parcel.readFloat(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readString() ?: "",
        parcel.readFloat(),
        parcel.readFloat(),
        parcel.readFloat(),
        try { parcel.readFloat() } catch (_: Exception) { 0f },
        try { parcel.readFloat() } catch (_: Exception) { 0f }
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeFloat(clockX)
        parcel.writeFloat(clockY)
        parcel.writeFloat(fontSize)
        parcel.writeInt(fontColor)
        parcel.writeFloat(fontThickness)
        parcel.writeFloat(clockHeightScale)
        parcel.writeString(clockMode.name)
        parcel.writeInt(deepLabTargetClassIndex)
        parcel.writeFloat(deepLabMinConfidence)
        parcel.writeFloat(saliencyThreshold)
        parcel.writeInt(saliencyFeatherRadius)
        parcel.writeInt(saliencyCleanupRadius)
        parcel.writeFloat(saliencyEdgeLock)
        parcel.writeInt(mlKitFeatherRadius)
        parcel.writeFloat(mlKitThreshold)
        parcel.writeInt(mlKitExpansionPx)
        parcel.writeFloat(clockDepth)
        parcel.writeFloat(accuracyLevel)
        parcel.writeByte(if (isEnabled) 1 else 0)
        parcel.writeString(fontFamily)
        parcel.writeFloat(letterSpacing)
        parcel.writeFloat(lineSpacing)
        parcel.writeByte(if (use24HourFormat) 1 else 0)
        parcel.writeByte(if (showAmPm) 1 else 0)
        parcel.writeByte(if (isCustomFont) 1 else 0)
        parcel.writeString(customFontName)
        parcel.writeFloat(wallpaperScale)
        parcel.writeFloat(wallpaperOffsetX)
        parcel.writeFloat(wallpaperOffsetY)
        parcel.writeFloat(wallpaperRotation)
        parcel.writeFloat(clockRotation)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<WallpaperConfig> {
        override fun createFromParcel(parcel: Parcel): WallpaperConfig = WallpaperConfig(parcel)
        override fun newArray(size: Int): Array<WallpaperConfig?> = arrayOfNulls(size)
    }
}

enum class ClockMode {
    HORIZONTAL,
    VERTICAL
}
