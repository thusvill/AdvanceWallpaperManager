package com.thusvill.advancewallpapermanager

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class EditorViewModelFactory(
    private val application: Application,
    private val configManager: ConfigManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EditorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return EditorViewModel(application, configManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
