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
import java.io.File

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

    fun resetUserData() {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Delete all configs
            configManager.loadAllConfigs().forEach { config ->
                configManager.deleteConfig(config.id)
            }
            // 2. Delete all custom fonts
            val fontsDir = File(getApplication<Application>().filesDir, "custom_fonts")
            fontsDir.deleteRecursively()
            fontsDir.mkdirs()
            
            // 3. Clear rotation prefs
            rotationManager.setRotationMode(RotationMode.NONE)
            
            loadSettings()
        }
    }
}

data class SettingsUiState(
    val rotationMode: RotationMode = RotationMode.NONE,
    val hourInterval: Int = 1,
    val customFonts: List<String> = emptyList()
)
