package com.thusvill.advancewallpapermanager

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.thusvill.advancewallpapermanager.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE) }
    
    private var currentConfig = WallpaperConfig()
    private var tfliteSegmenter: TfliteSelfieSegmenter? = null

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

    private val selectImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            processSelectedImage(it, useAI = true)
        }
    }

    private val edgeDetectionLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            processSelectedImage(it, useAI = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        System.loadLibrary("advancewallpapermanager")
        
        tfliteSegmenter = TfliteSelfieSegmenter(this)
        tfliteSegmenter?.initialize { success ->
            if (!success) {
                Toast.makeText(this, "Failed to init TFLite", Toast.LENGTH_LONG).show()
            }
        }

        loadConfig()
        initUi()

        binding.btnSelectImage.setOnClickListener { selectImageLauncher.launch("image/*") }
        binding.btnExtractEdges.setOnClickListener { edgeDetectionLauncher.launch("image/*") }

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

    private fun initUi() {
        binding.sliderX.value = currentConfig.clockX
        binding.sliderY.value = currentConfig.clockY
        binding.sliderSize.value = currentConfig.fontSize
        binding.sliderThickness.value = currentConfig.fontThickness

        binding.sliderX.addOnChangeListener { _, value, _ -> currentConfig.clockX = value; saveConfig(); notifyService() }
        binding.sliderY.addOnChangeListener { _, value, _ -> currentConfig.clockY = value; saveConfig(); notifyService() }
        binding.sliderSize.addOnChangeListener { _, value, _ -> currentConfig.fontSize = value; saveConfig(); notifyService() }
        binding.sliderThickness.addOnChangeListener { _, value, _ -> currentConfig.fontThickness = value; saveConfig(); notifyService() }
        
        updateStatus()
    }

    private fun notifyService() {
        sendBroadcast(Intent("com.thusvill.advancewallpapermanager.UPDATE_CONFIG"))
    }

    private fun processSelectedImage(uri: Uri, useAI: Boolean) {
        binding.pbExtraction.visibility = View.VISIBLE
        binding.pbExtraction.isIndeterminate = true
        binding.btnSelectImage.isEnabled = false
        binding.btnExtractEdges.isEnabled = false
        binding.tvStatus.text = if (useAI) "Status: AI Extraction..." else "Status: Edge Detection..."

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
                if (useAI) {
                    val maskBuffer = tfliteSegmenter?.segment(bitmap) ?: throw Exception("TFLite Segmentation failed")
                    success = extractMaskNative(bitmap, maskBuffer, 256, 256, maskBitmap)
                } else {
                    success = extractEdgesNative(bitmap, maskBitmap, 0.1f) // 0.1f is a default threshold
                }
                
                if (success) {
                    FileOutputStream(maskFile).use { maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    currentConfig.foregroundMaskPath = maskFile.absolutePath
                    
                    withContext(Dispatchers.Main) {
                        updateStatus()
                        saveConfig()
                        notifyService()
                        binding.pbExtraction.visibility = View.GONE
                        binding.btnSelectImage.isEnabled = true
                        binding.btnExtractEdges.isEnabled = true
                        val type = if (useAI) "AI" else "Edge"
                        Toast.makeText(this@MainActivity, "Depth Mask Extracted ($type)!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    throw Exception("Extraction failed")
                }
                maskBitmap.recycle()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.pbExtraction.visibility = View.GONE
                    binding.btnSelectImage.isEnabled = true
                    binding.btnExtractEdges.isEnabled = true
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
    }

    private fun updateStatus() {
        val hasBase = currentConfig.baseImagePath.isNotEmpty() && File(currentConfig.baseImagePath).exists()
        val hasMask = currentConfig.foregroundMaskPath.isNotEmpty() && File(currentConfig.foregroundMaskPath).exists()
        binding.tvStatus.text = "Base: ${if (hasBase) "OK" else "None"}, Mask: ${if (hasMask) "OK" else "None"}"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        tfliteSegmenter?.close()
    }
}
