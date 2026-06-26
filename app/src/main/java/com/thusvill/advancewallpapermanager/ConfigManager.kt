package com.thusvill.advancewallpapermanager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages Depth Wallpaper (.dwp) binary bundles.
 * Each bundle is a ZIP file containing config.json and image assets.
 */
class ConfigManager(private val context: Context) {
    private val gson = Gson()
    
    private val configsDir: File by lazy {
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val appDir = File(baseDir, "AdvanceWallpaperManager/configs")
        if (!appDir.exists()) appDir.mkdirs()
        appDir
    }

    /**
     * Saves all assets into a single .dwp binary file.
     */
    fun saveBundle(
        config: WallpaperConfig,
        baseBitmap: Bitmap?,
        maskBitmap: Bitmap?,
        previewBitmap: Bitmap?
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
