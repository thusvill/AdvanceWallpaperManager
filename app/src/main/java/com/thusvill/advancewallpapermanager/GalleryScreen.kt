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

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.thusvill.advancewallpapermanager.ui.theme.AdvanceWallpaperManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun GalleryScreenPreview() {
    AdvanceWallpaperManagerTheme {
        Scaffold(
            topBar = {
                MediumTopAppBar(
                    title = { Text("Wallpaper Configs") },
                    actions = {
                        IconButton(onClick = {}) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Config")
                }
            }
        ) { paddingValues ->
            Column(modifier = Modifier.padding(paddingValues).padding(16.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Mock Wallpaper", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    configManager: ConfigManager,
    onNavigateToEditor: (String?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToImport: (Uri) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var configs by remember { mutableStateOf(emptyList<WallpaperConfig>()) }
    var selectedConfigIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val isSelectionMode = selectedConfigIds.isNotEmpty()

    // STABILITY FIX: Safe permission check
    var hasFullAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true 
            }
        )
    }

    // Refresh logic
    fun refreshConfigs() {
        scope.launch(Dispatchers.IO) {
            try {
                val list = configManager.loadAllConfigs()
                withContext(Dispatchers.Main) {
                    configs = list
                }
            } catch (e: Exception) {
                android.util.Log.e("GalleryScreen", "Failed to refresh configs", e)
            }
        }
    }

    // Observe lifecycle to update permission state when coming back from settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    hasFullAccess = Environment.isExternalStorageManager()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(hasFullAccess) {
        refreshConfigs()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { onNavigateToImport(it) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text(if (isSelectionMode) "${selectedConfigIds.size} selected" else "Depth Wallpapers") },
                actions = {
                    if (isSelectionMode) {
                        IconButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val uri = configManager.exportConfigsAsDwps(selectedConfigIds)
                                    withContext(Dispatchers.Main) {
                                        if (uri != null) {
                                            val shareIntent = Intent.createChooser(configManager.createShareIntent(uri), "Share wallpapers")
                                            context.startActivity(shareIntent)
                                            selectedConfigIds = emptySet()
                                        } else {
                                            Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share Selected")
                        }
                        IconButton(onClick = { selectedConfigIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                        }
                    } else {
                    IconButton(onClick = { importLauncher.launch("*/*") }) {
                        Icon(Icons.Default.AddCircle, contentDescription = "Import Config")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onNavigateToEditor(null) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Config")
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            if (!hasFullAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PermissionBanner {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:${context.packageName}")
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(intent)
                        } catch (ex: Exception) {
                            android.util.Log.e("GalleryScreen", "Settings intent failed", ex)
                        }
                    }
                }
            }

            if (configs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No configs found. Click + to create one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(configs, key = { it.id }) { config ->
                        ConfigItem(
                            config = config, 
                            configManager = configManager,
                            isSelected = selectedConfigIds.contains(config.id),
                            selectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) {
                                    selectedConfigIds = if (selectedConfigIds.contains(config.id)) {
                                        selectedConfigIds - config.id
                                    } else {
                                        selectedConfigIds + config.id
                                    }
                                } else {
                                    onNavigateToEditor(config.id)
                                }
                            },
                            onLongClick = {
                                selectedConfigIds = selectedConfigIds + config.id
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionBanner(onGrant: () -> Unit) {
    Card(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Full Storage Access Required",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                "To see configurations shared by others in your Documents folder, please grant 'All Files Access' permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Grant Access")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConfigItem(
    config: WallpaperConfig,
    configManager: ConfigManager,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var previewBitmap by remember(config.id) { mutableStateOf<Bitmap?>(null) }
    
    LaunchedEffect(config.id) {
        withContext(Dispatchers.IO) {
            try {
                previewBitmap = configManager.loadBundleBitmap(config.id, "preview")
            } catch (e: Exception) {
                android.util.Log.e("GalleryScreen", "Failed to load thumb", e)
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Box {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                }
            }

            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
            
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            ) {
                Text(
                    text = config.displayName.ifBlank { config.id.take(8) },
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }
}
