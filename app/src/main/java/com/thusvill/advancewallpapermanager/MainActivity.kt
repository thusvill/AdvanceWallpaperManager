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
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE) }
    
    private var currentConfig = WallpaperConfig()
    private var selfieSegmenter: TfliteSelfieSegmenter? = null
    private var deepLabSegmenter: TfliteSelfieSegmenter? = null
    private var deepLabPreviewJob: Job? = null
    private var saliencyMattePreviewJob: Job? = null

    private var previewBaseBitmap: Bitmap? = null
    private var previewMaskBitmap: Bitmap? = null
    private var previewTimeBitmap: Bitmap? = null
    private val previewTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

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
            processSelectedImage(it, ExtractionMode.SELFIE_AI)
        }
    }

    private val deepLabLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            processSelectedImage(it, ExtractionMode.DEEPLAB_V3_MOBILENET_V2)
        }
    }

    private val edgeDetectionLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            processSelectedImage(it, ExtractionMode.EDGE)
        }
    }

    private val hybridDepthLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            processSelectedImage(it, ExtractionMode.HYBRID_DEPTH)
        }
    }

    private val saliencyMatteLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            processSelectedImage(it, ExtractionMode.SALIENCY_MATTE)
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

        binding.btnSelectImage.setOnClickListener { selectImageLauncher.launch("image/*") }
        binding.btnDeepLab.setOnClickListener { deepLabLauncher.launch("image/*") }
        binding.btnExtractEdges.setOnClickListener { edgeDetectionLauncher.launch("image/*") }
        binding.btnHybridDepth.setOnClickListener { hybridDepthLauncher.launch("image/*") }
        binding.btnSaliencyMatte.setOnClickListener { saliencyMatteLauncher.launch("image/*") }

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
    }

    private fun initPreview() {
        binding.surfacePreview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Set fixed size for preview to maintain aspect ratio and prevent distortion
                val w = binding.surfacePreview.width
                val h = binding.surfacePreview.height
                if (w > 0 && h > 0) {
                    holder.setFixedSize(w, h)
                }
                updatePreviewBitmaps()
                requestRender()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                updatePreviewBitmaps()
                requestRender()
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
    }

    private fun updateTimeBitmap(text: String) {
        previewTextPaint.textSize = currentConfig.fontSize * 0.5f // Scale for preview
        previewTextPaint.color = currentConfig.fontColor
        previewTextPaint.typeface = Typeface.DEFAULT_BOLD
        
        if (currentConfig.fontThickness > 0) {
            previewTextPaint.style = Paint.Style.FILL_AND_STROKE
            previewTextPaint.strokeWidth = currentConfig.fontThickness * 0.5f
        } else {
            previewTextPaint.style = Paint.Style.FILL
        }

        val bounds = Rect()
        previewTextPaint.getTextBounds(text, 0, text.length, bounds)
        
        val width = bounds.width() + 20
        val height = bounds.height() + 20
        
        if (previewTimeBitmap == null || previewTimeBitmap!!.width != width || previewTimeBitmap!!.height != height) {
            previewTimeBitmap?.recycle()
            previewTimeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        
        previewTimeBitmap?.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(previewTimeBitmap!!)
        canvas.drawText(text, width / 2f, height / 2f - (previewTextPaint.descent() + previewTextPaint.ascent()) / 2f, previewTextPaint)
    }

    private fun initUi() {
        binding.sliderX.value = currentConfig.clockX
        binding.sliderY.value = currentConfig.clockY
        binding.sliderSize.value = currentConfig.fontSize
        binding.sliderThickness.value = currentConfig.fontThickness
        binding.sliderThreshold.value = 0.1f
        binding.sliderDeepLabClass.value = currentConfig.deepLabTargetClassIndex.toFloat()
        binding.sliderDeepLabConfidence.value = currentConfig.deepLabMinConfidence
        updateDeepLabSliderLabels()
        binding.sliderSaliencyThreshold.value = currentConfig.saliencyThreshold
        binding.sliderSaliencyFeather.value = currentConfig.saliencyFeatherRadius.toFloat()
        binding.sliderSaliencyCleanup.value = currentConfig.saliencyCleanupRadius.toFloat()
        binding.sliderSaliencyEdgeLock.value = currentConfig.saliencyEdgeLock
        updateSaliencyMatteSliderLabels()

        binding.sliderX.addOnChangeListener { _, value, _ -> currentConfig.clockX = value; saveConfig(); notifyService(); requestRender() }
        binding.sliderY.addOnChangeListener { _, value, _ -> currentConfig.clockY = value; saveConfig(); notifyService(); requestRender() }
        binding.sliderSize.addOnChangeListener { _, value, _ -> currentConfig.fontSize = value; saveConfig(); notifyService(); requestRender() }
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
            currentConfig.saliencyThreshold = value
            updateSaliencyMatteSliderLabels()
            saveConfig()
            scheduleSaliencyMattePreviewUpdate()
        }
        binding.sliderSaliencyFeather.addOnChangeListener { _, value, _ ->
            currentConfig.saliencyFeatherRadius = value.toInt()
            updateSaliencyMatteSliderLabels()
            saveConfig()
            scheduleSaliencyMattePreviewUpdate()
        }
        binding.sliderSaliencyCleanup.addOnChangeListener { _, value, _ ->
            currentConfig.saliencyCleanupRadius = value.toInt()
            updateSaliencyMatteSliderLabels()
            saveConfig()
            scheduleSaliencyMattePreviewUpdate()
        }
        binding.sliderSaliencyEdgeLock.addOnChangeListener { _, value, _ ->
            currentConfig.saliencyEdgeLock = value
            updateSaliencyMatteSliderLabels()
            saveConfig()
            scheduleSaliencyMattePreviewUpdate()
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
        
        updateStatus()
    }

    private fun notifyService() {
        sendBroadcast(Intent("com.thusvill.advancewallpapermanager.UPDATE_CONFIG"))
    }

    private fun processSelectedImage(uri: Uri, mode: ExtractionMode) {
        binding.pbExtraction.visibility = View.VISIBLE
        binding.pbExtraction.isIndeterminate = true
        setExtractionButtonsEnabled(false)
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
                        requestRender()
                        updateStatus()
                        saveConfig()
                        notifyService()
                        binding.pbExtraction.visibility = View.GONE
                        setExtractionButtonsEnabled(true)
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
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    updateStatus()
                }
            }
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
        currentConfig.saliencyThreshold = prefs.getFloat("saliencyThreshold", 0.35f)
        currentConfig.saliencyFeatherRadius = prefs.getInt("saliencyFeatherRadius", 10)
        currentConfig.saliencyCleanupRadius = prefs.getInt("saliencyCleanupRadius", 2)
        currentConfig.saliencyEdgeLock = prefs.getFloat("saliencyEdgeLock", 0.5f)
    }

    private fun updateStatus() {
        val hasBase = currentConfig.baseImagePath.isNotEmpty() && File(currentConfig.baseImagePath).exists()
        val hasMask = currentConfig.foregroundMaskPath.isNotEmpty() && File(currentConfig.foregroundMaskPath).exists()
        binding.tvStatus.text = "Base: ${if (hasBase) "OK" else "None"}, Mask: ${if (hasMask) "OK" else "None"}"
    }

    private fun setExtractionButtonsEnabled(enabled: Boolean) {
        binding.btnSelectImage.isEnabled = enabled
        binding.btnDeepLab.isEnabled = enabled
        binding.btnExtractEdges.isEnabled = enabled
        binding.btnHybridDepth.isEnabled = enabled
        binding.btnSaliencyMatte.isEnabled = enabled
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
    
    override fun onDestroy() {
        super.onDestroy()
        selfieSegmenter?.close()
        deepLabSegmenter?.close()
        deepLabPreviewJob?.cancel()
        saliencyMattePreviewJob?.cancel()
    }

    private enum class ExtractionMode(
        val statusLabel: String,
        val toastLabel: String
    ) {
        SELFIE_AI("Selfie AI Extraction", "Selfie AI"),
        DEEPLAB_V3_MOBILENET_V2("DeepLabV3 MobileNetV2 Extraction", "DeepLabV3 MobileNetV2"),
        EDGE("Edge Detection", "Edge"),
        HYBRID_DEPTH("Hybrid Depth Extraction", "Hybrid Depth"),
        SALIENCY_MATTE("Saliency Matte Extraction", "Saliency Matte")
    }

    private companion object {
        const val DEEPLAB_PREVIEW_DEBOUNCE_MS = 350L
        const val SALIENCY_PREVIEW_DEBOUNCE_MS = 450L
        const val HYBRID_FEATHER_RADIUS = 8

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
