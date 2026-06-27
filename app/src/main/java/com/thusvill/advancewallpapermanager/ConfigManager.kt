package com.thusvill.advancewallpapermanager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    
    /**
     * PUBLIC STORAGE: Using Documents folder for user accessibility.
     * Note: Requires MANAGE_EXTERNAL_STORAGE on Android 11+ to see 'foreign' files.
     */
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

    /**
     * Imports a font file (.ttf, .otf) or a .zip bundle into the app's library.
     */
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
                    val fontName = fileName
                    val destFile = File(customFontsDir, fontName)
                    destFile.outputStream().use { output -> input.copyTo(output) }
                    importedNames.add(fontName)
                }
            }
        } catch (e: Exception) {
            Log.e("ConfigManager", "Failed to import font $fileName", e)
        }
        return importedNames
    }

    fun getAvailableCustomFonts(): List<String> {
        return customFontsDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }

    fun getCustomFontFile(name: String): File {
        return File(customFontsDir, name)
    }

    /**
     * Imports a shared .dwp configuration bundle.
     */
    fun importConfig(uri: Uri): Boolean {
        val fileName = getFileName(uri) ?: return false
        if (!fileName.lowercase().endsWith(".dwp")) return false
        
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val destFile = File(configsDir, fileName)
                destFile.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
        } catch (e: Exception) {
            Log.e("ConfigManager", "Failed to import config $fileName", e)
            false
        }
    }

    private fun getFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    /**
     * Saves all assets into a single .dwp binary file.
     */
    fun saveBundle(
        config: WallpaperConfig,
        baseBitmap: Bitmap?,
        maskBitmap: Bitmap?,
        previewBitmap: Bitmap?,
        customFontName: String? = null
    ): String {
        val id = if (config.id == "default" || config.id.isEmpty()) UUID.randomUUID().toString() else config.id
        config.id = id
        
        val bundleFile = File(configsDir, "$id.dwp")
        
        try {
            val fos = FileOutputStream(bundleFile)
            ZipOutputStream(fos).use { zos ->
                // 1. Save Metadata
                zos.putNextEntry(ZipEntry("config.json"))
                zos.write(gson.toJson(config).toByteArray())
                zos.closeEntry()
                
                // 2. Save Images
                baseBitmap?.let { saveImageToZip(it, "base.png", zos) }
                maskBitmap?.let { saveImageToZip(it, "mask.png", zos) }
                previewBitmap?.let { saveImageToZip(it, "preview.png", zos) }

                // 3. Embed Custom Font if active
                if (config.isCustomFont) {
                    val fontToEmbed = customFontName ?: config.customFontName
                    if (fontToEmbed.isNotEmpty()) {
                        val fontFile = getCustomFontFile(fontToEmbed)
                        if (fontFile.exists()) {
                            zos.putNextEntry(ZipEntry("font.ttf"))
                            fontFile.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        } else {
                            // Try to recover from existing bundle if re-saving
                            loadBundleFile(id, "font.ttf")?.let { data ->
                                zos.putNextEntry(ZipEntry("font.ttf"))
                                zos.write(data)
                                zos.closeEntry()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ConfigManager", "Failed to save bundle $id", e)
        }
        
        return id
    }

    private fun saveImageToZip(bitmap: Bitmap, name: String, zos: ZipOutputStream) {
        zos.putNextEntry(ZipEntry(name))
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, zos)
        zos.closeEntry()
    }

    /**
     * Loads the config metadata from a .dwp bundle.
     */
    fun loadBundleConfig(id: String): WallpaperConfig? {
        val file = File(configsDir, "$id.dwp")
        if (!file.exists()) return null
        
        return try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "config.json") {
                        val reader = InputStreamReader(zis)
                        return gson.fromJson(reader, WallpaperConfig::class.java)
                    }
                    entry = zis.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            Log.e("ConfigManager", "Error loading config from bundle $id", e)
            null
        }
    }

    /**
     * Loads a specific image asset from a .dwp bundle.
     */
    fun loadBundleBitmap(id: String, type: String): Bitmap? {
        val file = File(configsDir, "$id.dwp")
        if (!file.exists()) return null
        val target = when(type) {
            "base" -> "base.png"
            "mask" -> "mask.png"
            "preview" -> "preview.png"
            else -> return null
        }
        
        return try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == target) {
                        return BitmapFactory.decodeStream(zis)
                    }
                    entry = zis.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            Log.e("ConfigManager", "Error loading $type from bundle $id", e)
            null
        }
    }

    /**
     * Generic file loader from bundle (used for fonts)
     */
    fun loadBundleFile(id: String, fileName: String): ByteArray? {
        val file = File(configsDir, "$id.dwp")
        if (!file.exists()) return null
        
        return try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == fileName) {
                        return zis.readBytes()
                    }
                    entry = zis.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            Log.e("ConfigManager", "Error loading $fileName from bundle $id", e)
            null
        }
    }

    /**
     * Utility to get a temporary file for a custom font.
     */
    fun getFontTempFile(id: String): File {
        val tempDir = File(context.cacheDir, "fonts")
        if (!tempDir.exists()) tempDir.mkdirs()
        return File(tempDir, "$id.ttf")
    }

    /**
     * Lists all available configuration bundles.
     */
    fun loadAllConfigs(): List<WallpaperConfig> {
        val list = mutableListOf<WallpaperConfig>()
        configsDir.listFiles()?.filter { it.name.endsWith(".dwp") }?.forEach { file ->
            loadBundleConfig(file.nameWithoutExtension)?.let { list.add(it) }
        }
        return list
    }

    fun deleteConfig(id: String) {
        val file = File(configsDir, "$id.dwp")
        if (file.exists()) file.delete()
        val tempFont = getFontTempFile(id)
        if (tempFont.exists()) tempFont.delete()
    }

    fun getActiveConfigId(): String? {
        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        return prefs.getString("active_config_id", null)
    }
    
    fun setActiveConfigId(id: String) {
        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("active_config_id", id).apply()
        
        // Notify any running service
        val intent = Intent("com.thusvill.advancewallpapermanager.UPDATE_CONFIG")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
}
