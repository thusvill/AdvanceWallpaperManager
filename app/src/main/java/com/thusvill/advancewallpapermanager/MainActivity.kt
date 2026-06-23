package com.thusvill.advancewallpapermanager

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.thusvill.advancewallpapermanager.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE) }
    
    private var currentConfig = WallpaperConfig()
    private var activeExtractionMode: ExtractionMode = ExtractionMode.SELFIE_AI
    private var selfieSegmenter: TfliteSelfieSegmenter? = null
    private var deepLabSegmenter: TfliteSelfieSegmenter? = null
    private var mlKitSegmenter: TfliteSelfieSegmenter? = null
    private var maskUpdateJob: Job? = null
    private var isApplyingAccuracyMultiplier: Boolean = false  // Flag to prevent recursive updates

    // Caching AI results to avoid re-running slow inference when only parameters change
    private var cachedAiMask: TfliteSelfieSegmenter.SegmentationMask? = null
    private var cachedAiMode: ExtractionMode? = null
    private var cachedSourceUri: Uri? = null
    private var lastSelectedUri: Uri? = null

    private var previewBaseBitmap: Bitmap? = null
    private var previewMaskBitmap: Bitmap? = null
    private var previewTimeBitmap: Bitmap? = null
    private val previewTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    /**
     * Native JNI call to handle heavy-duty pixel masking
     */
    private external fun extractMaskNative(
        original: Bitmap,
        maskBuffer: ByteBuffer,
        maskWidth: Int,
        maskHeight: Int,
        output: Bitmap
    ): Boolean

    /**
     * Native JNI call for mathematical edge detection
     */
    private external fun extractEdgesNative(
        original: Bitmap,
        output: Bitmap,
        threshold: Float
    ): Boolean

    private external fun extractHybridMaskNative(
        original: Bitmap,
        maskBuffer: ByteBuffer,
        maskWidth: Int,
        maskHeight: Int,
        output: Bitmap,
        confidenceThreshold: Float,
        edgeThreshold: Float,
        featherRadius: Int
    ): Boolean

    private external fun extractSaliencyMatteNative(
        original: Bitmap,
        maskBuffer: ByteBuffer,
        maskWidth: Int,
        maskHeight: Int,
        output: Bitmap,
        threshold: Float,
        featherRadius: Int,
        cleanupRadius: Int,
        edgeLock: Float
    ): Boolean

    /**
     * Native JNI call to render a frame to a surface (reuse logic from service)
     */
    private external fun renderNativeFrame(
        surface: Surface,
        timeText: String,
        baseBitmap: Bitmap?,
        maskBitmap: Bitmap?,
        timeBitmap: Bitmap?,
        clockX: Float,
        clockY: Float
    )

    private val selectImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            processSelectedImage(it, activeExtractionMode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        System.loadLibrary("advancewallpapermanager")

        loadConfig()
        initUi()
        initPreview()

        binding.btnInitialSelectImage.setOnClickListener {
            selectImageLauncher.launch("image/*")
        }
        binding.btnSelectImage.setOnClickListener {
            selectImageLauncher.launch("image/*")
        }

        binding.btnApply.setOnClickListener {
            saveConfig()
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, CustomDepthWallpaperService::class.java)
            )
            startActivity(intent)
            sendBroadcast(Intent("com.thusvill.advancewallpapermanager.UPDATE_CONFIG"))
        }
        updateEditorVisibility()
    }

    private fun initPreview() {
        syncPreviewAspectRatio()
        binding.previewContainer.setOnTouchListener { _, event ->
            handleClockTouch(event)
        }
        binding.clockDragHint.isClickable = false
        binding.surfacePreview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                holder.setSizeFromLayout()
                updatePreviewBitmaps()
                requestRender()
                updateClockDragHandle()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                holder.setSizeFromLayout()
                updatePreviewBitmaps()
                requestRender()
                updateClockDragHandle()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                previewBaseBitmap?.recycle()
                previewMaskBitmap?.recycle()
                previewTimeBitmap?.recycle()
                previewBaseBitmap = null
                previewMaskBitmap = null
                previewTimeBitmap = null
            }
        })
    }

    private fun syncPreviewAspectRatio() {
        binding.previewContainer.post {
            val metrics = wallpaperTargetMetrics()
            val availableWidth = binding.rootLayout.width - binding.rootLayout.paddingStart - binding.rootLayout.paddingEnd
            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0 || availableWidth <= 0) {
                return@post
            }

            val maxHeight = (metrics.heightPixels * PREVIEW_SCREEN_HEIGHT_FRACTION).toInt()
            var targetWidth = (maxHeight * metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()).toInt()
            var targetHeight = maxHeight

            if (targetWidth > availableWidth) {
                targetWidth = availableWidth
                targetHeight = (targetWidth * metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat()).toInt()
            }

            val cardParams = binding.previewCard.layoutParams
            if (cardParams.width != targetWidth) {
                cardParams.width = targetWidth
                binding.previewCard.layoutParams = cardParams
            }

            val previewParams = binding.previewContainer.layoutParams
            if (previewParams.height != targetHeight) {
                previewParams.height = targetHeight
                binding.previewContainer.layoutParams = previewParams
            }
            binding.previewContainer.post {
                updatePreviewBitmaps()
                requestRender()
                updateClockDragHandle()
            }
        }
    }

    private fun updatePreviewBitmaps() {
        val w = binding.surfacePreview.width
        val h = binding.surfacePreview.height
        if (w <= 0 || h <= 0) return

        previewBaseBitmap?.recycle()
        previewMaskBitmap?.recycle()

        previewBaseBitmap = loadAndScalePreview(currentConfig.baseImagePath, w, h)
        previewMaskBitmap = loadAndScalePreview(currentConfig.foregroundMaskPath, w, h)
    }

    private fun loadAndScalePreview(path: String, targetW: Int, targetH: Int): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        
        return try {
            val original = BitmapFactory.decodeFile(path) ?: return null
            
            val scale: Float
            var dx = 0f
            var dy = 0f
            if (original.width * targetH > targetW * original.height) {
                scale = targetH.toFloat() / original.height.toFloat()
                dx = (targetW - original.width * scale) * 0.5f
            } else {
                scale = targetW.toFloat() / original.width.toFloat()
                dy = (targetH - original.height * scale) * 0.5f
            }

            val matrix = android.graphics.Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)

            val scaled = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(scaled)
            canvas.drawBitmap(original, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            
            original.recycle()
            scaled
        } catch (e: Exception) { null }
    }

    private fun requestRender() {
        val surface = binding.surfacePreview.holder.surface
        if (surface == null || !surface.isValid) return
        
        updateTimeBitmap()
        
        renderNativeFrame(
            surface,
            "", // Not used anymore
            previewBaseBitmap,
            previewMaskBitmap,
            previewTimeBitmap,
            currentConfig.clockX,
            currentConfig.clockY
        )
        updateClockDragHandle()
    }

    private fun handleClockTouch(event: MotionEvent): Boolean {
        val width = binding.previewContainer.width.toFloat()
        val height = binding.previewContainer.height.toFloat()
        if (width <= 0f || height <= 0f) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragOffsetX = event.x - currentConfig.clockX * width
                dragOffsetY = event.y - currentConfig.clockY * height
                binding.clockDragHint.alpha = 1f
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                currentConfig.clockX = ((event.x - dragOffsetX) / width).coerceIn(0.05f, 0.95f)
                currentConfig.clockY = ((event.y - dragOffsetY) / height).coerceIn(0.08f, 0.92f)
                updateClockDragHandle()
                requestRender()
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    binding.clockDragHint.alpha = 0.78f
                    saveConfig()
                    notifyService()
                }
                return true
            }
        }
        return false
    }

    private fun updateClockDragHandle() {
        binding.clockDragHint.post {
            val parentWidth = binding.previewContainer.width
            val parentHeight = binding.previewContainer.height
            val handleWidth = binding.clockDragHint.width
            val handleHeight = binding.clockDragHint.height
            if (parentWidth <= 0 || parentHeight <= 0 || handleWidth <= 0 || handleHeight <= 0) {
                return@post
            }

            binding.clockDragHint.x = currentConfig.clockX * parentWidth - handleWidth / 2f
            binding.clockDragHint.y = currentConfig.clockY * parentHeight - handleHeight / 2f
            binding.clockDragHint.alpha = 0.78f
            binding.clockDragHint.bringToFront()
        }
    }

    private fun updateTimeBitmap() {
        val format = if (currentConfig.use24HourFormat) "HH:mm" else "h:mm"
        val currentTime = SimpleDateFormat(format, Locale.getDefault()).format(Date())
        
        val scale = previewScaleToWallpaper()
        val scaledTextSize = currentConfig.fontSize * scale
        previewTextPaint.textSize = scaledTextSize
        previewTextPaint.color = currentConfig.fontColor
        previewTextPaint.typeface = Typeface.create(currentConfig.fontFamily, Typeface.BOLD)
        previewTextPaint.letterSpacing = currentConfig.letterSpacing
        previewTextPaint.isAntiAlias = true
        previewTextPaint.isSubpixelText = true

        if (currentConfig.fontThickness > 0) {
            previewTextPaint.style = Paint.Style.FILL_AND_STROKE
            previewTextPaint.strokeWidth = currentConfig.fontThickness * scale
        } else {
            previewTextPaint.style = Paint.Style.FILL
        }

        previewTextPaint.textScaleX = 0.85f

        val fm = previewTextPaint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent)

        if (currentConfig.clockMode == ClockMode.HORIZONTAL) {
            val colonIdx = currentTime.indexOf(":")
            val hourPart = currentTime.substring(0, colonIdx)
            val minutePart = currentTime.substring(colonIdx + 1)

            val hourW = previewTextPaint.measureText(hourPart)
            val colonW = previewTextPaint.measureText(":")
            val minuteW = previewTextPaint.measureText(minutePart)

            val totalW = hourW + colonW + minuteW
            val totalH = lineHeight * currentConfig.clockHeightScale

            val padding = (30f * scale).toInt().coerceAtLeast(8)
            val width = (totalW + padding).toInt()
            val height = (totalH + padding).toInt()

            if (previewTimeBitmap == null || previewTimeBitmap!!.width != width || previewTimeBitmap!!.height != height) {
                previewTimeBitmap?.recycle()
                previewTimeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }

            previewTimeBitmap?.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(previewTimeBitmap!!)

            val startX = padding / 2f
            val centerY = height / 2f
            
            // Draw hour (stretched)
            canvas.save()
            canvas.translate(startX, centerY)
            canvas.scale(1.0f, currentConfig.clockHeightScale)
            canvas.drawText(hourPart, hourW / 2f, -(fm.ascent + fm.descent) / 2f, previewTextPaint)
            canvas.restore()

            // Draw colon (not stretched)
            canvas.drawText(":", startX + hourW + colonW / 2f, centerY - (fm.ascent + fm.descent) / 2f, previewTextPaint)

            // Draw minute (stretched)
            canvas.save()
            canvas.translate(startX + hourW + colonW, centerY)
            canvas.scale(1.0f, currentConfig.clockHeightScale)
            canvas.drawText(minutePart, minuteW / 2f, -(fm.ascent + fm.descent) / 2f, previewTextPaint)
            canvas.restore()

        } else {
            // VERTICAL MODE
            val finalLines = currentTime.split(":")
            val baseSpacing = 20f * scale
            val extraSpacing = currentConfig.lineSpacing * scale
            val spacing = if (finalLines.size > 1) (baseSpacing + extraSpacing) * currentConfig.clockHeightScale else 0f
            
            val stretchedLineHeight = lineHeight * currentConfig.clockHeightScale
            var maxWidth = 0f
            for (line in finalLines) {
                maxWidth = maxOf(maxWidth, previewTextPaint.measureText(line))
            }

            val totalHeight = (stretchedLineHeight * finalLines.size) + spacing
            val padding = (30f * scale).toInt().coerceAtLeast(8)
            val width = (maxWidth + padding).toInt()
            val height = (totalHeight + padding).toInt()

            if (previewTimeBitmap == null || previewTimeBitmap!!.width != width || previewTimeBitmap!!.height != height) {
                previewTimeBitmap?.recycle()
                previewTimeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }

            previewTimeBitmap?.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(previewTimeBitmap!!)

            var currentY = padding / 2f
            for (i in finalLines.indices) {
                val line = finalLines[i]
                canvas.save()
                canvas.translate(width / 2f, currentY + stretchedLineHeight / 2f)
                canvas.scale(1.0f, currentConfig.clockHeightScale)
                canvas.drawText(line, 0f, -(fm.ascent + fm.descent) / 2f, previewTextPaint)
                canvas.restore()
                
                currentY += stretchedLineHeight
                if (finalLines.size > 1 && i == 0) {
                    currentY += spacing
                }
            }
        }
    }

    private fun previewScaleToWallpaper(): Float {
        val metrics = wallpaperTargetMetrics()
        val previewW = binding.surfacePreview.width.takeIf { it > 0 } ?: binding.previewContainer.width
        val previewH = binding.surfacePreview.height.takeIf { it > 0 } ?: binding.previewContainer.height
        if (previewW <= 0 || previewH <= 0 || metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            return 0.5f
        }
        return min(
            previewW.toFloat() / metrics.widthPixels.toFloat(),
            previewH.toFloat() / metrics.heightPixels.toFloat()
        )
    }

    private fun wallpaperTargetMetrics(): DisplayMetrics {
        return DisplayMetrics().also { metrics ->
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
    }

    private fun initUi() {
        val pipelineAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            ExtractionMode.values().map { it.displayName }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerPipeline.adapter = pipelineAdapter
        binding.spinnerPipeline.setSelection(activeExtractionMode.ordinal)
        binding.spinnerPipeline.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                setActiveExtractionMode(ExtractionMode.values()[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.sliderThickness.value = currentConfig.fontThickness
        binding.sliderThreshold.value = 0.1f
        binding.sliderDeepLabClass.value = currentConfig.deepLabTargetClassIndex.toFloat()
        binding.sliderDeepLabConfidence.value = currentConfig.deepLabMinConfidence
        updateDeepLabSliderLabels()
        binding.sliderAccuracy.value = currentConfig.accuracyLevel
        updateAccuracySliderLabel()
        binding.sliderSaliencyThreshold.value = currentConfig.saliencyThreshold
        binding.sliderSaliencyFeather.value = currentConfig.saliencyFeatherRadius.toFloat()
        binding.sliderSaliencyCleanup.value = currentConfig.saliencyCleanupRadius.toFloat()
        binding.sliderSaliencyEdgeLock.value = currentConfig.saliencyEdgeLock
        updateSaliencyMatteSliderLabels()

        binding.sliderMlkitFeather.value = currentConfig.mlKitFeatherRadius.toFloat()
        updateMlKitSliderLabels()

        binding.sliderStretch.value = currentConfig.clockHeightScale
        updateStretchSliderLabel()

        val fonts = listOf("sans-serif", "sans-serif-condensed", "sans-serif-light", "sans-serif-medium", "serif", "monospace", "cursive")
        val fontAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fonts).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerFont.adapter = fontAdapter
        binding.spinnerFont.setSelection(fonts.indexOf(currentConfig.fontFamily).coerceAtLeast(0))
        binding.spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentConfig.fontFamily = fonts[position]
                saveConfig()
                notifyService()
                requestRender()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.sliderLetterSpacing.value = currentConfig.letterSpacing
        binding.tvLabelLetterSpacing.text = "Letter Spacing: ${String.format("%.2f", currentConfig.letterSpacing)}"
        binding.sliderLetterSpacing.addOnChangeListener { _, value, _ ->
            currentConfig.letterSpacing = value
            binding.tvLabelLetterSpacing.text = "Letter Spacing: ${String.format("%.2f", value)}"
            saveConfig()
            notifyService()
            requestRender()
        }

        binding.sliderLineSpacing.value = currentConfig.lineSpacing
        binding.tvLabelLineSpacing.text = "Line Spacing: ${currentConfig.lineSpacing.toInt()}px"
        binding.sliderLineSpacing.addOnChangeListener { _, value, _ ->
            currentConfig.lineSpacing = value
            binding.tvLabelLineSpacing.text = "Line Spacing: ${value.toInt()}px"
            saveConfig()
            notifyService()
            requestRender()
        }

        if (currentConfig.clockMode == ClockMode.VERTICAL) {
            binding.toggleClockMode.check(R.id.btn_mode_vertical)
        } else {
            binding.toggleClockMode.check(R.id.btn_mode_horizontal)
        }

        if (currentConfig.use24HourFormat) {
            binding.toggleTimeFormat.check(R.id.btn_format_24h)
        } else {
            binding.toggleTimeFormat.check(R.id.btn_format_12h)
        }

        initColorPalette()

        setActiveExtractionMode(activeExtractionMode)

        binding.toggleClockMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentConfig.clockMode = if (checkedId == R.id.btn_mode_vertical) ClockMode.VERTICAL else ClockMode.HORIZONTAL
                saveConfig()
                notifyService()
                requestRender()
            }
        }

        binding.toggleTimeFormat.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentConfig.use24HourFormat = checkedId == R.id.btn_format_24h
                saveConfig()
                notifyService()
                requestRender()
            }
        }

        binding.btnAutoColor.setOnClickListener {
            autoDetectColor()
        }

        binding.btnClockSmaller.setOnClickListener {
            resizeClock(1f / CLOCK_RESIZE_STEP)
        }
        binding.btnClockLarger.setOnClickListener {
            resizeClock(CLOCK_RESIZE_STEP)
        }
        binding.sliderThickness.addOnChangeListener { _, value, _ -> currentConfig.fontThickness = value; saveConfig(); notifyService(); requestRender() }
        binding.sliderDeepLabClass.addOnChangeListener { _, value, _ ->
            currentConfig.deepLabTargetClassIndex = value.toInt()
            updateDeepLabSliderLabels()
            saveConfig()
            scheduleMaskRecalculation()
        }
        binding.sliderDeepLabConfidence.addOnChangeListener { _, value, _ ->
            currentConfig.deepLabMinConfidence = value
            updateDeepLabSliderLabels()
            saveConfig()
            scheduleMaskRecalculation()
        }
        binding.sliderSaliencyThreshold.addOnChangeListener { _, value, _ ->
            if (!isApplyingAccuracyMultiplier) {
                currentConfig.saliencyThreshold = value
                updateSaliencyMatteSliderLabels()
                saveConfig()
                scheduleMaskRecalculation()
            }
        }
        binding.sliderSaliencyFeather.addOnChangeListener { _, value, _ ->
            if (!isApplyingAccuracyMultiplier) {
                currentConfig.saliencyFeatherRadius = value.toInt()
                updateSaliencyMatteSliderLabels()
                saveConfig()
                scheduleMaskRecalculation()
            }
        }
        binding.sliderSaliencyCleanup.addOnChangeListener { _, value, _ ->
            if (!isApplyingAccuracyMultiplier) {
                currentConfig.saliencyCleanupRadius = value.toInt()
                updateSaliencyMatteSliderLabels()
                saveConfig()
                scheduleMaskRecalculation()
            }
        }
        binding.sliderSaliencyEdgeLock.addOnChangeListener { _, value, _ ->
            if (!isApplyingAccuracyMultiplier) {
                currentConfig.saliencyEdgeLock = value
                updateSaliencyMatteSliderLabels()
                saveConfig()
                scheduleMaskRecalculation()
            }
        }

        binding.sliderMlkitFeather.addOnChangeListener { _, value, _ ->
            currentConfig.mlKitFeatherRadius = value.toInt()
            updateMlKitSliderLabels()
            saveConfig()
            scheduleMaskRecalculation()
        }

        binding.sliderStretch.addOnChangeListener { _, value, _ ->
            currentConfig.clockHeightScale = value
            updateStretchSliderLabel()
            saveConfig()
            notifyService()
            requestRender()
        }
        
        binding.sliderStretch.addOnChangeListener { _, value, _ ->
            currentConfig.clockHeightScale = value
            updateStretchSliderLabel()
            saveConfig()
            notifyService()
            requestRender()
        }
        
        binding.sliderThreshold.addOnChangeListener { _, value, _ ->
            // Real-time Math Edge Update
            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    val bitmap = BitmapFactory.decodeFile(currentConfig.baseImagePath, options) ?: return@launch
                    val maskBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                    
                    if (extractEdgesNative(bitmap, maskBitmap, value)) {
                        val maskFile = File(getExternalFilesDir(null), "mask.png")
                        FileOutputStream(maskFile).use { maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        currentConfig.foregroundMaskPath = maskFile.absolutePath
                        
                        withContext(Dispatchers.Main) {
                            updatePreviewBitmaps()
                            requestRender()
                        }
                    }
                    maskBitmap.recycle()
                    bitmap.recycle()
                } catch (e: Exception) {}
            }
        }

        binding.sliderAccuracy.addOnChangeListener { _, value, _ ->
            currentConfig.accuracyLevel = value
            updateAccuracySliderLabel()
            saveConfig()
            // Apply accuracy multiplier to all parameters
            applyAccuracyMultiplier()
            scheduleAccuracyPreviewUpdate()
        }
        
        updateStatus()
    }

    private fun autoDetectColor() {
        val bitmap = previewBaseBitmap ?: return
        
        // Sampling multiple points around the clock area for better accuracy
        val x = (currentConfig.clockX * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (currentConfig.clockY * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        
        val color = bitmap.getPixel(x, y)
        
        // Convert to HSL to find a contrasting color or a prominent one
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl)
        
        // Logic: if background is dark, use light version of the color; if light, use dark.
        // Also boost saturation for a better look
        hsl[1] = (hsl[1] + 0.3f).coerceAtMost(1.0f)
        if (hsl[2] < 0.5f) {
            hsl[2] = 0.85f // Lighten
        } else {
            hsl[2] = 0.15f // Darken
        }
        
        currentConfig.fontColor = androidx.core.graphics.ColorUtils.HSLToColor(hsl)
        saveConfig()
        notifyService()
        requestRender()
    }

    private fun initColorPalette() {
        val colors = listOf(
            Color.WHITE, Color.BLACK, 
            Color.parseColor("#FF3B30"), // iOS Red
            Color.parseColor("#FF9500"), // iOS Orange
            Color.parseColor("#FFCC00"), // iOS Yellow
            Color.parseColor("#4CD964"), // iOS Green
            Color.parseColor("#5AC8FA"), // iOS Light Blue
            Color.parseColor("#007AFF"), // iOS Blue
            Color.parseColor("#5856D6"), // iOS Purple
            Color.parseColor("#FF2D55")  // iOS Pink
        )
        
        binding.colorPalette.removeAllViews()
        for (color in colors) {
            val view = View(this).apply {
                val size = (48 * resources.displayMetrics.density).toInt()
                val margin = (4 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(margin, 0, margin, 0)
                }
                setBackgroundColor(color)
                setOnClickListener {
                    currentConfig.fontColor = color
                    saveConfig()
                    notifyService()
                    requestRender()
                }
            }
            binding.colorPalette.addView(view)
        }
    }

    private fun updateAccuracySliderLabel() {
        val accuracyText = when {
            currentConfig.accuracyLevel < 0.75f -> "Low (Faster)"
            currentConfig.accuracyLevel < 1.15f -> "High"
            else -> "Ultra High (Slower)"
        }
        binding.tvLabelAccuracy.text = "Detection Accuracy: $accuracyText (${String.format("%.1f", currentConfig.accuracyLevel)}×)"
    }

    private fun applyAccuracyMultiplier() {
        isApplyingAccuracyMultiplier = true
        try {
            val factor = currentConfig.accuracyLevel
            // Adjust saliency threshold based on accuracy
            // Lower accuracy = higher threshold (fewer detections, faster)
            // Higher accuracy = lower threshold (more detections, slower)
            val baseThreshold = 0.35f
            val rawThreshold = (baseThreshold / factor).coerceIn(0.05f, 0.95f)
            // Quantize to stepSize 0.01: round to nearest 0.01
            currentConfig.saliencyThreshold = (kotlin.math.round(rawThreshold * 100) / 100).coerceIn(0.05f, 0.95f)
            
            // Adjust feather radius based on accuracy
            val baseFeather = 10
            currentConfig.saliencyFeatherRadius = (baseFeather * factor).toInt().coerceIn(1, 32)
            
            // Adjust cleanup radius based on accuracy
            val baseCleanup = 2
            currentConfig.saliencyCleanupRadius = (baseCleanup * factor).toInt().coerceIn(0, 8)
            
            // Slightly adjust edge lock for better accuracy
            val baseEdgeLock = 0.5f
            val rawEdgeLock = (baseEdgeLock * factor).coerceIn(0.0f, 1.0f)
            // Quantize to stepSize 0.1: round to nearest 0.1
            currentConfig.saliencyEdgeLock = (kotlin.math.round(rawEdgeLock * 10) / 10).coerceIn(0.0f, 1.0f)
            
            // Update all visible sliders to reflect the new values
            binding.sliderSaliencyThreshold.value = currentConfig.saliencyThreshold
            binding.sliderSaliencyFeather.value = currentConfig.saliencyFeatherRadius.toFloat()
            binding.sliderSaliencyCleanup.value = currentConfig.saliencyCleanupRadius.toFloat()
            binding.sliderSaliencyEdgeLock.value = currentConfig.saliencyEdgeLock
            
            updateSaliencyMatteSliderLabels()
        } finally {
            isApplyingAccuracyMultiplier = false
        }
    }

    private fun scheduleAccuracyPreviewUpdate() {
        scheduleMaskRecalculation()
    }

    private fun resizeClock(multiplier: Float) {
        currentConfig.fontSize = (currentConfig.fontSize * multiplier).coerceIn(MIN_CLOCK_SIZE, MAX_CLOCK_SIZE)
        saveConfig()
        notifyService()
        requestRender()
    }

    private fun scheduleMaskRecalculation() {
        maskUpdateJob?.cancel()
        maskUpdateJob = lifecycleScope.launch(Dispatchers.Default) {
            delay(300) // Debounce
            val uri = lastSelectedUri ?: return@launch
            val mode = activeExtractionMode
            
            // If the mode and image are the same as cached, we can skip slow AI inference
            val canUseCache = cachedAiMask != null && cachedAiMode == mode && cachedSourceUri == uri
            
            withContext(Dispatchers.Main) {
                binding.pbExtraction.visibility = View.VISIBLE
                binding.pbExtraction.isIndeterminate = true
                binding.tvStatus.text = "Status: Updating mask..."
            }
            
            try {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeFile(currentConfig.baseImagePath, options) ?: return@launch
                val maskBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val maskFile = File(getExternalFilesDir(null), "mask.png")

                var success = false
                
                if (canUseCache) {
                    val mask = cachedAiMask!!
                    success = when (mode) {
                        ExtractionMode.SELFIE_AI, ExtractionMode.DEEPLAB_V3_MOBILENET_V2 -> {
                            extractMaskNative(bitmap, mask.buffer, mask.width, mask.height, maskBitmap)
                        }
                        ExtractionMode.MLKIT_SUBJECT -> {
                            extractSaliencyMatteNative(
                                bitmap, mask.buffer, mask.width, mask.height, maskBitmap,
                                0.4f, currentConfig.mlKitFeatherRadius, 2, 0.5f
                            )
                        }
                        ExtractionMode.SALIENCY_MATTE -> {
                            extractSaliencyMatteNative(
                                bitmap, mask.buffer, mask.width, mask.height, maskBitmap,
                                currentConfig.saliencyThreshold,
                                currentConfig.saliencyFeatherRadius,
                                currentConfig.saliencyCleanupRadius,
                                currentConfig.saliencyEdgeLock
                            )
                        }
                        ExtractionMode.HYBRID_DEPTH -> {
                            extractHybridMaskNative(
                                bitmap, mask.buffer, mask.width, mask.height, maskBitmap,
                                currentConfig.deepLabMinConfidence.coerceAtLeast(0.15f),
                                binding.sliderThreshold.value,
                                HYBRID_FEATHER_RADIUS
                            )
                        }
                        ExtractionMode.EDGE -> {
                            extractEdgesNative(bitmap, maskBitmap, binding.sliderThreshold.value)
                        }
                    }
                } else {
                    // Re-run full extraction logic (this part matches processSelectedImage but simplified)
                    // For now, if no cache, just call processSelectedImage again
                    withContext(Dispatchers.Main) {
                        processSelectedImage(uri, mode)
                    }
                    maskBitmap.recycle()
                    bitmap.recycle()
                    return@launch
                }

                if (success) {
                    FileOutputStream(maskFile).use { maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    currentConfig.foregroundMaskPath = maskFile.absolutePath
                    withContext(Dispatchers.Main) {
                        updatePreviewBitmaps()
                        requestRender()
                        saveConfig()
                        notifyService()
                        binding.pbExtraction.visibility = View.GONE
                        binding.tvStatus.text = "Status: Updated"
                    }
                }
                maskBitmap.recycle()
                bitmap.recycle()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.pbExtraction.visibility = View.GONE
                    binding.tvStatus.text = "Status: Update failed"
                }
            }
        }
    }

    private fun updateMlKitSliderLabels() {
        binding.tvLabelMlkitFeather.text = "ML Kit Feather: ${currentConfig.mlKitFeatherRadius}px"
    }

    private fun setActiveExtractionMode(mode: ExtractionMode) {
        activeExtractionMode = mode
        val showDeepLab = mode == ExtractionMode.DEEPLAB_V3_MOBILENET_V2 ||
                mode == ExtractionMode.HYBRID_DEPTH ||
                mode == ExtractionMode.SALIENCY_MATTE
        val showMlKit = mode == ExtractionMode.MLKIT_SUBJECT
        val showSaliency = mode == ExtractionMode.SALIENCY_MATTE
        val showEdge = mode == ExtractionMode.EDGE || mode == ExtractionMode.HYBRID_DEPTH

        val showParameters = showDeepLab || showMlKit || showSaliency || showEdge

        binding.parameterCard.visibility = if (showParameters) View.VISIBLE else View.GONE
        // Always show accuracy slider when parameters are visible

        setViewsVisible(showParameters, binding.tvLabelAccuracy, binding.sliderAccuracy)
        setViewsVisible(showDeepLab, binding.tvDeepLabSettings, binding.tvLabelDeepLabClass, binding.sliderDeepLabClass, binding.tvLabelDeepLabConfidence, binding.sliderDeepLabConfidence)
        setViewsVisible(showMlKit, binding.tvMlkitSettings, binding.tvLabelMlkitFeather, binding.sliderMlkitFeather)
        setViewsVisible(showSaliency, binding.tvSaliencySettings, binding.tvLabelSaliencyThreshold, binding.sliderSaliencyThreshold, binding.tvLabelSaliencyFeather, binding.sliderSaliencyFeather, binding.tvLabelSaliencyCleanup, binding.sliderSaliencyCleanup, binding.tvLabelSaliencyEdgeLock, binding.sliderSaliencyEdgeLock)
        setViewsVisible(showEdge, binding.tvLabelThreshold, binding.sliderThreshold)

        saveConfig() // Save the new mode

        // Trigger update if we have an image
        if (lastSelectedUri != null) {
            scheduleMaskRecalculation()
        }
    }

    private fun setViewsVisible(visible: Boolean, vararg views: View) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        views.forEach { it.visibility = visibility }
    }

    private fun notifyService() {
        val intent = Intent("com.thusvill.advancewallpapermanager.UPDATE_CONFIG")

        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun getMlKitSegmenter(): TfliteSelfieSegmenter {
        mlKitSegmenter?.let { return it }
        return TfliteSelfieSegmenter(this, TfliteSelfieSegmenter.Config.mlKitSubject()).also {
            mlKitSegmenter = it
            it.initialize { success ->
                if (!success) Log.e("MainActivity", "ML Kit Segmenter failed to init")
            }
        }
    }

    private fun processSelectedImage(uri: Uri, mode: ExtractionMode) {
        lastSelectedUri = uri
        clearPreviewForNewImage()
        binding.pbExtraction.visibility = View.VISIBLE
        binding.pbExtraction.isIndeterminate = true
        setExtractionButtonsEnabled(false)
        binding.btnInitialSelectImage.text = "Processing..."
        binding.tvStatus.text = "Status: ${mode.statusLabel}..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val baseFile = File(getExternalFilesDir(null), "base.jpg")
                    FileOutputStream(baseFile).use { output -> input.copyTo(output) }
                    currentConfig.baseImagePath = baseFile.absolutePath
                }

                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeFile(currentConfig.baseImagePath, options) ?: throw Exception("Failed to decode image")
                val maskFile = File(getExternalFilesDir(null), "mask.png")
                val maskBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                
                val success: Boolean
                var mask: TfliteSelfieSegmenter.SegmentationMask? = null
                
                when (mode) {
                    ExtractionMode.SELFIE_AI -> {
                        mask = getSelfieSegmenter().segment(bitmap)
                            ?: throw Exception("Selfie segmentation failed")
                        success = extractMaskNative(bitmap, mask.buffer, mask.width, mask.height, maskBitmap)
                    }
                    ExtractionMode.DEEPLAB_V3_MOBILENET_V2 -> {
                        mask = getDeepLabSegmenter().segment(bitmap)
                            ?: throw Exception("DeepLabV3 MobileNetV2 segmentation failed")
                        success = extractMaskNative(bitmap, mask.buffer, mask.width, mask.height, maskBitmap)
                    }
                    ExtractionMode.EDGE -> {
                        val threshold = binding.sliderThreshold.value
                        success = extractEdgesNative(bitmap, maskBitmap, threshold)
                    }
                    ExtractionMode.HYBRID_DEPTH -> {
                        val segmenter = TfliteSelfieSegmenter(
                            this@MainActivity,
                            TfliteSelfieSegmenter.Config.deepLabV3MobileNetV2Confidence(
                                targetClassIndex = currentConfig.deepLabTargetClassIndex
                            )
                        )
                        mask = try {
                            segmenter.segment(bitmap)
                                ?: throw Exception("Hybrid DeepLab confidence pass failed")
                        } finally {
                            segmenter.close()
                        }
                        success = extractHybridMaskNative(
                            bitmap,
                            mask.buffer,
                            mask.width,
                            mask.height,
                            maskBitmap,
                            currentConfig.deepLabMinConfidence.coerceAtLeast(0.15f),
                            binding.sliderThreshold.value,
                            HYBRID_FEATHER_RADIUS
                        )
                    }
                    ExtractionMode.MLKIT_SUBJECT -> {
                        mask = try {
                            getMlKitSegmenter().segment(bitmap) ?: throw Exception("ML Kit segmentation failed")
                        } catch (e: Exception) {
                            if (e.message?.contains("Waiting for", ignoreCase = true) == true ||
                                e.cause?.message?.contains("Waiting for", ignoreCase = true) == true) {
                                withContext(Dispatchers.Main) {
                                    binding.tvStatus.text = "Status: Downloading AI model... please wait."
                                    delay(5000)
                                    processSelectedImage(uri, mode)
                                }
                                return@launch
                            }
                            throw e
                        }
                        success = extractSaliencyMatteNative(
                            bitmap,
                            mask.buffer,
                            mask.width,
                            mask.height,
                            maskBitmap,
                            0.4f,
                            currentConfig.mlKitFeatherRadius,
                            2,
                            0.5f
                        )
                    }
                    ExtractionMode.SALIENCY_MATTE -> {
                        mask = runSaliencyMatteSegmenter(bitmap)
                        success = extractSaliencyMatteNative(
                            bitmap,
                            mask.buffer,
                            mask.width,
                            mask.height,
                            maskBitmap,
                            currentConfig.saliencyThreshold,
                            currentConfig.saliencyFeatherRadius,
                            currentConfig.saliencyCleanupRadius,
                            currentConfig.saliencyEdgeLock
                        )
                    }
                }
                
                if (success) {
                    // Update cache for AI-based modes
                    if (mask != null && (mode == ExtractionMode.MLKIT_SUBJECT || mode == ExtractionMode.SALIENCY_MATTE || mode == ExtractionMode.SELFIE_AI || mode == ExtractionMode.DEEPLAB_V3_MOBILENET_V2)) {
                        cachedAiMask = mask
                        cachedAiMode = mode
                        cachedSourceUri = uri
                    }
                    FileOutputStream(maskFile).use { maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    currentConfig.foregroundMaskPath = maskFile.absolutePath
                    
                    withContext(Dispatchers.Main) {
                        updatePreviewBitmaps()
                        updateEditorVisibility()
                        requestRender()
                        updateStatus()
                        saveConfig()
                        notifyService()
                        binding.pbExtraction.visibility = View.GONE
                        setExtractionButtonsEnabled(true)
                        binding.btnInitialSelectImage.text = "Select Image"
                        Toast.makeText(this@MainActivity, "Depth Mask Extracted (${mode.toastLabel})!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    throw Exception("Extraction failed")
                }
                maskBitmap.recycle()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.pbExtraction.visibility = View.GONE
                    setExtractionButtonsEnabled(true)
                    binding.btnInitialSelectImage.text = "Select Image"
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    updateEditorVisibility()
                    updateStatus()
                }
            }
        }
    }

    private fun clearPreviewForNewImage() {
        cachedAiMask = null
        cachedAiMode = null
        cachedSourceUri = null
        lastSelectedUri = null
        currentConfig.baseImagePath = ""
        currentConfig.foregroundMaskPath = ""
        previewBaseBitmap?.recycle()
        previewMaskBitmap?.recycle()
        previewTimeBitmap?.recycle()
        previewBaseBitmap = null
        previewMaskBitmap = null
        previewTimeBitmap = null
        clearPreviewSurface()
        updateEditorVisibility()
        updateStatus()
    }

    private fun clearPreviewSurface() {
        val holder = binding.surfacePreview.holder
        if (!holder.surface.isValid) return

        try {
            val canvas = holder.lockCanvas() ?: return
            try {
                canvas.drawColor(Color.BLACK)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
        }
    }

    private fun saveConfig() {
        prefs.edit().apply {
            putString("basePath", currentConfig.baseImagePath)
            putString("maskPath", currentConfig.foregroundMaskPath)
            putFloat("clockX", currentConfig.clockX)
            putFloat("clockY", currentConfig.clockY)
            putFloat("fontSize", currentConfig.fontSize)
            putInt("fontColor", currentConfig.fontColor)
            putFloat("fontThickness", currentConfig.fontThickness)
            putInt("deepLabTargetClassIndex", currentConfig.deepLabTargetClassIndex)
            putFloat("deepLabMinConfidence", currentConfig.deepLabMinConfidence)
            putFloat("saliencyThreshold", currentConfig.saliencyThreshold)
            putInt("saliencyFeatherRadius", currentConfig.saliencyFeatherRadius)
            putInt("saliencyCleanupRadius", currentConfig.saliencyCleanupRadius)
            putFloat("saliencyEdgeLock", currentConfig.saliencyEdgeLock)
            putInt("mlKitFeatherRadius", currentConfig.mlKitFeatherRadius)
            putFloat("clockHeightScale", currentConfig.clockHeightScale)
            putString("clockMode", currentConfig.clockMode.name)
            putFloat("accuracyLevel", currentConfig.accuracyLevel)
            putString("fontFamily", currentConfig.fontFamily)
            putFloat("letterSpacing", currentConfig.letterSpacing)
            putFloat("lineSpacing", currentConfig.lineSpacing)
            putBoolean("use24HourFormat", currentConfig.use24HourFormat)
            putString("activeExtractionMode", activeExtractionMode.name)
            apply()
        }
    }

    private fun loadConfig() {
        currentConfig.baseImagePath = prefs.getString("basePath", "") ?: ""
        currentConfig.foregroundMaskPath = prefs.getString("maskPath", "") ?: ""
        currentConfig.clockX = prefs.getFloat("clockX", 0.5f)
        currentConfig.clockY = prefs.getFloat("clockY", 0.4f)
        currentConfig.fontSize = prefs.getFloat("fontSize", 200f)
        currentConfig.fontColor = prefs.getInt("fontColor", Color.WHITE)
        currentConfig.fontThickness = prefs.getFloat("fontThickness", 0f)
        currentConfig.deepLabTargetClassIndex = prefs.getInt("deepLabTargetClassIndex", 15)
        currentConfig.deepLabMinConfidence = prefs.getFloat("deepLabMinConfidence", 0f)
        // Quantize saliency threshold to match slider step size (0.01) and valueFrom (0.05)
        val rawThreshold = prefs.getFloat("saliencyThreshold", 0.35f)
        currentConfig.saliencyThreshold = (kotlin.math.round(rawThreshold * 100) / 100).coerceIn(0.05f, 0.95f)
        currentConfig.saliencyFeatherRadius = prefs.getInt("saliencyFeatherRadius", 10)
        currentConfig.saliencyCleanupRadius = prefs.getInt("saliencyCleanupRadius", 2)
        // Quantize edge lock to match slider step size (0.1)
        val rawEdgeLock = prefs.getFloat("saliencyEdgeLock", 0.5f)
        currentConfig.saliencyEdgeLock = (kotlin.math.round(rawEdgeLock * 10) / 10).coerceIn(0.0f, 1.0f)
        currentConfig.mlKitFeatherRadius = prefs.getInt("mlKitFeatherRadius", 10)
        currentConfig.clockHeightScale = prefs.getFloat("clockHeightScale", 1.0f)
        currentConfig.clockMode = ClockMode.valueOf(prefs.getString("clockMode", ClockMode.HORIZONTAL.name) ?: ClockMode.HORIZONTAL.name)
        // Quantize accuracy level to match slider step size (0.1)
        val rawAccuracy = prefs.getFloat("accuracyLevel", 1.0f)
        currentConfig.accuracyLevel = (kotlin.math.round(rawAccuracy * 10) / 10).coerceIn(0.5f, 1.5f)
        currentConfig.fontFamily = prefs.getString("fontFamily", "sans-serif-condensed") ?: "sans-serif-condensed"
        currentConfig.letterSpacing = prefs.getFloat("letterSpacing", 0f)
        currentConfig.lineSpacing = prefs.getFloat("lineSpacing", 0f)
        currentConfig.use24HourFormat = prefs.getBoolean("use24HourFormat", true)

        val modeName = prefs.getString("activeExtractionMode", ExtractionMode.SELFIE_AI.name)
        activeExtractionMode = ExtractionMode.valueOf(modeName ?: ExtractionMode.SELFIE_AI.name)

        if (currentConfig.baseImagePath.isNotEmpty()) {
            val file = File(currentConfig.baseImagePath)
            if (file.exists()) {
                lastSelectedUri = Uri.fromFile(file)
            }
        }
    }

    private fun updateStatus() {
        val hasBase = currentConfig.baseImagePath.isNotEmpty() && File(currentConfig.baseImagePath).exists()
        val hasMask = currentConfig.foregroundMaskPath.isNotEmpty() && File(currentConfig.foregroundMaskPath).exists()
        binding.tvStatus.text = "Base: ${if (hasBase) "OK" else "None"}, Mask: ${if (hasMask) "OK" else "None"}"
    }

    private fun updateEditorVisibility() {
        val hasBase = currentConfig.baseImagePath.isNotEmpty() && File(currentConfig.baseImagePath).exists()
        binding.initialSelectCard.visibility = if (hasBase) View.GONE else View.VISIBLE
        binding.previewCard.visibility = if (hasBase) View.VISIBLE else View.GONE
        binding.controlsScroll.visibility = if (hasBase) View.VISIBLE else View.GONE
        binding.btnSelectImage.text = if (hasBase) "Reselect Image" else "Select Image"
        binding.tvSubtitle.text = if (hasBase) {
            "Preview stays visible. Drag the clock directly on it."
        } else {
            "Select an image to open the lock screen editor."
        }
        if (hasBase) {
            syncPreviewAspectRatio()
            updateClockDragHandle()
        }
    }

    private fun setExtractionButtonsEnabled(enabled: Boolean) {
        binding.btnInitialSelectImage.isEnabled = enabled
        binding.btnSelectImage.isEnabled = enabled
        binding.spinnerPipeline.isEnabled = enabled
    }

    private fun getSelfieSegmenter(): TfliteSelfieSegmenter {
        return selfieSegmenter ?: TfliteSelfieSegmenter(
            this,
            TfliteSelfieSegmenter.Config.selfie()
        ).also { selfieSegmenter = it }
    }

    private fun getDeepLabSegmenter(): TfliteSelfieSegmenter {
        return deepLabSegmenter ?: TfliteSelfieSegmenter(
            this,
            TfliteSelfieSegmenter.Config.deepLabV3MobileNetV2(
                targetClassIndex = currentConfig.deepLabTargetClassIndex,
                minConfidence = currentConfig.deepLabMinConfidence
            )
        ).also { deepLabSegmenter = it }
    }

    private fun runSaliencyMatteSegmenter(bitmap: Bitmap): TfliteSelfieSegmenter.SegmentationMask {
        val segmenter = TfliteSelfieSegmenter(
            this,
            TfliteSelfieSegmenter.Config.deepLabV3MobileNetV2Confidence(
                targetClassIndex = currentConfig.deepLabTargetClassIndex
            )
        )
        return try {
            segmenter.segment(bitmap)
                ?: throw Exception("Saliency matte confidence pass failed")
        } finally {
            segmenter.close()
        }
    }

    private fun updateDeepLabSliderLabels() {
        val className = DEEPLAB_CLASS_NAMES.getOrNull(currentConfig.deepLabTargetClassIndex) ?: "Class"
        binding.tvLabelDeepLabClass.text = "DeepLab Target: ${currentConfig.deepLabTargetClassIndex} ($className)"
        binding.tvLabelDeepLabConfidence.text = "DeepLab Min Confidence: ${String.format(Locale.US, "%.2f", currentConfig.deepLabMinConfidence)}"
    }

    private fun updateSaliencyMatteSliderLabels() {
        binding.tvLabelSaliencyThreshold.text = "Saliency Threshold: ${String.format(Locale.US, "%.2f", currentConfig.saliencyThreshold)}"
        binding.tvLabelSaliencyFeather.text = "Matte Feather: ${currentConfig.saliencyFeatherRadius}px"
        binding.tvLabelSaliencyCleanup.text = "Mask Cleanup: ${currentConfig.saliencyCleanupRadius}px"
        binding.tvLabelSaliencyEdgeLock.text = "Edge Lock: ${String.format(Locale.US, "%.2f", currentConfig.saliencyEdgeLock)}"
    }

    private fun updateStretchSliderLabel() {
        binding.tvLabelStretch.text = "Vertical Stretch (iOS Style): ${String.format(Locale.US, "%.1fx", currentConfig.clockHeightScale)}"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        selfieSegmenter?.close()
        deepLabSegmenter?.close()
        mlKitSegmenter?.close()
        maskUpdateJob?.cancel()
    }

    private enum class ExtractionMode(
        val displayName: String,
        val statusLabel: String,
        val toastLabel: String
    ) {
        SELFIE_AI("Selfie AI", "Selfie AI Extraction", "Selfie AI"),
        DEEPLAB_V3_MOBILENET_V2("DeepLabV3 MobileNetV2", "DeepLabV3 MobileNetV2 Extraction", "DeepLabV3 MobileNetV2"),
        EDGE("Math Edge", "Edge Detection", "Edge"),
        HYBRID_DEPTH("Hybrid Depth", "Hybrid Depth Extraction", "Hybrid Depth"),
        SALIENCY_MATTE("Saliency Matte", "Saliency Matte Extraction", "Saliency Matte"),
        MLKIT_SUBJECT("ML Kit Subject", "ML Kit Extraction", "ML Kit")
    }

    private companion object {
        const val HYBRID_FEATHER_RADIUS = 8
        const val PREVIEW_SCREEN_HEIGHT_FRACTION = 0.5f
        const val CLOCK_RESIZE_STEP = 1.08f
        const val MIN_CLOCK_SIZE = 40f
        const val MAX_CLOCK_SIZE = 1000f

        val DEEPLAB_CLASS_NAMES = listOf(
            "Background",
            "Aeroplane",
            "Bicycle",
            "Bird",
            "Boat",
            "Bottle",
            "Bus",
            "Car",
            "Cat",
            "Chair",
            "Cow",
            "Dining Table",
            "Dog",
            "Horse",
            "Motorbike",
            "Person",
            "Potted Plant",
            "Sheep",
            "Sofa",
            "Train",
            "TV"
        )
    }
}
