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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    uri: Uri,
    configManager: ConfigManager,
    onBack: () -> Unit,
    onImported: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var items by remember(uri) { mutableStateOf<List<ImportableConfig>>(emptyList()) }
    var selectedEntries by remember(uri) { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember(uri) { mutableStateOf(true) }
    var isImporting by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        val inspected = withContext(Dispatchers.IO) { configManager.inspectSerializedConfigs(uri) }
        items = inspected
        selectedEntries = inspected.map { it.entryName }.toSet()
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Wallpapers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "${selectedEntries.size} selected",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        enabled = selectedEntries.isNotEmpty() && !isLoading && !isImporting,
                        onClick = {
                            scope.launch {
                                isImporting = true
                                val count = withContext(Dispatchers.IO) {
                                    configManager.importSerializedConfigs(uri, selectedEntries)
                                }
                                isImporting = false
                                if (count > 0) onImported() else resultText = "Nothing was imported"
                            }
                        }
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Import")
                    }
                }
            }
        }
    ) { padding ->
        when {
            isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            items.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    resultText ?: "No wallpaper configs found in this file.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(items, key = { it.entryName }) { item ->
                    val checked = selectedEntries.contains(item.entryName)
                    ListItem(
                        headlineContent = { Text(item.displayName) },
                        supportingContent = { Text(item.id.take(8)) },
                        leadingContent = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedEntries = if (isChecked) {
                                        selectedEntries + item.entryName
                                    } else {
                                        selectedEntries - item.entryName
                                    }
                                }
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
