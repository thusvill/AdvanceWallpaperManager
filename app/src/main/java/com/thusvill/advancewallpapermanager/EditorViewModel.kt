/*
 * Copyright (C) 2026 Advance Wallpaper Manager
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class EditorViewModel(application: Application, private val configManager: ConfigManager) : AndroidViewModel(application) {
    private val context = application
    private val TAG = "EditorViewModel"

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()

    private var selfieSegmenter: TfliteSelfieSegmenter? = null
    private var deepLabSegmenter: TfliteSelfieSegmenter? = null
    private var mlKitSegmenter: TfliteSelfieSegmenter? = null
    
    private var maskUpdateJob: Job? = null
    
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

    private val renderChannel = Channel<Unit>(Channel.CONFLATED)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            var lastRenderTime = 0L
            for (req in renderChannel) {
                val now = System.currentTimeMillis()
                val delta = now - lastRenderTime
                if (delta < 16) delay(16 - delta)
                executeRenderFrame()
                lastRenderTime = System.currentTimeMillis()
            }
        }
        refreshCustomFonts()
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
                previewBaseBitmap?.recycle(); previewMaskBitmap?.recycle()
                previewBaseBitmap = configManager.loadBundleBitmap(configId, "base")
                previewMaskBitmap = configManager.loadBundleBitmap(configId, "mask")
            }

            // AUTO-IMPORT & RECOVERY LOGIC
            if (config.isCustomFont) {
                val fontName = config.customFontName
                val libFile = configManager.getCustomFontFile(fontName)
                
                // If font is missing from library but exists in the bundle, import it permanently
                if (!libFile.exists()) {
                    val fontData = configManager.loadBundleFile(configId, "font.ttf")
                    fontData?.let {
                        FileOutputStream(libFile).use { it.write(fontData) }
                        refreshCustomFonts()
                        Log.i(TAG, "Auto-imported missing font '$fontName' from config bundle.")
                    }
                }
                
                // Ensure temp preview file exists for the current session
                val tempFile = configManager.getFontTempFile(configId)
                if (!tempFile.exists() && libFile.exists()) {
                    libFile.inputStream().use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            
            updateTimeBitmap()
            requestPreviewUpdate()
        }
    }

    fun setSourceImage(uri: Uri) {
        cachedAiMask = null; cachedAiMode = null
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = loadRaw(uri)
            val imageName = configManager.getFileName(uri)?.substringBeforeLast('.') ?: ""
            synchronized(renderLock) {
                previewBaseBitmap?.recycle(); previewMaskBitmap?.recycle()
                previewBaseBitmap = bitmap
                previewMaskBitmap = null
            }
            if (uiState.value.config.displayName.isBlank() && imageName.isNotBlank()) {
                _uiState.update { it.copy(config = it.config.copy(displayName = imageName)) }
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
        val screenW = metrics.widthPixels.toFloat(); val screenH = metrics.heightPixels.toFloat()
        val maxDim = 4096f
        var scale = 1.0f
        if (original.width > maxDim || original.height > maxDim) {
            scale = maxDim / Math.max(original.width.toFloat(), original.height.toFloat())
        }
        val coverScale = Math.max(screenW / (original.width * scale), screenH / (original.height * scale))
        val finalScale = scale * coverScale
        val offsetX = (screenW - original.width * finalScale) / 2f
        val offsetY = (screenH - original.height * finalScale) / 2f
        _uiState.update { it.copy(config = it.config.copy(wallpaperScale = finalScale, wallpaperOffsetX = offsetX, wallpaperOffsetY = offsetY, wallpaperRotation = 0f)) }
        return if (scale < 1.0f) {
            val scaled = Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true)
            original.recycle(); scaled
        } else original
    }

    fun setExtractionMode(mode: ExtractionMode) {
        _uiState.update { it.copy(extractionMode = mode) }
        scheduleMaskRecalculation()
    }

    fun importFont(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val imported = configManager.importFont(uri)
            if (imported.isNotEmpty()) {
                refreshCustomFonts()
                // Auto-select the first imported font if only one
                if (imported.size == 1) setCustomFont(imported[0])
            }
        }
    }

    fun refreshCustomFonts() {
        val fonts = configManager.getAvailableCustomFonts()
        _uiState.update { it.copy(availableCustomFonts = fonts) }
    }

    fun setCustomFont(fontName: String) {
        _uiState.update { it.copy(config = it.config.copy(
            isCustomFont = true,
            customFontName = fontName,
            fontFamily = ""
        )) }
        updateTimeBitmap()
        requestPreviewUpdate()
    }

    fun updateConfig(update: (WallpaperConfig) -> WallpaperConfig) {
        val oldConfig = uiState.value.config
        val newConfig = update(oldConfig)
        val finalConfig = if (newConfig.fontFamily != oldConfig.fontFamily && newConfig.fontFamily.isNotEmpty()) {
            newConfig.copy(isCustomFont = false, customFontName = "")
        } else newConfig
        _uiState.update { it.copy(config = finalConfig) }
        if (oldConfig.fontFamily != finalConfig.fontFamily || oldConfig.fontSize != finalConfig.fontSize ||
            oldConfig.fontColor != finalConfig.fontColor || oldConfig.fontThickness != finalConfig.fontThickness ||
            oldConfig.letterSpacing != finalConfig.letterSpacing || oldConfig.lineSpacing != finalConfig.lineSpacing ||
            oldConfig.clockHeightScale != finalConfig.clockHeightScale || oldConfig.use24HourFormat != finalConfig.use24HourFormat ||
            oldConfig.showAmPm != finalConfig.showAmPm || oldConfig.clockMode != finalConfig.clockMode) {
            updateTimeBitmap()
        }
        requestPreviewUpdate()
        if (oldConfig.saliencyThreshold != finalConfig.saliencyThreshold || oldConfig.saliencyFeatherRadius != finalConfig.saliencyFeatherRadius ||
            oldConfig.mlKitFeatherRadius != finalConfig.mlKitFeatherRadius || oldConfig.mlKitThreshold != finalConfig.mlKitThreshold ||
            oldConfig.mlKitExpansionPx != finalConfig.mlKitExpansionPx || oldConfig.deepLabTargetClassIndex != finalConfig.deepLabTargetClassIndex) {
            scheduleMaskRecalculation()
        }
    }

    fun setDisplayName(name: String) {
        _uiState.update { it.copy(config = it.config.copy(displayName = name)) }
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
                        NativeLib.extractMlKitSubjectNative(base, aiMask.buffer, aiMask.width, aiMask.height, out, config.mlKitThreshold, config.mlKitFeatherRadius, config.mlKitExpansionPx)
                        out
                    } else null
                }
            }
            synchronized(renderLock) { previewMaskBitmap?.recycle(); previewMaskBitmap = mask }
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
        var mask = segmenter.segment(base)
        if (mask == null && mode != ExtractionMode.MLKIT_SUBJECT) {
            Log.w(TAG, "TFLite model extraction returned null for $mode. Falling back to ML Kit Subject Segmenter.")
            mask = getMlKitSegmenter().segment(base)
        }
        if (mask != null) {
            cachedAiMask = mask
            cachedAiMode = mode
        }
        return mask
    }

    private fun getSelfieSegmenter() = selfieSegmenter ?: TfliteSelfieSegmenter(context, TfliteSelfieSegmenter.Config.selfie()).also { selfieSegmenter = it }
    private fun getDeepLabSegmenter() = deepLabSegmenter ?: TfliteSelfieSegmenter(context, TfliteSelfieSegmenter.Config.deepLabV3MobileNetV2()).also { deepLabSegmenter = it }
    private fun getMlKitSegmenter() = mlKitSegmenter ?: TfliteSelfieSegmenter(context, TfliteSelfieSegmenter.Config.mlKitSubject()).also { mlKitSegmenter = it }

    private fun updateTimeBitmap() {
        val config = uiState.value.config
        val format = if (config.use24HourFormat) "HH:mm" else if (config.showAmPm) "hh:mm a" else "hh:mm"
        val currentTime = SimpleDateFormat(format, Locale.getDefault()).format(Date())
        val baseSize = config.fontSize; val stretch = config.clockHeightScale
        textPaint.textSize = baseSize; textPaint.color = config.fontColor
        try {
            if (config.isCustomFont) {
                // Try library first, then bundle temp
                var fontFile = configManager.getCustomFontFile(config.customFontName)
                if (!fontFile.exists()) fontFile = configManager.getFontTempFile(config.id)
                if (fontFile.exists()) textPaint.typeface = Typeface.createFromFile(fontFile)
                else textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } else if (config.fontFamily.isNotEmpty()) {
                val fontFile = File("/system/fonts", "${config.fontFamily}.ttf")
                textPaint.typeface = if (fontFile.exists()) Typeface.createFromFile(fontFile) else Typeface.create(config.fontFamily, Typeface.BOLD)
            } else textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        } catch (e: Exception) { textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        textPaint.letterSpacing = config.letterSpacing
        if (config.fontThickness > 0) { textPaint.style = Paint.Style.FILL_AND_STROKE; textPaint.strokeWidth = config.fontThickness }
        else textPaint.style = Paint.Style.FILL
        val fm = textPaint.fontMetrics; val lineHeight = (fm.descent - fm.ascent); val padding = 60f
        if (config.clockMode == ClockMode.HORIZONTAL) {
            val totalW = textPaint.measureText(currentTime)
            val width = (totalW + padding).toInt(); val height = (lineHeight * stretch + padding).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
            canvas.save(); canvas.translate(width / 2f, height / 2f); canvas.scale(1.0f, stretch)
            canvas.drawText(currentTime, 0f, -(fm.ascent + fm.descent) / 2f, textPaint); canvas.restore()
            synchronized(renderLock) { previewTimeBitmap?.recycle(); previewTimeBitmap = bitmap }
        } else {
            val rawTokens = currentTime.split(" "); val finalLines = mutableListOf<String>()
            for (token in rawTokens) {
                if (token.contains(":")) { finalLines.add(token.substringBefore(":")); finalLines.add(token.substringAfter(":")) }
                else finalLines.add(token)
            }
            var maxWidth = 0f; for (line in finalLines) maxWidth = maxOf(maxWidth, textPaint.measureText(line))
            val spacing = config.lineSpacing; val stretchedLineHeight = lineHeight * stretch
            val totalHeight = (stretchedLineHeight * finalLines.size) + (spacing * (finalLines.size - 1))
            val width = (maxWidth + padding).toInt(); val height = (Math.max(100f, totalHeight) + padding).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
            var currentY = padding / 2f + stretchedLineHeight / 2f
            for (i in finalLines.indices) {
                canvas.save(); canvas.translate(width / 2f, currentY); canvas.scale(1.0f, stretch)
                canvas.drawText(finalLines[i], 0f, -(fm.ascent + fm.descent) / 2f, textPaint); canvas.restore()
                currentY += stretchedLineHeight + spacing
            }
            synchronized(renderLock) { previewTimeBitmap?.recycle(); previewTimeBitmap = bitmap }
        }
    }

    fun autoDetectColor() {
        val base = synchronized(renderLock) { previewBaseBitmap } ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val config = uiState.value.config
            val centerX = (base.width * config.clockX).toInt().coerceIn(0, base.width - 1)
            val centerY = (base.height * config.clockY).toInt().coerceIn(0, base.height - 1)
            var r = 0; var g = 0; var b = 0
            for (i in -2..2) {
                val p = base.getPixel((centerX + i).coerceIn(0, base.width - 1), centerY)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p)
            }
            val detected = Color.rgb(255 - r / 5, 255 - g / 5, 255 - b / 5)
            withContext(Dispatchers.Main) { updateConfig { it.copy(fontColor = detected) } }
        }
    }

    fun saveConfig(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { updateTimeBitmap() }
            val preview = generatePreview()
            synchronized(renderLock) { configManager.saveBundle(uiState.value.config, previewBaseBitmap, previewMaskBitmap, preview) }
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    fun deleteConfig(onComplete: () -> Unit) {
        val id = uiState.value.config.id
        if (id != "default") {
            viewModelScope.launch(Dispatchers.IO) { configManager.deleteConfig(id); withContext(Dispatchers.Main) { onComplete() } }
        } else onComplete()
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
        val config = uiState.value.config; val metrics = context.resources.displayMetrics
        
        // DOWNSCALE: Create a lower-res preview for the gallery (e.g., half-screen)
        val targetWidth = metrics.widthPixels / 2
        val targetHeight = metrics.heightPixels / 2
        
        val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out); canvas.drawColor(Color.BLACK)
        
        // Scaling factor for coordinates
        val scaleDown = 0.5f
        
        synchronized(renderLock) {
            val base = previewBaseBitmap ?: return null
            val baseW = base.width.toFloat()
            val baseH = base.height.toFloat()
            
            val matrix = Matrix()
            // Apply wallpaper scale, rotation around scaled center, then translation for preview
            matrix.postScale(config.wallpaperScale * scaleDown, config.wallpaperScale * scaleDown)
            matrix.postRotate(
                config.wallpaperRotation,
                (baseW * config.wallpaperScale / 2f) * scaleDown,
                (baseH * config.wallpaperScale / 2f) * scaleDown
            )
            matrix.postTranslate(config.wallpaperOffsetX * scaleDown, config.wallpaperOffsetY * scaleDown)
            canvas.drawBitmap(base, matrix, null)
            
            previewTimeBitmap?.let {
                val tx = (metrics.widthPixels * config.clockX - it.width / 2f) * scaleDown
                val ty = (metrics.heightPixels * config.clockY - it.height / 2f) * scaleDown
                
                val clockMatrix = Matrix()
                clockMatrix.postRotate(config.clockRotation, it.width / 2f, it.height / 2f)
                clockMatrix.postScale(scaleDown, scaleDown)
                clockMatrix.postTranslate(tx, ty)
                canvas.drawBitmap(it, clockMatrix, null)
            }
            
            previewMaskBitmap?.let {
                val maskMatrix = Matrix()
                maskMatrix.postScale(config.wallpaperScale * scaleDown, config.wallpaperScale * scaleDown)
                maskMatrix.postRotate(
                    config.wallpaperRotation,
                    (baseW * config.wallpaperScale / 2f) * scaleDown,
                    (baseH * config.wallpaperScale / 2f) * scaleDown
                )
                maskMatrix.postTranslate(config.wallpaperOffsetX * scaleDown, config.wallpaperOffsetY * scaleDown)
                canvas.drawBitmap(it, maskMatrix, null)
            }
        }
        
        return out
    }

    fun onSurfaceCreated(surface: Surface) { lastSurface = surface; requestPreviewUpdate() }
    fun requestPreviewUpdate() { viewModelScope.launch { renderChannel.send(Unit) } }
    private fun executeRenderFrame() {
        val surface = lastSurface ?: return
        if (surface.isValid) {
            val config = uiState.value.config
            synchronized(renderLock) {
                NativeLib.renderNativeFrame(surface, "", previewBaseBitmap, previewMaskBitmap, previewTimeBitmap, config.clockX, config.clockY, config.wallpaperScale, config.wallpaperOffsetX, config.wallpaperOffsetY, config.wallpaperRotation, config.clockRotation, config.clockDepth)
            }
        }
    }

    fun handleClockTransform(dx: Float, dy: Float, zoom: Float, rotationChange: Float, viewWidth: Float, viewHeight: Float) {
        val oldFontSize = uiState.value.config.fontSize
        val newFontSize = (oldFontSize * zoom).coerceAtLeast(10f)
        val fontChanged = abs(oldFontSize - newFontSize) > 0.1f

        _uiState.update {
            val rawRotation = it.config.clockRotation + rotationChange
            val newRotation = (rawRotation % 360f + 360f) % 360f
            val newClockX = (it.config.clockX + dx / viewWidth).coerceIn(0f, 1f)
            val newClockY = (it.config.clockY + dy / viewHeight).coerceIn(0f, 1f)
            it.copy(config = it.config.copy(
                clockX = newClockX,
                clockY = newClockY,
                fontSize = newFontSize,
                clockRotation = newRotation
            ))
        }
        if (fontChanged) {
            updateTimeBitmap()
        }
        requestPreviewUpdate()
    }

    fun handleWallpaperTransform(dx: Float, dy: Float, zoom: Float, rotationChange: Float, viewWidth: Float, viewHeight: Float) {
        _uiState.update {
            val newScale = (it.config.wallpaperScale * zoom).coerceAtLeast(0.01f)
            val rawRotation = it.config.wallpaperRotation + rotationChange
            val newRotation = (rawRotation % 360f + 360f) % 360f
            it.copy(config = it.config.copy(
                wallpaperScale = newScale,
                wallpaperRotation = newRotation,
                wallpaperOffsetX = (it.config.wallpaperOffsetX + dx).coerceIn(-viewWidth * 2, viewWidth * 2),
                wallpaperOffsetY = (it.config.wallpaperOffsetY + dy).coerceIn(-viewHeight * 2, viewHeight * 2)
            ))
        }
        requestPreviewUpdate()
    }

    fun setWallpaperRotation(rotation: Float) {
        val normalized = (rotation % 360f + 360f) % 360f
        updateConfig { it.copy(wallpaperRotation = normalized) }
    }

    fun setClockRotation(rotation: Float) {
        val normalized = (rotation % 360f + 360f) % 360f
        updateConfig { it.copy(clockRotation = normalized) }
    }

    fun updateInteraction(update: (InteractionMode) -> InteractionMode) { _uiState.update { it.copy(interactionMode = update(it.interactionMode)) } }
    override fun onCleared() { 
        selfieSegmenter?.close(); deepLabSegmenter?.close(); mlKitSegmenter?.close()
        synchronized(renderLock) { previewBaseBitmap?.recycle(); previewMaskBitmap?.recycle(); previewTimeBitmap?.recycle() }
    }
}

enum class InteractionMode { CLOCK, WALLPAPER }
data class EditorUiState(val config: WallpaperConfig = WallpaperConfig(), val isExtracting: Boolean = false, val statusText: String = "Status: Select an image", val extractionMode: ExtractionMode = ExtractionMode.MLKIT_SUBJECT, val interactionMode: InteractionMode = InteractionMode.CLOCK, val availableCustomFonts: List<String> = emptyList())
