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

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import java.io.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private val exportDir: File by lazy {
        val dir = File(context.cacheDir, "exports")
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
                val data = input.readBytes()
                val config = readConfigFromBundleBytes(data) ?: return false
                importBundleBytes(data, config)
                true
            } ?: false
        } catch (e: Exception) { Log.e(TAG, "Failed to import config $fileName", e); false }
    }

    fun getFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    fun saveBundle(config: WallpaperConfig, baseBitmap: Bitmap?, maskBitmap: Bitmap?, previewBitmap: Bitmap?, customFontName: String? = null): String {
        val id = if (config.id == "default" || config.id.isEmpty()) UUID.randomUUID().toString() else config.id
        config.id = id
        if (config.displayName.isBlank()) config.displayName = "Wallpaper ${id.take(8)}"
        val existingFile = findBundleFileById(id)
        val bundleFile = uniqueConfigFile(config.displayName, existingFile)
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

    fun loadBundleConfig(id: String): WallpaperConfig? {
        val file = findBundleFileById(id)
        if (!file.exists()) return null
        return try {
            readConfigFromBundleFile(file)?.also {
                if (it.id.isBlank() || it.id == "default") it.id = id
                if (it.displayName.isBlank()) it.displayName = file.nameWithoutExtension
            }
        } catch (e: Exception) { Log.e(TAG, "Error loading config from bundle $id", e); null }
    }

    fun loadBundleBitmap(id: String, type: String): Bitmap? {
        val file = findBundleFileById(id)
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
        val file = findBundleFileById(id)
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
                readConfigFromBundleFile(file)?.let { config ->
                    if (config.id.isBlank() || config.id == "default") config.id = file.nameWithoutExtension
                    if (config.displayName.isBlank()) config.displayName = file.nameWithoutExtension
                    list.add(config)
                }
            } catch (e: Exception) { Log.e(TAG, "Corrupt bundle: ${file.name}", e) }
        }
        return list.sortedBy { it.displayName.lowercase() }
    }

    fun deleteConfig(id: String) {
        val file = findBundleFileById(id)
        if (file.exists()) file.delete()
        val tempFont = getFontTempFile(id)
        if (tempFont.exists()) tempFont.delete()
    }

    fun exportConfigsAsDwps(ids: Set<String>): Uri? {
        val selected = loadAllConfigs().filter { ids.contains(it.id) }
        if (selected.isEmpty()) return null
        val exportName = if (selected.size == 1) selected.first().displayName else "Depth Wallpapers ${timestamp()}"
        val outFile = File(exportDir, "${sanitizeFileName(exportName)}.dwps")
        return try {
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                val exportItems = selected.map { config ->
                    config to "configs/${sanitizeFileName(config.displayName)}-${config.id.take(8)}.dwp"
                }
                val manifest = BundleManifest(
                    version = 1,
                    configs = exportItems.map { (config, path) -> BundleManifestItem(config.id, config.displayName, path) }
                )
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(gson.toJson(manifest).toByteArray())
                zos.closeEntry()
                exportItems.forEach { (config, path) ->
                    val source = findBundleFileById(config.id)
                    if (source.exists()) {
                        zos.putNextEntry(ZipEntry(path))
                        source.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export .dwps", e)
            null
        }
    }

    fun createShareIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/x-dwps"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun inspectSerializedConfigs(uri: Uri): List<ImportableConfig> {
        val fileName = getFileName(uri).orEmpty().lowercase()
        return try {
            if (fileName.endsWith(".dwp")) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val data = input.readBytes()
                    readConfigFromBundleBytes(data)?.let {
                        if (it.displayName.isBlank()) it.displayName = getFileName(uri)?.substringBeforeLast('.') ?: it.id.take(8)
                        listOf(ImportableConfig("single.dwp", it.id, it.displayName))
                    }
                } ?: emptyList()
            } else {
                val items = mutableListOf<ImportableConfig>()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.lowercase().endsWith(".dwp")) {
                                val data = zis.readBytes()
                                readConfigFromBundleBytes(data)?.let { config ->
                                    val name = config.displayName.ifBlank { File(entry.name).nameWithoutExtension }
                                    items.add(ImportableConfig(entry.name, config.id, name))
                                }
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
                items
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inspect serialized configs", e)
            emptyList()
        }
    }

    fun importSerializedConfigs(uri: Uri, entryNames: Set<String>): Int {
        val fileName = getFileName(uri).orEmpty().lowercase()
        return try {
            if (fileName.endsWith(".dwp")) {
                if (!entryNames.contains("single.dwp")) return 0
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val data = input.readBytes()
                    val config = readConfigFromBundleBytes(data) ?: return 0
                    importBundleBytes(data, config)
                    1
                } ?: 0
            } else {
                var imported = 0
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entryNames.contains(entry.name)) {
                                val data = zis.readBytes()
                                val config = readConfigFromBundleBytes(data)
                                if (config != null) {
                                    importBundleBytes(data, config)
                                    imported++
                                }
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
                imported
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import serialized configs", e)
            0
        }
    }

    fun getActiveConfigId(): String? = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).getString("active_config_id", null)
    fun setActiveConfigId(id: String) {
        context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).edit().putString("active_config_id", id).apply()
        val intent = Intent("com.thusvill.advancewallpapermanager.UPDATE_CONFIG")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    private fun importBundleBytes(data: ByteArray, incomingConfig: WallpaperConfig) {
        if (incomingConfig.id.isBlank() || incomingConfig.id == "default" || findBundleFileById(incomingConfig.id).exists()) {
            incomingConfig.id = UUID.randomUUID().toString()
        }
        if (incomingConfig.displayName.isBlank()) incomingConfig.displayName = incomingConfig.id.take(8)
        val destFile = uniqueConfigFile(incomingConfig.displayName, null)
        ZipInputStream(ByteArrayInputStream(data)).use { zis ->
            ZipOutputStream(FileOutputStream(destFile)).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        zos.putNextEntry(ZipEntry(entry.name))
                        if (entry.name.lowercase().endsWith("config.json")) {
                            zos.write(gson.toJson(incomingConfig).toByteArray())
                        } else {
                            zis.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun findBundleFileById(id: String): File {
        val direct = File(configsDir, "$id.dwp")
        if (direct.exists()) return direct
        val files = configsDir.listFiles()?.filter { it.name.endsWith(".dwp") } ?: emptyList()
        return files.firstOrNull { file ->
            try { readConfigFromBundleFile(file)?.id == id } catch (_: Exception) { false }
        } ?: direct
    }

    private fun readConfigFromBundleFile(file: File): WallpaperConfig? {
        return file.inputStream().use { readConfigFromBundleStream(it) }
    }

    private fun readConfigFromBundleBytes(data: ByteArray): WallpaperConfig? {
        return ByteArrayInputStream(data).use { readConfigFromBundleStream(it) }
    }

    private fun readConfigFromBundleStream(input: InputStream): WallpaperConfig? {
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.lowercase().endsWith("config.json")) {
                    return gson.fromJson(InputStreamReader(zis), WallpaperConfig::class.java)
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    private fun uniqueConfigFile(displayName: String, currentFile: File?): File {
        val base = sanitizeFileName(displayName.ifBlank { "Wallpaper" })
        var candidate = File(configsDir, "$base.dwp")
        if (currentFile != null && candidate.absolutePath == currentFile.absolutePath) return candidate
        var index = 2
        while (candidate.exists()) {
            candidate = File(configsDir, "$base ($index).dwp")
            if (currentFile != null && candidate.absolutePath == currentFile.absolutePath) return candidate
            index++
        }
        if (currentFile != null && currentFile.exists() && currentFile.absolutePath != candidate.absolutePath) currentFile.delete()
        return candidate
    }

    private fun sanitizeFileName(name: String): String {
        return name.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(80)
            .ifBlank { "Wallpaper" }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
}

data class ImportableConfig(
    val entryName: String,
    val id: String,
    val displayName: String
)

private data class BundleManifest(
    val version: Int,
    val configs: List<BundleManifestItem>
)

private data class BundleManifestItem(
    val id: String,
    val displayName: String,
    val path: String
)
