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
        
        private var baseBitmap: Bitmap? = null
        private var maskBitmap: Bitmap? = null
        private var timeBitmap: Bitmap? = null
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }

        private var lastMinute: String = ""

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_TIME_TICK -> {
                        if (isVisible) render()
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
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
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
            recycleBitmaps()

            baseBitmap = configManager.loadBundleBitmap(activeId, "base")
            maskBitmap = configManager.loadBundleBitmap(activeId, "mask")
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
                    config.wallpaperOffsetX, config.wallpaperOffsetY
                )
            }
        }

        private fun updateTimeBitmap(config: WallpaperConfig) {
            val format = if (config.use24HourFormat) "HH:mm" else "hh:mm a"
            val currentTime = SimpleDateFormat(format, Locale.getDefault()).format(Date())

            val currentMinute = currentTime.substringAfter(":")

            if (currentMinute == lastMinute && timeBitmap != null) return

            lastMinute = currentMinute

            textPaint.textSize = config.fontSize
            textPaint.color = config.fontColor
            
            try {
                if (config.fontFamily.isNotEmpty()) {
                    val fontFile = File("/system/fonts", "${config.fontFamily}.ttf")
                    textPaint.typeface = if (fontFile.exists()) {
                        Typeface.createFromFile(fontFile)
                    } else {
                        Typeface.create(config.fontFamily, Typeface.BOLD)
                    }
                } else {
                    textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
            } catch (e: Exception) {
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            textPaint.letterSpacing = config.letterSpacing

            if (config.fontThickness > 0) {
                textPaint.style = Paint.Style.FILL_AND_STROKE
                textPaint.strokeWidth = config.fontThickness
            } else {
                textPaint.style = Paint.Style.FILL
            }

            val fm = textPaint.fontMetrics
            val lineHeight = (fm.descent - fm.ascent)
            
            if (config.clockMode == ClockMode.HORIZONTAL) {
                val colonIdx = currentTime.indexOf(":")
                val hourPart = currentTime.substring(0, colonIdx)
                val minutePart = currentTime.substring(colonIdx + 1)
                val hourW = textPaint.measureText(hourPart)
                val colonW = textPaint.measureText(":")
                val minuteW = textPaint.measureText(minutePart)
                val totalW = hourW + colonW + minuteW
                val totalH = lineHeight * config.clockHeightScale
                val padding = 60
                val width = (totalW + padding).toInt()
                val height = (totalH + padding).toInt()

                if (timeBitmap == null || timeBitmap!!.width != width || timeBitmap!!.height != height) {
                    timeBitmap?.recycle()
                    timeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
                timeBitmap?.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(timeBitmap!!)
                val startX = padding / 2f
                val centerY = height / 2f
                
                canvas.save()
                canvas.translate(startX, centerY)
                canvas.scale(1.0f, config.clockHeightScale)
                canvas.drawText(hourPart, hourW / 2f, -(fm.ascent + fm.descent) / 2f, textPaint)
                canvas.restore()

                canvas.drawText(":", startX + hourW + colonW / 2f, centerY - (fm.ascent + fm.descent) / 2f, textPaint)

                canvas.save()
                canvas.translate(startX + hourW + colonW, centerY)
                canvas.scale(1.0f, config.clockHeightScale)
                canvas.drawText(minutePart, minuteW / 2f, -(fm.ascent + fm.descent) / 2f, textPaint)
                canvas.restore()
            } else {
                val lines = currentTime.split(":")
                val spacing = (20f + config.lineSpacing) * config.clockHeightScale
                val stretchedLineHeight = lineHeight * config.clockHeightScale
                var maxWidth = 0f
                for (line in lines) maxWidth = maxOf(maxWidth, textPaint.measureText(line))
                val totalHeight = (stretchedLineHeight * lines.size) + spacing
                val padding = 60
                val width = (maxWidth + padding).toInt()
                val height = (totalHeight + padding).toInt()

                if (timeBitmap == null || timeBitmap!!.width != width || timeBitmap!!.height != height) {
                    timeBitmap?.recycle()
                    timeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
                timeBitmap?.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(timeBitmap!!)
                var currentY = padding / 2f
                for (i in lines.indices) {
                    canvas.save()
                    canvas.translate(width / 2f, currentY + stretchedLineHeight / 2f)
                    canvas.scale(1.0f, config.clockHeightScale)
                    canvas.drawText(lines[i], 0f, -(fm.ascent + fm.descent) / 2f, textPaint)
                    canvas.restore()
                    currentY += stretchedLineHeight + if (i == 0) spacing else 0f
                }
            }
        }
    }
}
