package com.thusvill.advancewallpapermanager

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    configManager: ConfigManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(context.applicationContext as android.app.Application, configManager)
    )
    val uiState by viewModel.uiState.collectAsState()
    var showFontDeleteDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 1. Config Rotation
            item {
                Text("Wallpaper Rotation", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(12.dp))
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RotationOption("None", uiState.rotationMode == RotationMode.NONE) {
                        viewModel.setRotationMode(RotationMode.NONE)
                    }
                    RotationOption("Daily", uiState.rotationMode == RotationMode.DAILY) {
                        viewModel.setRotationMode(RotationMode.DAILY)
                    }
                    RotationOption("Hourly", uiState.rotationMode == RotationMode.HOURLY) {
                        viewModel.setRotationMode(RotationMode.HOURLY)
                    }
                    if (uiState.rotationMode == RotationMode.HOURLY) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Interval: ${uiState.hourInterval} hours", fontSize = 14.sp)
                            Slider(
                                value = uiState.hourInterval.toFloat(),
                                onValueChange = { viewModel.setHourInterval(it.toInt()) },
                                valueRange = 1f..12f,
                                steps = 10,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                    RotationOption("On Awake", uiState.rotationMode == RotationMode.ON_AWAKE) {
                        viewModel.setRotationMode(RotationMode.ON_AWAKE)
                    }
                }
            }

            // 2. Font Management
            item {
                Text("Font Management", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                        uri?.let { viewModel.importFont(it) }
                    }
                    Button(
                        onClick = { fontLauncher.launch("*/*") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Import Font/ZIP")
                    }
                    
                    OutlinedButton(
                        onClick = { showFontDeleteDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = uiState.customFonts.isNotEmpty()
                    ) {
                        Text("Delete Fonts")
                    }
                }
            }

            // 3. Reset Data
            item {
                Text("System", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Red)
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Reset User Data", color = Color.White)
                }
                Text(
                    "Warning: This will delete all your wallpaper configs and custom fonts permanently.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    if (showFontDeleteDialog) {
        FontDeleteDialog(
            fonts = uiState.customFonts,
            onDismiss = { showFontDeleteDialog = false },
            onDelete = { selected ->
                viewModel.deleteFonts(selected)
                showFontDeleteDialog = false
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Full Reset?") },
            text = { Text("Are you sure you want to delete EVERYTHING? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetUserData()
                    showResetConfirm = false
                }) {
                    Text("RESET", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun RotationOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
fun FontDeleteDialog(
    fonts: List<String>,
    onDismiss: () -> Unit,
    onDelete: (List<String>) -> Unit
) {
    val selectedFonts = remember { mutableStateListOf<String>() }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(480.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Select Fonts to Delete", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(16.dp))
                
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(fonts) { font ->
                        val isSelected = selectedFonts.contains(font)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSelected) selectedFonts.remove(font) else selectedFonts.add(font)
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Checkbox(checked = isSelected, onCheckedChange = {
                                if (it) selectedFonts.add(font) else selectedFonts.remove(font)
                            })
                            Text(font, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onDelete(selectedFonts.toList()) },
                        modifier = Modifier.weight(1f),
                        enabled = selectedFonts.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete (${selectedFonts.size})")
                    }
                }
            }
        }
    }
}
