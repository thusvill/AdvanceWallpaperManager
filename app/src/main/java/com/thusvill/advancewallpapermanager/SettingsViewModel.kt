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
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SettingsViewModel(application: Application, private val configManager: ConfigManager) : AndroidViewModel(application) {
    private val rotationManager = RotationManager(application)
    
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        _uiState.update { it.copy(
            rotationMode = rotationManager.getRotationMode(),
            hourInterval = rotationManager.getHourInterval(),
            customFonts = configManager.getAvailableCustomFonts()
        ) }
    }

    fun setRotationMode(mode: RotationMode) {
        rotationManager.setRotationMode(mode, uiState.value.hourInterval)
        _uiState.update { it.copy(rotationMode = mode) }
    }

    fun setHourInterval(hours: Int) {
        rotationManager.setRotationMode(uiState.value.rotationMode, hours)
        _uiState.update { it.copy(hourInterval = hours) }
    }

    fun importFont(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.importFont(uri)
            loadSettings()
        }
    }

    fun deleteFonts(fonts: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            fonts.forEach { fontName ->
                val file = configManager.getCustomFontFile(fontName)
                if (file.exists()) file.delete()
            }
            loadSettings()
        }
    }

    fun scanConfigsForFonts() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isScanning = true) }
            val configs = configManager.loadAllConfigs()
            var importedCount = 0
            
            configs.forEach { config ->
                if (config.isCustomFont) {
                    val fontName = config.customFontName
                    val libFile = configManager.getCustomFontFile(fontName)
                    
                    if (!libFile.exists() && fontName.isNotEmpty()) {
                        // Extract font from this specific bundle
                        val fontData = configManager.loadBundleFile(config.id, "font.ttf")
                        fontData?.let { data ->
                            try {
                                FileOutputStream(libFile).use { it.write(data) }
                                importedCount++
                            } catch (e: Exception) {
                                android.util.Log.e("SettingsVM", "Failed to auto-import $fontName", e)
                            }
                        }
                    }
                }
            }
            
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isScanning = false) }
                loadSettings()
            }
        }
    }

    fun resetUserData() {
        viewModelScope.launch(Dispatchers.IO) {
            //Delete all configs
            configManager.loadAllConfigs().forEach { config ->
                configManager.deleteConfig(config.id)
            }
            //Delete all custom fonts
            val fontsDir = File(getApplication<Application>().filesDir, "custom_fonts")
            fontsDir.deleteRecursively()
            fontsDir.mkdirs()
            
            //Clear rotation prefs
            rotationManager.setRotationMode(RotationMode.NONE)
            
            loadSettings()
        }
    }
}

data class SettingsUiState(
    val rotationMode: RotationMode = RotationMode.NONE,
    val hourInterval: Int = 1,
    val customFonts: List<String> = emptyList(),
    val isScanning: Boolean = false
)
