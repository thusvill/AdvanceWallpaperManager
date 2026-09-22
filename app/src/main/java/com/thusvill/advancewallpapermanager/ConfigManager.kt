package com.thusvill.advancewallpapermanager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages Depth Wallpaper (.dwp) binary bundles and Custom Font Library.
 */
class ConfigManager(private val context: Context) {
    private val gson = Gson()
    private val TAG = "ConfigManager"
    
    private val configsDir: File by lazy {
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val appDir = File(baseDir, "AdvanceWallpaperManager/configs")
        if (!appDir.exists()) appDir.mkdirs()
        appDir
    }

    private val customFontsDir: File by lazy {
        val dir = File(context.filesDir, "custom_fonts")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    fun importFont(uri: Uri): List<String> {
        val importedNames = mutableListOf<String>()
        val fileName = getFileName(uri) ?: "unknown"
        try {
            if (fileName.lowercase().endsWith(".zip")) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && (entry.name.lowercase().endsWith(".ttf") || entry.name.lowercase().endsWith(".otf"))) {
                                val fontName = File(entry.name).name
                                val destFile = File(customFontsDir, fontName)
                                destFile.outputStream().use { output -> zis.copyTo(output) }
                                importedNames.add(fontName)
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
            } else if (fileName.lowercase().endsWith(".ttf") || fileName.lowercase().endsWith(".otf")) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val destFile = File(customFontsDir, fileName)
                    destFile.outputStream().use { output -> input.copyTo(output) }
                    importedNames.add(fileName)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Failed to import font $fileName", e) }
        return importedNames
    }

    fun getAvailableCustomFonts(): List<String> = customFontsDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
    fun getCustomFontFile(name: String): File = File(customFontsDir, name)

    fun importConfig(uri: Uri): Boolean {
        val fileName = getFileName(uri) ?: return false
        if (!fileName.lowercase().endsWith(".dwp")) return false
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val destFile = File(configsDir, fileName)
                destFile.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
        } catch (e: Exception) { Log.e(TAG, "Failed to import config $fileName", e); false }
    }

