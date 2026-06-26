package com.thusvill.advancewallpapermanager

import android.app.Application
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class EditorViewModel(application: Application, private val configManager: ConfigManager) : AndroidViewModel(application) {
    private val context = application
    private val TAG = "EditorViewModel"

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()

    private var selfieSegmenter: TfliteSelfieSegmenter? = null
    private var deepLabSegmenter: TfliteSelfieSegmenter? = null
    private var mlKitSegmenter: TfliteSelfieSegmenter? = null
    
    private var maskUpdateJob: Job? = null
    
    // Thread-safe bitmap management
    private val renderLock = Any()
    private var previewBaseBitmap: Bitmap? = null
    private var previewMaskBitmap: Bitmap? = null
    private var previewTimeBitmap: Bitmap? = null

    @Volatile
    private var lastSurface: Surface? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private var cachedAiMask: TfliteSelfieSegmenter.SegmentationMask? = null
    private var cachedAiMode: ExtractionMode? = null

    // Performance: Ultra-Responsive Render Queue
    private val renderChannel = Channel<Unit>(Channel.CONFLATED)

    init {
        // High-performance render thread (background)
        viewModelScope.launch(Dispatchers.Default) {
            var lastRenderTime = 0L
            for (req in renderChannel) {
                val now = System.currentTimeMillis()
                val delta = now - lastRenderTime
                
                // Pacing: Max 60 FPS (16ms per frame)
                if (delta < 16) {
                    delay(16 - delta)
                }
                
                executeRenderFrame()
                lastRenderTime = System.currentTimeMillis()
            }
        }
    }

    fun loadConfig(configId: String?) {
        if (configId == null) {
            _uiState.update { it.copy(config = WallpaperConfig()) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val config = configManager.loadBundleConfig(configId) ?: WallpaperConfig()
            _uiState.update { it.copy(config = config) }
            
            synchronized(renderLock) {
                previewBaseBitmap?.recycle()
                previewMaskBitmap?.recycle()
                previewBaseBitmap = configManager.loadBundleBitmap(configId, "base")
                previewMaskBitmap = configManager.loadBundleBitmap(configId, "mask")
            }
            
            updateTimeBitmap()
            requestPreviewUpdate()
        }
    }

    fun setSourceImage(uri: Uri) {
        cachedAiMask = null
        cachedAiMode = null
        
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = loadRaw(uri)
            synchronized(renderLock) {
                previewBaseBitmap?.recycle()
                previewMaskBitmap?.recycle()
                previewBaseBitmap = bitmap
                previewMaskBitmap = null
            }
            
            updateTimeBitmap()
            requestPreviewUpdate()
            
            _uiState.update { it.copy(isExtracting = true, statusText = "Status: Raw image loaded. Extracting...") }
            performExtraction(uiState.value.extractionMode)
        }
    }

    private fun loadRaw(uri: Uri): Bitmap? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val original = BitmapFactory.decodeStream(inputStream) ?: return null
        
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()

        val maxDim = 4096f
        var scale = 1.0f
        if (original.width > maxDim || original.height > maxDim) {
            scale = maxDim / Math.max(original.width.toFloat(), original.height.toFloat())
        }

        val coverScale = Math.max(screenW / (original.width * scale), screenH / (original.height * scale))
        val finalScale = scale * coverScale

        val offsetX = (screenW - original.width * finalScale) / 2f
        val offsetY = (screenH - original.height * finalScale) / 2f
        
        _uiState.update { 
            it.copy(config = it.config.copy(
                wallpaperScale = finalScale,
                wallpaperOffsetX = offsetX,
                wallpaperOffsetY = offsetY
            ))
        }

        return if (scale < 1.0f) {
            val scaled = Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true)
            original.recycle()
            scaled
        } else {
            original
        }
    }

    fun setExtractionMode(mode: ExtractionMode) {
        _uiState.update { it.copy(extractionMode = mode) }
        scheduleMaskRecalculation()
    }

    fun updateConfig(update: (WallpaperConfig) -> WallpaperConfig) {
        val oldConfig = uiState.value.config
        val newConfig = update(oldConfig)
        _uiState.update { it.copy(config = newConfig) }
        
        if (oldConfig.fontFamily != newConfig.fontFamily ||
            oldConfig.fontSize != newConfig.fontSize ||
            oldConfig.fontColor != newConfig.fontColor ||
            oldConfig.fontThickness != newConfig.fontThickness ||
            oldConfig.letterSpacing != newConfig.letterSpacing ||
            oldConfig.lineSpacing != newConfig.lineSpacing ||
            oldConfig.clockHeightScale != newConfig.clockHeightScale ||
            oldConfig.clockMode != newConfig.clockMode) {
            updateTimeBitmap()
        }
        
        requestPreviewUpdate()

        if (oldConfig.saliencyThreshold != newConfig.saliencyThreshold ||
            oldConfig.saliencyFeatherRadius != newConfig.saliencyFeatherRadius ||
            oldConfig.mlKitFeatherRadius != newConfig.mlKitFeatherRadius ||
            oldConfig.deepLabTargetClassIndex != newConfig.deepLabTargetClassIndex) {
            scheduleMaskRecalculation()
        }
    }

    private fun scheduleMaskRecalculation() {
        maskUpdateJob?.cancel()
        maskUpdateJob = viewModelScope.launch(Dispatchers.Default) {
            delay(500)
            performExtraction(uiState.value.extractionMode)
        }
    }

    private suspend fun performExtraction(mode: ExtractionMode) {
        val base = synchronized(renderLock) { previewBaseBitmap } ?: return
        val config = uiState.value.config
        
        withContext(Dispatchers.IO) {
            _uiState.update { it.copy(isExtracting = true, statusText = mode.statusLabel) }
            
            val mask: Bitmap? = when (mode) {
                ExtractionMode.SELFIE_AI, ExtractionMode.DEEPLAB_V3_MOBILENET_V2 -> {
                    val aiMask = getAiMask(mode, base)
                    if (aiMask != null) {
                        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                        NativeLib.extractMaskNative(base, aiMask.buffer, aiMask.width, aiMask.height, out)
                        out
                    } else null
                }
                ExtractionMode.EDGE -> {
                    val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                    NativeLib.extractEdgesNative(base, out, 0.1f)
                    out
                }
                ExtractionMode.HYBRID_DEPTH -> {
                    val aiMask = getAiMask(ExtractionMode.SELFIE_AI, base)
                    if (aiMask != null) {
                        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                        NativeLib.extractHybridMaskNative(base, aiMask.buffer, aiMask.width, aiMask.height, out, 0.5f, 0.15f, 10)
                        out
                    } else null
                }
                ExtractionMode.SALIENCY_MATTE -> {
                    val aiMask = getAiMask(ExtractionMode.SELFIE_AI, base)
                    if (aiMask != null) {
                        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                        NativeLib.extractSaliencyMatteNative(base, aiMask.buffer, aiMask.width, aiMask.height, out, config.saliencyThreshold, config.saliencyFeatherRadius, config.saliencyCleanupRadius, config.saliencyEdgeLock)
                        out
                    } else null
                }
                ExtractionMode.MLKIT_SUBJECT -> {
                    val aiMask = getAiMask(mode, base)
                    if (aiMask != null) {
                        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                        // ML Kit high-quality softening logic:
                        NativeLib.extractSaliencyMatteNative(base, aiMask.buffer, aiMask.width, aiMask.height, out, 0.5f, config.mlKitFeatherRadius, 2, 0.5f)
                        out
                    } else null
                }
            }
            
            synchronized(renderLock) {
                previewMaskBitmap?.recycle()
                previewMaskBitmap = mask
            }
            _uiState.update { it.copy(isExtracting = false, statusText = "Status: ${mode.toastLabel}") }
            requestPreviewUpdate()
        }
    }

    private suspend fun getAiMask(mode: ExtractionMode, base: Bitmap): TfliteSelfieSegmenter.SegmentationMask? {
        if (cachedAiMask != null && cachedAiMode == mode) return cachedAiMask
        val segmenter = when (mode) {
            ExtractionMode.SELFIE_AI -> getSelfieSegmenter()
            ExtractionMode.DEEPLAB_V3_MOBILENET_V2 -> getDeepLabSegmenter()
            ExtractionMode.MLKIT_SUBJECT -> getMlKitSegmenter()
            else -> getSelfieSegmenter()
        }
        val mask = segmenter.segment(base)
        cachedAiMask = mask
        cachedAiMode = mode
        return mask
    }

    private fun getSelfieSegmenter(): TfliteSelfieSegmenter = selfieSegmenter ?: TfliteSelfieSegmenter(context, TfliteSelfieSegmenter.Config.selfie()).also { selfieSegmenter = it }
    private fun getDeepLabSegmenter(): TfliteSelfieSegmenter = deepLabSegmenter ?: TfliteSelfieSegmenter(context, TfliteSelfieSegmenter.Config.deepLabV3MobileNetV2()).also { deepLabSegmenter = it }
    private fun getMlKitSegmenter(): TfliteSelfieSegmenter = mlKitSegmenter ?: TfliteSelfieSegmenter(context, TfliteSelfieSegmenter.Config.mlKitSubject()).also { mlKitSegmenter = it }

    private fun updateTimeBitmap() {
        val config = uiState.value.config
        val format = if (config.use24HourFormat) "HH:mm" else "hh:mm"
        val currentTime = SimpleDateFormat(format, Locale.getDefault()).format(Date())

        val baseSize = config.fontSize
        val stretch = config.clockHeightScale

        textPaint.textSize = baseSize * stretch
        textPaint.textScaleX = 1.0f / stretch
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
            val hourW = textPaint.measureText(currentTime.substringBefore(":"))
            val colonW = textPaint.measureText(":")
            val minuteW = textPaint.measureText(currentTime.substringAfter(":"))
            val totalW = hourW + colonW + minuteW
            val width = (totalW + 60).toInt(); val height = (lineHeight + 60).toInt()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawText(currentTime, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
            
            synchronized(renderLock) {
                previewTimeBitmap?.recycle()
                previewTimeBitmap = bitmap
            }
        } else {
            val lines = currentTime.split(":")
            var maxWidth = 0f
            for (line in lines) maxWidth = maxOf(maxWidth, textPaint.measureText(line))
            val spacing = (20f + config.lineSpacing)
            val totalHeight = (lineHeight * lines.size) + spacing
            val width = (maxWidth + 60).toInt(); val height = (totalHeight + 60).toInt()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            var currentY = 30f + lineHeight / 2f
            for (i in lines.indices) {
                canvas.drawText(lines[i], width / 2f, currentY - (fm.ascent + fm.descent) / 2f, textPaint)
                currentY += lineHeight + if (i == 0) spacing else 0f
            }
            
            synchronized(renderLock) {
                previewTimeBitmap?.recycle()
                previewTimeBitmap = bitmap
            }
        }
    }

    fun autoDetectColor() {
        val base = synchronized(renderLock) { previewBaseBitmap } ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val config = uiState.value.config
            val centerX = (base.width * config.clockX).toInt().coerceIn(0, base.width - 1)
            val centerY = (base.height * config.clockY).toInt().coerceIn(0, base.height - 1)
            var r = 0; var g = 0; var b = 0
            val samples = 5
            for (i in -2..2) {
                val p = base.getPixel((centerX + i).coerceIn(0, base.width - 1), centerY)
                r += android.graphics.Color.red(p); g += android.graphics.Color.green(p); b += android.graphics.Color.blue(p)
            }
            val avgR = r / samples; val avgG = g / samples; val avgB = b / samples
            val detected = android.graphics.Color.rgb(255 - avgR, 255 - avgG, 255 - avgB)
            withContext(Dispatchers.Main) { updateConfig { it.copy(fontColor = detected) } }
        }
    }

    fun saveConfig(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { updateTimeBitmap() }
            val preview = generatePreview()
            synchronized(renderLock) {
                configManager.saveBundle(uiState.value.config, previewBaseBitmap, previewMaskBitmap, preview)
            }
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
    
    fun applyConfig(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { updateTimeBitmap() }
            val preview = generatePreview()
            synchronized(renderLock) {
                val id = configManager.saveBundle(uiState.value.config, previewBaseBitmap, previewMaskBitmap, preview)
                configManager.setActiveConfigId(id)
            }
            withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, CustomDepthWallpaperService::class.java))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                onComplete()
            }
        }
    }

    private fun generatePreview(): Bitmap? {
        val config = uiState.value.config
        val metrics = context.resources.displayMetrics
        val out = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        
        synchronized(renderLock) {
            val base = previewBaseBitmap ?: return null
            val matrix = Matrix()
            matrix.postScale(config.wallpaperScale, config.wallpaperScale)
            matrix.postTranslate(config.wallpaperOffsetX, config.wallpaperOffsetY)
            canvas.drawBitmap(base, matrix, null)
            previewTimeBitmap?.let {
                val tx = out.width * config.clockX - it.width / 2f
                val ty = out.height * config.clockY - it.height / 2f
                canvas.drawBitmap(it, tx, ty, null)
            }
            previewMaskBitmap?.let { canvas.drawBitmap(it, matrix, null) }
        }
        
        val thumb = Bitmap.createScaledBitmap(out, out.width / 2, out.height / 2, true)
        out.recycle()
        return thumb
    }

    fun onSurfaceCreated(surface: Surface) {
        lastSurface = surface
        requestPreviewUpdate()
    }

    /**
     * Performance: Async non-blocking render queue.
     * Emits a request to the dedicated render loop.
     */
    fun requestPreviewUpdate() {
        viewModelScope.launch {
            renderChannel.send(Unit)
        }
    }

    private fun executeRenderFrame() {
        val surface = lastSurface ?: return
        if (surface.isValid) {
            val config = uiState.value.config
            synchronized(renderLock) {
                NativeLib.renderNativeFrame(
                    surface, "", previewBaseBitmap, previewMaskBitmap, previewTimeBitmap,
                    config.clockX, config.clockY, config.wallpaperScale, 
                    config.wallpaperOffsetX, config.wallpaperOffsetY
                )
            }
        }
    }

    fun handleClockDragDelta(dx: Float, dy: Float, viewWidth: Float, viewHeight: Float) {
        _uiState.update { it.copy(config = it.config.copy(
            clockX = (it.config.clockX + dx / viewWidth).coerceIn(0f, 1f),
            clockY = (it.config.clockY + dy / viewHeight).coerceIn(0f, 1f)
        )) }
        requestPreviewUpdate()
    }

    fun handleWallpaperTransform(dx: Float, dy: Float, zoom: Float, viewWidth: Float, viewHeight: Float) {
        _uiState.update {
            val newScale = (it.config.wallpaperScale * zoom).coerceIn(0.5f, 10.0f)
            it.copy(config = it.config.copy(
                wallpaperScale = newScale,
                wallpaperOffsetX = (it.config.wallpaperOffsetX + dx).coerceIn(-viewWidth * 2, viewWidth * 2),
                wallpaperOffsetY = (it.config.wallpaperOffsetY + dy).coerceIn(-viewHeight * 2, viewHeight * 2)
            ))
        }
        requestPreviewUpdate()
    }

    fun updateInteraction(update: (InteractionMode) -> InteractionMode) { 
        _uiState.update { it.copy(interactionMode = update(it.interactionMode)) } 
    }

    override fun onCleared() { 
        selfieSegmenter?.close()
        deepLabSegmenter?.close()
        mlKitSegmenter?.close()
        synchronized(renderLock) {
            previewBaseBitmap?.recycle()
            previewMaskBitmap?.recycle()
            previewTimeBitmap?.recycle()
        }
    }
}

enum class InteractionMode { CLOCK, WALLPAPER }
data class EditorUiState(val config: WallpaperConfig = WallpaperConfig(), val isExtracting: Boolean = false, val statusText: String = "Status: Select an image", val extractionMode: ExtractionMode = ExtractionMode.MLKIT_SUBJECT, val interactionMode: InteractionMode = InteractionMode.CLOCK)
