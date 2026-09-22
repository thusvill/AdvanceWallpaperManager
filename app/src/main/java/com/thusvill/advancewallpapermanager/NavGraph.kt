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

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

@Composable
fun AppNavGraph(
    navController: NavHostController,
    configManager: ConfigManager,
    initialImportUri: Uri? = null,
    onInitialImportUriHandled: () -> Unit = {}
) {
    LaunchedEffect(initialImportUri) {
        initialImportUri?.let {
            navController.navigate("import/${Uri.encode(it.toString())}")
            onInitialImportUriHandled()
        }
    }

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
                },
                onNavigateToImport = { uri ->
                    navController.navigate("import/${Uri.encode(uri.toString())}")
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
        composable(
            route = "import/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val rawUri = backStackEntry.arguments?.getString("uri").orEmpty()
            ImportScreen(
                uri = Uri.parse(Uri.decode(rawUri)),
                configManager = configManager,
                onBack = { navController.popBackStack() },
                onImported = {
                    navController.popBackStack("gallery", inclusive = false)
                }
            )
        }
    }
}
