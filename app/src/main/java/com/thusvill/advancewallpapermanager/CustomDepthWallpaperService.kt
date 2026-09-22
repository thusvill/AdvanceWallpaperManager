package com.thusvill.advancewallpapermanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class CustomDepthWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return DepthEngine()
    }

    inner class DepthEngine : Engine() {
        private var isVisible = false
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var currentConfig: WallpaperConfig? = null
        private lateinit var configManager: ConfigManager
        private lateinit var rotationManager: RotationManager
        
        private var baseBitmap: Bitmap? = null
        private var maskBitmap: Bitmap? = null
        private var timeBitmap: Bitmap? = null
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }

        private var lastTimeText: String? = null

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_TIME_TICK -> {
                        if (isVisible) render()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        // PRE-LOAD: When screen turns off, rotate and load assets into memory
                        // so they are ready the instant the user wakes the device.
                        if (rotationManager.getRotationMode() == RotationMode.ON_AWAKE) {
                            rotationManager.rotateNow() // Swaps active_config_id in Prefs
                            loadActiveConfig() // Decodes Bitmaps immediately while screen is off
                        }
                    }
                    "com.thusvill.advancewallpapermanager.UPDATE_CONFIG" -> {
                        loadActiveConfig()
                        if (isVisible) render()
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            configManager = ConfigManager(this@CustomDepthWallpaperService)
            rotationManager = RotationManager(this@CustomDepthWallpaperService)
            
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction("com.thusvill.advancewallpapermanager.UPDATE_CONFIG")
            }
            ContextCompat.registerReceiver(
                this@CustomDepthWallpaperService,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            loadActiveConfig()
        }

        override fun onDestroy() {
            super.onDestroy()
            try { unregisterReceiver(receiver) } catch (e: Exception) {}
            recycleBitmaps()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            if (visible) render()
        }

        private fun loadActiveConfig() {
            if (surfaceWidth == 0 || surfaceHeight == 0) return

            val activeId = configManager.getActiveConfigId() ?: return
            val config = configManager.loadBundleConfig(activeId) ?: return
            
            currentConfig = config
            
            // Decode new bitmaps before recycling old ones to avoid flickering if still visible
            val nextBase = configManager.loadBundleBitmap(activeId, "base")
            val nextMask = configManager.loadBundleBitmap(activeId, "mask")

            recycleBitmaps()

            baseBitmap = nextBase
            maskBitmap = nextMask

            if (config.isCustomFont) {
                val fontData = configManager.loadBundleFile(activeId, "font.ttf")
                fontData?.let {
                    val tempFile = configManager.getFontTempFile(activeId)
                    try {
                        FileOutputStream(tempFile).use { it.write(fontData) }
                    } catch (e: Exception) {
                        Log.e("DepthEngine", "Failed to write temp font", e)
                    }
                }
            }

            lastTimeText = null
        }

        private fun recycleBitmaps() {
            baseBitmap?.recycle(); baseBitmap = null
            maskBitmap?.recycle(); maskBitmap = null
            timeBitmap?.recycle(); timeBitmap = null
        }

        private fun render() {
            val surface = surfaceHolder.surface
            if (surface != null && surface.isValid) {
                val config = currentConfig ?: return
                if (baseBitmap == null) return
                
                updateTimeBitmap(config)
                
                NativeLib.renderNativeFrame(
                    surface, "", baseBitmap, maskBitmap, timeBitmap,
                    config.clockX, config.clockY, config.wallpaperScale,
                    config.wallpaperOffsetX, config.wallpaperOffsetY,
                    config.wallpaperRotation, config.clockRotation, config.clockDepth
                )
            }
        }

        private fun updateTimeBitmap(config: WallpaperConfig) {
            val format = if (config.use24HourFormat) "HH:mm" else "hh:mm"
            val currentTime = SimpleDateFormat(format, Locale.getDefault()).format(Date())

            if (currentTime == lastTimeText && timeBitmap != null) return
            lastTimeText = currentTime

            val baseSize = config.fontSize
            val stretch = config.clockHeightScale

            textPaint.textSize = baseSize
            textPaint.color = config.fontColor
            
            try {
                if (config.isCustomFont) {
                    val tempFile = configManager.getFontTempFile(config.id)
                    if (tempFile.exists()) {
                        textPaint.typeface = Typeface.createFromFile(tempFile)
                    } else {
                        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                } else if (config.fontFamily.isNotEmpty()) {
                    val fontFile = File("/system/fonts", "${config.fontFamily}.ttf")
                    textPaint.typeface = if (fontFile.exists()) Typeface.createFromFile(fontFile) else Typeface.create(config.fontFamily, Typeface.BOLD)
                } else textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } catch (e: Exception) {
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            textPaint.letterSpacing = config.letterSpacing
            if (config.fontThickness > 0) {
                textPaint.style = Paint.Style.FILL_AND_STROKE
                textPaint.strokeWidth = config.fontThickness
            } else textPaint.style = Paint.Style.FILL

            val fm = textPaint.fontMetrics
            val lineHeight = (fm.descent - fm.ascent)
            val padding = 60f

            if (config.clockMode == ClockMode.HORIZONTAL) {
                val totalW = textPaint.measureText(currentTime)
                val width = (totalW + padding).toInt()
                val height = (lineHeight * stretch + padding).toInt()

                if (timeBitmap == null || timeBitmap!!.width != width || timeBitmap!!.height != height) {
                    timeBitmap?.recycle()
                    timeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
                timeBitmap!!.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(timeBitmap!!)
                
                canvas.save()
                canvas.translate(width / 2f, height / 2f)
                canvas.scale(1.0f, stretch)
                canvas.drawText(currentTime, 0f, -(fm.ascent + fm.descent) / 2f, textPaint)
                canvas.restore()
            } else {
                val rawTokens = currentTime.split(" ")
                val finalLines = mutableListOf<String>()
                for (token in rawTokens) {
                    if (token.contains(":")) {
                        finalLines.add(token.substringBefore(":"))
                        finalLines.add(token.substringAfter(":"))
                    } else finalLines.add(token)
                }

                var maxWidth = 0f
                for (line in finalLines) maxWidth = maxOf(maxWidth, textPaint.measureText(line))
                
                val spacing = config.lineSpacing
                val stretchedLineHeight = lineHeight * stretch
                val totalHeight = (stretchedLineHeight * finalLines.size) + (spacing * (finalLines.size - 1))
                
                val width = (maxWidth + padding).toInt()
                val height = (Math.max(100f, totalHeight) + padding).toInt()

                if (timeBitmap == null || timeBitmap!!.width != width || timeBitmap!!.height != height) {
                    timeBitmap?.recycle()
                    timeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
                timeBitmap!!.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(timeBitmap!!)
                
                var currentY = padding / 2f + stretchedLineHeight / 2f
                for (i in finalLines.indices) {
                    canvas.save()
                    canvas.translate(width / 2f, currentY)
                    canvas.scale(1.0f, stretch)
                    canvas.drawText(finalLines[i], 0f, -(fm.ascent + fm.descent) / 2f, textPaint)
                    canvas.restore()
                    currentY += stretchedLineHeight + spacing
                }
            }
        }
    }
}
