package com.thusvill.advancewallpapermanager

data class WallpaperConfig(
    val id: String = "default",
    var baseImagePath: String = "",
    var foregroundMaskPath: String = "",
    var clockX: Float = 0.5f,
    var clockY: Float = 0.4f,
    var fontSize: Float = 200f,
    var fontColor: Int = 0xFFFFFFFF.toInt(),
    var fontThickness: Float = 0f, // 0 means FILL, >0 means STROKE/BOLD
    var isEnabled: Boolean = true
)
