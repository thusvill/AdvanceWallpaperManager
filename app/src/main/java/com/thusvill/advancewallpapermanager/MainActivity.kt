package com.thusvill.advancewallpapermanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.thusvill.advancewallpapermanager.ui.theme.AdvanceWallpaperManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val configManager = ConfigManager(this)
        
        setContent {
            AdvanceWallpaperManagerTheme {
                val navController = rememberNavController()
                AppNavGraph(
                    navController = navController,
                    configManager = configManager
                )
            }
        }
    }
}
