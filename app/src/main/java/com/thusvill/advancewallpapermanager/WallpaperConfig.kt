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
    var clockHeightScale: Float = 1.0f, // iOS-style vertical stretch
    var deepLabTargetClassIndex: Int = 15,
    var deepLabMinConfidence: Float = 0f,
    var saliencyThreshold: Float = 0.35f,
    var saliencyFeatherRadius: Int = 10,
    var saliencyCleanupRadius: Int = 2,
    var saliencyEdgeLock: Float = 0.5f,
    var accuracyLevel: Float = 1.0f, // 0.5 = low accuracy (faster), 1.0 = balanced, 1.5 = high accuracy (slower)
    var isEnabled: Boolean = true
)
