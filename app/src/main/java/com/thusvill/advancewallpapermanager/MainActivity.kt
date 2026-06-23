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
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
    private var deepLabPreviewJob: Job? = null
    private var saliencyMattePreviewJob: Job? = null
    private var isApplyingAccuracyMultiplier: Boolean = false  // Flag to prevent recursive updates

    private var previewBaseBitmap: Bitmap? = null
    private var previewMaskBitmap: Bitmap? = null
    private var previewTimeBitmap: Bitmap? = null
    private val previewTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
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
        
        val timeText = timeFormat.format(Date())
        updateTimeBitmap(timeText)
        
        renderNativeFrame(
            surface,
            timeText,
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

    private fun updateTimeBitmap(text: String) {
        val scale = previewScaleToWallpaper()
        previewTextPaint.textSize = currentConfig.fontSize * scale
        previewTextPaint.color = currentConfig.fontColor
        previewTextPaint.typeface = Typeface.DEFAULT_BOLD
        
        if (currentConfig.fontThickness > 0) {
            previewTextPaint.style = Paint.Style.FILL_AND_STROKE
            previewTextPaint.strokeWidth = currentConfig.fontThickness * scale
        } else {
            previewTextPaint.style = Paint.Style.FILL
        }

        val bounds = Rect()
        previewTextPaint.getTextBounds(text, 0, text.length, bounds)
        
        val padding = (40f * scale).toInt().coerceAtLeast(8)
        val width = bounds.width() + padding
        val height = (bounds.height() * currentConfig.clockHeightScale + padding).toInt()
        
        if (previewTimeBitmap == null || previewTimeBitmap!!.width != width || previewTimeBitmap!!.height != height) {
            previewTimeBitmap?.recycle()
            previewTimeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        
        previewTimeBitmap?.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(previewTimeBitmap!!)
        
        // Apply vertical stretch
        canvas.save()
        canvas.scale(1.0f, currentConfig.clockHeightScale, width / 2f, height / 2f)
        canvas.drawText(text, width / 2f, height / 2f - (previewTextPaint.descent() + previewTextPaint.ascent()) / 2f, previewTextPaint)
        canvas.restore()
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
        binding.sliderStretch.value = currentConfig.clockHeightScale
        updateStretchSliderLabel()
        setActiveExtractionMode(activeExtractionMode)

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
            scheduleDeepLabPreviewUpdate()
        }
        binding.sliderDeepLabConfidence.addOnChangeListener { _, value, _ ->
            currentConfig.deepLabMinConfidence = value
            updateDeepLabSliderLabels()
            saveConfig()
            scheduleDeepLabPreviewUpdate()
        }
        binding.sliderSaliencyThreshold.addOnChangeListener { _, value, _ ->
            if (!isApplyingAccuracyMultiplier) {
                currentConfig.saliencyThreshold = value
                updateSaliencyMatteSliderLabels()
                saveConfig()
                scheduleSaliencyMattePreviewUpdate()
            }
        }
        binding.sliderSaliencyFeather.addOnChangeListener { _, value, _ ->
            if (!isApplyingAccuracyMultiplier) {
                currentConfig.saliencyFeatherRadius = value.toInt()
                updateSaliencyMatteSliderLabels()
                saveConfig()
                scheduleSaliencyMattePreviewUpdate()
            }
        }
        binding.sliderSaliencyCleanup.addOnChangeListener { _, value, _ ->
            if (!isApplyingAccuracyMultiplier) {
                currentConfig.saliencyCleanupRadius = value.toInt()
                updateSaliencyMatteSliderLabels()
                saveConfig()
                scheduleSaliencyMattePreviewUpdate()
            }
        }
        binding.sliderSaliencyEdgeLock.addOnChangeListener { _, value, _ ->
            if (!isApplyingAccuracyMultiplier) {
                currentConfig.saliencyEdgeLock = value
                updateSaliencyMatteSliderLabels()
                saveConfig()
                scheduleSaliencyMattePreviewUpdate()
            }
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
        lifecycleScope.launch(Dispatchers.Default) {
            delay(300)  // Debounce: wait 300ms after slider stops
            withContext(Dispatchers.Main) {
                when (activeExtractionMode) {
                    ExtractionMode.SALIENCY_MATTE -> scheduleSaliencyMattePreviewUpdate()
                    ExtractionMode.HYBRID_DEPTH -> scheduleDeepLabPreviewUpdate()
                    else -> {}
                }
            }
        }
    }

    private fun resizeClock(multiplier: Float) {
        currentConfig.fontSize = (currentConfig.fontSize * multiplier).coerceIn(MIN_CLOCK_SIZE, MAX_CLOCK_SIZE)
        saveConfig()
        notifyService()
        requestRender()
    }

    private fun setActiveExtractionMode(mode: ExtractionMode) {
        activeExtractionMode = mode
        val isMlKit = mode == ExtractionMode.MLKIT_SUBJECT
        val showDeepLab = mode == ExtractionMode.DEEPLAB_V3_MOBILENET_V2 ||
                mode == ExtractionMode.HYBRID_DEPTH ||
                mode == ExtractionMode.SALIENCY_MATTE
        val showSaliency = mode == ExtractionMode.SALIENCY_MATTE
        val showEdge = mode == ExtractionMode.EDGE || mode == ExtractionMode.HYBRID_DEPTH


        val showParameters = showDeepLab || showSaliency || showEdge


        binding.parameterCard.visibility = if (showParameters) View.VISIBLE else View.GONE
        // Always show accuracy slider when parameters are visible

        setViewsVisible(showParameters, binding.tvLabelAccuracy, binding.sliderAccuracy)
        setViewsVisible(showDeepLab, binding.tvDeepLabSettings, binding.tvLabelDeepLabClass, binding.sliderDeepLabClass, binding.tvLabelDeepLabConfidence, binding.sliderDeepLabConfidence)
        setViewsVisible(showSaliency, binding.tvSaliencySettings, binding.tvLabelSaliencyThreshold, binding.sliderSaliencyThreshold, binding.tvLabelSaliencyFeather, binding.sliderSaliencyFeather, binding.tvLabelSaliencyCleanup, binding.sliderSaliencyCleanup, binding.tvLabelSaliencyEdgeLock, binding.sliderSaliencyEdgeLock)
        setViewsVisible(showEdge, binding.tvLabelThreshold, binding.sliderThreshold)
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

    private fun processSelectedImage(uri: Uri, mode: ExtractionMode) {

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
                when (mode) {
                    ExtractionMode.SELFIE_AI -> {
                        val mask = getSelfieSegmenter().segment(bitmap)
                            ?: throw Exception("Selfie segmentation failed")
                        success = extractMaskNative(bitmap, mask.buffer, mask.width, mask.height, maskBitmap)
                    }
                    ExtractionMode.DEEPLAB_V3_MOBILENET_V2 -> {
                        val mask = getDeepLabSegmenter().segment(bitmap)
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
                        val mask = try {
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

                        val segmenter = TfliteSelfieSegmenter(
                            this@MainActivity,
                            TfliteSelfieSegmenter.Config.mlKitSubject()
                        )
                        try {
                            val mask = segmenter.segment(bitmap) ?: throw Exception("Failed")

                        } catch (e: Exception) {
                            if (e.message?.contains("Waiting for") == true) {
                                withContext(Dispatchers.Main) {
                                    binding.tvStatus.text = "Status: Downloading AI model... please wait."

                                    delay(5000)
                                    processSelectedImage(uri, mode)
                                }
                            }
                        }

                        val mask = try {
                            segmenter.segment(bitmap) ?: throw Exception("ML Kit segmentation failed")
                        } finally {
                            segmenter.close()
                        }
                        success = extractMaskNative(bitmap, mask.buffer, mask.width, mask.height, maskBitmap)
                    }
                    ExtractionMode.SALIENCY_MATTE -> {
                        val mask = runSaliencyMatteSegmenter(bitmap)
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
            putFloat("fontThickness", currentConfig.fontThickness)
            putInt("deepLabTargetClassIndex", currentConfig.deepLabTargetClassIndex)
            putFloat("deepLabMinConfidence", currentConfig.deepLabMinConfidence)
            putFloat("saliencyThreshold", currentConfig.saliencyThreshold)
            putInt("saliencyFeatherRadius", currentConfig.saliencyFeatherRadius)
            putInt("saliencyCleanupRadius", currentConfig.saliencyCleanupRadius)
            putFloat("saliencyEdgeLock", currentConfig.saliencyEdgeLock)
            putFloat("clockHeightScale", currentConfig.clockHeightScale)
            putFloat("accuracyLevel", currentConfig.accuracyLevel)
            apply()
        }
    }

    private fun loadConfig() {
        currentConfig.baseImagePath = prefs.getString("basePath", "") ?: ""
        currentConfig.foregroundMaskPath = prefs.getString("maskPath", "") ?: ""
        currentConfig.clockX = prefs.getFloat("clockX", 0.5f)
        currentConfig.clockY = prefs.getFloat("clockY", 0.4f)
        currentConfig.fontSize = prefs.getFloat("fontSize", 200f)
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
        currentConfig.clockHeightScale = prefs.getFloat("clockHeightScale", 1.0f)
        // Quantize accuracy level to match slider step size (0.1)
        val rawAccuracy = prefs.getFloat("accuracyLevel", 1.0f)
        currentConfig.accuracyLevel = (kotlin.math.round(rawAccuracy * 10) / 10).coerceIn(0.5f, 1.5f)
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

    private fun scheduleDeepLabPreviewUpdate() {
        deepLabPreviewJob?.cancel()
        if (currentConfig.baseImagePath.isEmpty() || !File(currentConfig.baseImagePath).exists()) {
            return
        }

        val targetClassIndex = currentConfig.deepLabTargetClassIndex
        val minConfidence = currentConfig.deepLabMinConfidence
        deepLabPreviewJob = lifecycleScope.launch {
            delay(DEEPLAB_PREVIEW_DEBOUNCE_MS)
            updateDeepLabPreview(targetClassIndex, minConfidence)
        }
    }

    private suspend fun updateDeepLabPreview(targetClassIndex: Int, minConfidence: Float) {
        binding.pbExtraction.visibility = View.VISIBLE
        binding.pbExtraction.isIndeterminate = true
        binding.tvStatus.text = "Status: Updating DeepLab preview..."

        val result = withContext(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            var maskBitmap: Bitmap? = null
            val segmenter = TfliteSelfieSegmenter(
                this@MainActivity,
                TfliteSelfieSegmenter.Config.deepLabV3MobileNetV2(
                    targetClassIndex = targetClassIndex,
                    minConfidence = minConfidence
                )
            )

            try {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                bitmap = BitmapFactory.decodeFile(currentConfig.baseImagePath, options)
                    ?: return@withContext false
                maskBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val mask = segmenter.segment(bitmap) ?: return@withContext false
                val success = extractMaskNative(bitmap, mask.buffer, mask.width, mask.height, maskBitmap)

                if (success) {
                    val maskFile = File(getExternalFilesDir(null), "mask.png")
                    FileOutputStream(maskFile).use { maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    currentConfig.foregroundMaskPath = maskFile.absolutePath
                }
                success
            } catch (e: Exception) {
                false
            } finally {
                segmenter.close()
                maskBitmap?.recycle()
                bitmap?.recycle()
            }
        }

        binding.pbExtraction.visibility = View.GONE
        if (result) {
            updatePreviewBitmaps()
            requestRender()
            saveConfig()
            notifyService()
        }
        updateStatus()
    }

    private fun scheduleSaliencyMattePreviewUpdate() {
        saliencyMattePreviewJob?.cancel()
        if (currentConfig.baseImagePath.isEmpty() || !File(currentConfig.baseImagePath).exists()) {
            return
        }

        val threshold = currentConfig.saliencyThreshold
        val featherRadius = currentConfig.saliencyFeatherRadius
        val cleanupRadius = currentConfig.saliencyCleanupRadius
        val edgeLock = currentConfig.saliencyEdgeLock
        saliencyMattePreviewJob = lifecycleScope.launch {
            delay(SALIENCY_PREVIEW_DEBOUNCE_MS)
            updateSaliencyMattePreview(threshold, featherRadius, cleanupRadius, edgeLock)
        }
    }

    private suspend fun updateSaliencyMattePreview(
        threshold: Float,
        featherRadius: Int,
        cleanupRadius: Int,
        edgeLock: Float
    ) {
        binding.pbExtraction.visibility = View.VISIBLE
        binding.pbExtraction.isIndeterminate = true
        binding.tvStatus.text = "Status: Updating Saliency Matte..."

        val result = withContext(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            var maskBitmap: Bitmap? = null
            try {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                bitmap = BitmapFactory.decodeFile(currentConfig.baseImagePath, options)
                    ?: return@withContext false
                maskBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val mask = runSaliencyMatteSegmenter(bitmap)
                val success = extractSaliencyMatteNative(
                    bitmap,
                    mask.buffer,
                    mask.width,
                    mask.height,
                    maskBitmap,
                    threshold,
                    featherRadius,
                    cleanupRadius,
                    edgeLock
                )

                if (success) {
                    val maskFile = File(getExternalFilesDir(null), "mask.png")
                    FileOutputStream(maskFile).use { maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    currentConfig.foregroundMaskPath = maskFile.absolutePath
                }
                success
            } catch (e: Exception) {
                false
            } finally {
                maskBitmap?.recycle()
                bitmap?.recycle()
            }
        }

        binding.pbExtraction.visibility = View.GONE
        if (result) {
            updatePreviewBitmaps()
            requestRender()
            saveConfig()
            notifyService()
        }
        updateStatus()
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
        deepLabPreviewJob?.cancel()
        saliencyMattePreviewJob?.cancel()
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
        const val DEEPLAB_PREVIEW_DEBOUNCE_MS = 350L
        const val SALIENCY_PREVIEW_DEBOUNCE_MS = 450L
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
