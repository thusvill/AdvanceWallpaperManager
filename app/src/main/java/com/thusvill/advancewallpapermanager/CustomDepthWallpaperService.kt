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
        
        private var baseBitmap: Bitmap? = null
        private var maskBitmap: Bitmap? = null
        private var timeBitmap: Bitmap? = null
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isLinearText = false

        }

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_TIME_TICK -> {
                        if (isVisible) render()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        loadConfigFromPrefs()
                        if (isVisible) render()
                    }
                    "com.thusvill.advancewallpapermanager.UPDATE_CONFIG" -> {
                        loadConfigFromPrefs()
                        if (isVisible) render()
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
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
            loadConfigFromPrefs()
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

        private fun loadConfigFromPrefs() {
            if (surfaceWidth == 0 || surfaceHeight == 0) return

            val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            val path = prefs.getString("basePath", "") ?: ""
            Log.d("DepthEngine", "Loading from: $path")

            val file = File(path)
            if (!file.exists()) {
                Log.e("DepthEngine", "File does not exist at $path!")
                return
            }
            val config = WallpaperConfig(
                baseImagePath = prefs.getString("basePath", "") ?: "",
                foregroundMaskPath = prefs.getString("maskPath", "") ?: "",
                clockX = prefs.getFloat("clockX", 0.5f),
                clockY = prefs.getFloat("clockY", 0.4f),
                fontSize = prefs.getFloat("fontSize", 200f),
                fontThickness = prefs.getFloat("fontThickness", 0f),
                clockHeightScale = prefs.getFloat("clockHeightScale", 1.0f),
                clockMode = ClockMode.valueOf(prefs.getString("clockMode", ClockMode.HORIZONTAL.name) ?: ClockMode.HORIZONTAL.name),
                fontFamily = prefs.getString("fontFamily", "sans-serif-condensed") ?: "sans-serif-condensed",
                letterSpacing = prefs.getFloat("letterSpacing", 0f),
                lineSpacing = prefs.getFloat("lineSpacing", 0f),
                use24HourFormat = prefs.getBoolean("use24HourFormat", true)
            )
            config.fontColor = prefs.getInt("fontColor", Color.WHITE)
            
            currentConfig = config
            recycleBitmaps()

            baseBitmap = loadAndScaleBitmap(config.baseImagePath)
            maskBitmap = loadAndScaleBitmap(config.foregroundMaskPath)
        }

        private fun recycleBitmaps() {
            baseBitmap?.recycle(); baseBitmap = null
            maskBitmap?.recycle(); maskBitmap = null
            timeBitmap?.recycle(); timeBitmap = null
        }

        private fun loadAndScaleBitmap(path: String): Bitmap? {
            val file = File(path)
            if (!file.exists()) return null
            
            return try {
                val original = BitmapFactory.decodeFile(path) ?: return null
                
                val scale: Float
                var dx = 0f
                var dy = 0f
                if (original.width * surfaceHeight > surfaceWidth * original.height) {
                    scale = surfaceHeight.toFloat() / original.height.toFloat()
                    dx = (surfaceWidth - original.width * scale) * 0.5f
                } else {
                    scale = surfaceWidth.toFloat() / original.width.toFloat()
                    dy = (surfaceHeight - original.height * scale) * 0.5f
                }

                val matrix = Matrix()
                matrix.setScale(scale, scale)
                matrix.postTranslate(dx, dy)

                val scaled = Bitmap.createBitmap(surfaceWidth, surfaceHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(scaled)
                canvas.drawBitmap(original, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                
                original.recycle()
                scaled
            } catch (e: Exception) { null }
        }

        private fun render() {
            val surface = surfaceHolder.surface
            if (surface != null && surface.isValid) {
                val config = currentConfig ?: return

                if (baseBitmap == null) {
                    Log.e("DepthEngine", "Base bitmap is null, cannot render.")
                    return
                }
                
                updateTimeBitmap(config)
                
                renderNativeFrame(
                    surface, 
                    "", 
                    baseBitmap, 
                    maskBitmap, 
                    timeBitmap,
                    config.clockX, 
                    config.clockY
                )
            }
        }

        private fun updateTimeBitmap(config: WallpaperConfig) {
            val format = if (config.use24HourFormat) "HH:mm" else "h:mm"
            val currentTime = SimpleDateFormat(format, Locale.getDefault()).format(Date())

            val scaledTextSize = config.fontSize
            textPaint.textSize = scaledTextSize
            textPaint.color = config.fontColor
            textPaint.typeface = Typeface.create(config.fontFamily, Typeface.BOLD)
            textPaint.letterSpacing = config.letterSpacing
            textPaint.isAntiAlias = true
            textPaint.isSubpixelText = true

            if (config.fontThickness > 0) {
                textPaint.style = Paint.Style.FILL_AND_STROKE
                textPaint.strokeWidth = config.fontThickness
            } else {
                textPaint.style = Paint.Style.FILL
            }

            textPaint.textScaleX = 0.85f

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
                
                // Draw hour (stretched)
                canvas.save()
                canvas.translate(startX, centerY)
                canvas.scale(1.0f, config.clockHeightScale)
                canvas.drawText(hourPart, hourW / 2f, -(fm.ascent + fm.descent) / 2f, textPaint)
                canvas.restore()

                // Draw colon (not stretched)
                canvas.drawText(":", startX + hourW + colonW / 2f, centerY - (fm.ascent + fm.descent) / 2f, textPaint)

                // Draw minute (stretched)
                canvas.save()
                canvas.translate(startX + hourW + colonW, centerY)
                canvas.scale(1.0f, config.clockHeightScale)
                canvas.drawText(minutePart, minuteW / 2f, -(fm.ascent + fm.descent) / 2f, textPaint)
                canvas.restore()

            } else {
                // VERTICAL MODE
                val finalLines = currentTime.split(":")
                val baseSpacing = 20f
                val extraSpacing = config.lineSpacing
                val spacing = if (finalLines.size > 1) (baseSpacing + extraSpacing) * config.clockHeightScale else 0f
                
                val stretchedLineHeight = lineHeight * config.clockHeightScale
                var maxWidth = 0f
                for (line in finalLines) {
                    maxWidth = maxOf(maxWidth, textPaint.measureText(line))
                }

                val totalHeight = (stretchedLineHeight * finalLines.size) + spacing
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
                for (i in finalLines.indices) {
                    val line = finalLines[i]
                    canvas.save()
                    canvas.translate(width / 2f, currentY + stretchedLineHeight / 2f)
                    canvas.scale(1.0f, config.clockHeightScale)
                    canvas.drawText(line, 0f, -(fm.ascent + fm.descent) / 2f, textPaint)
                    canvas.restore()
                    
                    currentY += stretchedLineHeight
                    if (finalLines.size > 1 && i == 0) {
                        currentY += spacing
                    }
                }
            }
        }
    }

    private external fun renderNativeFrame(
        surface: Surface,
        timeText: String,
        baseBitmap: Bitmap?,
        maskBitmap: Bitmap?,
        timeBitmap: Bitmap?,
        clockX: Float,
        clockY: Float
    )

    companion object {
        init {
            System.loadLibrary("advancewallpapermanager")
        }
    }
}