    private fun getFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    fun saveBundle(config: WallpaperConfig, baseBitmap: Bitmap?, maskBitmap: Bitmap?, previewBitmap: Bitmap?, customFontName: String? = null): String {
        // ID-FILENAME SYNC: Ensure internal ID matches the file being written
        val id = if (config.id == "default" || config.id.isEmpty()) UUID.randomUUID().toString() else config.id
        config.id = id
        val bundleFile = File(configsDir, "$id.dwp")
        try {
            val fos = FileOutputStream(bundleFile)
            ZipOutputStream(fos).use { zos ->
                zos.putNextEntry(ZipEntry("config.json"))
                zos.write(gson.toJson(config).toByteArray())
                zos.closeEntry()
                baseBitmap?.let { saveImageToZip(it, "base.png", zos) }
                maskBitmap?.let { saveImageToZip(it, "mask.png", zos) }
                previewBitmap?.let { saveImageToZip(it, "preview.png", zos) }
                if (config.isCustomFont) {
                    val fontToEmbed = customFontName ?: config.customFontName
                    if (fontToEmbed.isNotEmpty()) {
                        val fontFile = getCustomFontFile(fontToEmbed)
                        if (fontFile.exists()) {
                            zos.putNextEntry(ZipEntry("font.ttf"))
                            fontFile.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        } else {
                            loadBundleFile(id, "font.ttf")?.let { data ->
                                zos.putNextEntry(ZipEntry("font.ttf"))
                                zos.write(data)
                                zos.closeEntry()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Failed to save bundle $id", e) }
        return id
    }

    private fun saveImageToZip(bitmap: Bitmap, name: String, zos: ZipOutputStream) {
        zos.putNextEntry(ZipEntry(name))
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, zos)
        zos.closeEntry()
    }

    /**
     * FLEXIBLE BUNDLE LOADER: Forces internal ID to match the actual filename.
     */
    fun loadBundleConfig(id: String): WallpaperConfig? {
        val file = File(configsDir, "$id.dwp")
        if (!file.exists()) return null
        return try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.lowercase().endsWith("config.json")) {
                        val reader = InputStreamReader(zis)
                        val config = gson.fromJson(reader, WallpaperConfig::class.java)
                        // STABILITY FIX: Always overwrite ID with the filename to prevent render failures
                        if (config != null) config.id = id 
                        return config
                    }
                    entry = zis.nextEntry
                }
                null
            }
        } catch (e: Exception) { Log.e(TAG, "Error loading config from bundle $id", e); null }
    }

    fun loadBundleBitmap(id: String, type: String): Bitmap? {
        val file = File(configsDir, "$id.dwp")
        if (!file.exists()) return null
        val target = type.lowercase() + ".png" // Robust matching
        
        return try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    // Match by lowercase filename suffix (handles desktop folder variations)
                    if (entry.name.lowercase().endsWith(target)) {
                        val bitmap = BitmapFactory.decodeStream(zis)
                        // If it's a mask and already has alpha, use it directly (Desktop App colored mask)
                        // Otherwise, if it's opaque grayscale, convert it.
                        return if (type == "mask" && bitmap != null) {
                            if (bitmap.hasAlpha() && !isFullyOpaque(bitmap)) bitmap else ensureAlphaMask(bitmap)
                        } else bitmap
                    }
                    entry = zis.nextEntry
                }
                null
            }
        } catch (e: Exception) { Log.e(TAG, "Error loading $type from bundle $id", e); null }
    }

    /**
     * Converts opaque/grayscale images (from OpenCV) to ARGB alpha masks.
     */
    private fun ensureAlphaMask(src: Bitmap): Bitmap {
        if (src.hasAlpha() && !isFullyOpaque(src)) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val p = src.getPixel(x, y)
                val lum = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
                out.setPixel(x, y, Color.argb(lum, Color.red(p), Color.green(p), Color.blue(p)))
            }
        }
        src.recycle()
        return out
    }

    private fun isFullyOpaque(bitmap: Bitmap): Boolean {
        for (i in 0 until 10) {
            val x = (Math.random() * bitmap.width).toInt()
            val y = (Math.random() * bitmap.height).toInt()
            if (Color.alpha(bitmap.getPixel(x, y)) < 255) return false
        }
        return true
    }

    fun loadBundleFile(id: String, fileName: String): ByteArray? {
        val file = File(configsDir, "$id.dwp")
        if (!file.exists()) return null
        return try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.lowercase().endsWith(fileName.lowercase())) return zis.readBytes()
                    entry = zis.nextEntry
                }
                null
            }
        } catch (e: Exception) { Log.e(TAG, "Error loading $fileName from bundle $id", e); null }
    }

    fun getFontTempFile(id: String): File {
        val tempDir = File(context.cacheDir, "fonts")
        if (!tempDir.exists()) tempDir.mkdirs()
        return File(tempDir, "$id.ttf")
    }

    fun loadAllConfigs(): List<WallpaperConfig> {
        val list = mutableListOf<WallpaperConfig>()
        val files = configsDir.listFiles() ?: return emptyList()
        files.filter { it.name.endsWith(".dwp") }.forEach { file ->
            try {
                loadBundleConfig(file.nameWithoutExtension)?.let { list.add(it) }
            } catch (e: Exception) { Log.e(TAG, "Corrupt bundle: ${file.name}", e) }
        }
        return list
    }

    fun deleteConfig(id: String) {
        val file = File(configsDir, "$id.dwp")
        if (file.exists()) file.delete()
        val tempFont = getFontTempFile(id)
        if (tempFont.exists()) tempFont.delete()
    }

    fun getActiveConfigId(): String? = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).getString("active_config_id", null)
    fun setActiveConfigId(id: String) {
        context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).edit().putString("active_config_id", id).apply()
        val intent = Intent("com.thusvill.advancewallpapermanager.UPDATE_CONFIG")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
}
