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
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
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
                clockHeightScale = prefs.getFloat("clockHeightScale", 1.0f)
            )
            
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
                val timeText = timeFormat.format(Date())
                
                updateTimeBitmap(timeText, config)
                
                renderNativeFrame(
                    surface, 
                    timeText, 
                    baseBitmap, 
                    maskBitmap, 
                    timeBitmap,
                    config.clockX, 
                    config.clockY
                )
            }
        }

//        private fun updateTimeBitmap(text: String, config: WallpaperConfig) {
//            textPaint.textSize = config.fontSize
//            textPaint.color = config.fontColor
//            textPaint.typeface = Typeface.DEFAULT_BOLD
//
//            if (config.fontThickness > 0) {
//                textPaint.style = Paint.Style.FILL_AND_STROKE
//                textPaint.strokeWidth = config.fontThickness
//            } else {
//                textPaint.style = Paint.Style.FILL
//            }
//
//            val bounds = Rect()
//            textPaint.getTextBounds(text, 0, text.length, bounds)
//
//            val width = bounds.width() + 40
//            val height = (bounds.height() * config.clockHeightScale + 40).toInt()
//
//            if (timeBitmap == null || timeBitmap!!.width != width || timeBitmap!!.height != height) {
//                timeBitmap?.recycle()
//                timeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
//            }
//
//            timeBitmap?.eraseColor(Color.TRANSPARENT)
//            val canvas = Canvas(timeBitmap!!)
//
//            // Apply vertical stretch
//            canvas.save()
//            canvas.scale(1.0f, config.clockHeightScale, width / 2f, height / 2f)
//            canvas.drawText(text, width / 2f, height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
//            canvas.restore()
//        }
private fun updateTimeBitmap(text: String, config: WallpaperConfig) {

    val scaledTextSize = config.fontSize * config.clockHeightScale
    textPaint.textSize = scaledTextSize


    textPaint.color = config.fontColor
    textPaint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    if (config.fontThickness > 0) {
        textPaint.style = Paint.Style.FILL_AND_STROKE
        textPaint.strokeWidth = config.fontThickness
    } else {
        textPaint.style = Paint.Style.FILL
    }

    textPaint.textScaleX = 0.85f


    val bounds = Rect()
    textPaint.getTextBounds(text, 0, text.length, bounds)

    val padding = 40
    val width = bounds.width() + padding
    val height = bounds.height() + padding


    if (timeBitmap == null || timeBitmap!!.width != width || timeBitmap!!.height != height) {
        timeBitmap?.recycle()
        timeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    timeBitmap?.eraseColor(Color.TRANSPARENT)
    val canvas = Canvas(timeBitmap!!)

    val x = width / 2f
    val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f

    canvas.drawText(text, x, y, textPaint)
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
