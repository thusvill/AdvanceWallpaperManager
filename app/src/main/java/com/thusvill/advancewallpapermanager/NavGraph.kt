package com.thusvill.advancewallpapermanager

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

@Composable
fun AppNavGraph(
    navController: NavHostController,
    configManager: ConfigManager
) {
    NavHost(
        navController = navController,
        startDestination = "gallery"
    ) {
        composable("gallery") {
            GalleryScreen(
                configManager = configManager,
                onNavigateToEditor = { configId ->
                    if (configId != null) {
                        navController.navigate("editor/$configId")
                    } else {
                        navController.navigate("editor/new")
                    }
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }
        composable(
            route = "editor/{configId}",
            arguments = listOf(navArgument("configId") { type = NavType.StringType })
        ) { backStackEntry ->
            val configId = backStackEntry.arguments?.getString("configId")
            EditorScreen(
                configId = if (configId == "new") null else configId,
                configManager = configManager,
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                configManager = configManager,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
