package com.thusvill.advancewallpapermanager

import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlin.math.abs


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    configId: String?,
    configManager: ConfigManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: EditorViewModel = viewModel(
        factory = EditorViewModelFactory(context.applicationContext as android.app.Application, configManager)
    )

    val uiState by viewModel.uiState.collectAsState()
    var isSettingsVisible by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(configId) {
        viewModel.loadConfig(configId)
    }

    BackHandler {
        onBack()
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.setSourceImage(it) }
    }

    val systemFonts = remember {
        val fontsDir = File("/system/fonts")
        val allowedVariants = listOf("regular", "bold", "condensed", "italic")
        fontsDir.listFiles()?.filter { file ->
            val name = file.nameWithoutExtension.lowercase()
            file.extension == "ttf" && (allowedVariants.any { name.contains(it) } || !name.contains("-"))
        }?.map { it.nameWithoutExtension }?.distinct() ?: listOf("sans-serif", "serif", "monospace")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full Screen Preview
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(uiState.interactionMode) {
                    if (uiState.interactionMode == InteractionMode.CLOCK) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            viewModel.handleClockTransform(
                                pan.x,
                                pan.y,
                                zoom,
                                rotation,
                                size.width.toFloat(),
                                size.height.toFloat()
                            )
                        }
                    } else {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            viewModel.handleWallpaperTransform(
                                pan.x,
                                pan.y,
                                zoom,
                                rotation,
                                size.width.toFloat(),
                                size.height.toFloat()
                            )
                        }
                    }
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                viewModel.onSurfaceCreated(h.surface)
                            }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hi: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) {}
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { viewModel.requestPreviewUpdate() }
            )
            
            if (uiState.isExtracting) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
        }

        // Top Interaction Bar (Clock / Wallpaper)
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
        ) {
            Row(modifier = Modifier.padding(4.dp)) {
                InteractionPill("Clock", uiState.interactionMode == InteractionMode.CLOCK) {
                    viewModel.updateInteraction { InteractionMode.CLOCK }
                }
                InteractionPill("Wallpaper", uiState.interactionMode == InteractionMode.WALLPAPER) {
                    viewModel.updateInteraction { InteractionMode.WALLPAPER }
                }
            }
        }

        // Back Button (Floating)
        FilledIconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(16.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        // Delete Button (Floating Right)
        if (uiState.config.id != "default") {
            FilledIconButton(
                onClick = { viewModel.deleteConfig(onBack) },
                modifier = Modifier
                    .statusBarsPadding()
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }

        // Toggle Settings Button
        FilledIconButton(
            onClick = { isSettingsVisible = !isSettingsVisible },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Icon(if (isSettingsVisible) Icons.Default.Close else Icons.Default.Settings, contentDescription = "Toggle Settings")
        }

        // Floating Settings Window (Bottom 1/4th)
        AnimatedVisibility(
            visible = isSettingsVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
                shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.98f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Pill Tab Bar
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        edgePadding = 16.dp,
                        containerColor = Color.Transparent,
                        divider = {},
                        indicator = { tabPositions ->
                            if (selectedTab < tabPositions.size) {
                                Box(
                                    modifier = Modifier
                                        .tabIndicatorOffset(tabPositions[selectedTab])
                                        .fillMaxHeight()
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                        .zIndex(-1f)
                                )
                            }
                        },
                        modifier = Modifier.height(56.dp)
                    ) {
                        val tabs = listOf("Source", "Wallpaper", "Model", "Clock", "Apply")
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = { 
                                    Text(
                                        title, 
                                        style = if(selectedTab == index) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                                        color = if(selectedTab == index) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    ) 
                                }
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                    // Tab Content
                    Box(modifier = Modifier.weight(1f).padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
                        Column(modifier = Modifier.padding(vertical = 12.dp)) {
                            when (selectedTab) {
                                0 -> { // Source
                                    Button(
                                        onClick = { launcher.launch("image/*") }, 
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Text("Pick New Image")
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        uiState.statusText, 
                                        style = MaterialTheme.typography.labelSmall, 
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                1 -> { // Wallpaper
                                    SectionTitle("Transform")
                                    SliderItem("Scale", uiState.config.wallpaperScale, 0.1f..20.0f) {
                                        viewModel.updateConfig { c -> c.copy(wallpaperScale = it) }
                                    }
                                    SliderItem("Rotation", uiState.config.wallpaperRotation, 0f..360f, step = 1f) {
                                        viewModel.setWallpaperRotation(it)
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    SectionTitle("Quick Rotation Snap")
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(0f, 90f, 180f, 270f).forEach { angle ->
                                            OutlinedButton(
                                                onClick = { viewModel.setWallpaperRotation(angle) },
                                                modifier = Modifier.weight(1f),
                                                shape = MaterialTheme.shapes.medium,
                                                colors = if (abs(uiState.config.wallpaperRotation - angle) < 1f)
                                                    ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                                                else ButtonDefaults.outlinedButtonColors()
                                            ) {
                                                Text("${angle.toInt()}°", fontSize = 12.sp)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.updateConfig { c ->
                                                c.copy(wallpaperScale = 1.0f, wallpaperOffsetX = 0f, wallpaperOffsetY = 0f, wallpaperRotation = 0f)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Text("Reset Transform")
                                    }
                                }
                                2 -> { // Model
                                    SectionTitle("Pipeline")
                                    FlowRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                        ExtractionMode.entries.forEach { mode ->
                                            FilterChip(
                                                selected = uiState.extractionMode == mode,
                                                onClick = { viewModel.setExtractionMode(mode) },
                                                label = { Text(mode.displayName) },
                                                modifier = Modifier.padding(end = 8.dp),
                                                shape = MaterialTheme.shapes.medium
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(16.dp))
                                    
                                    when (uiState.extractionMode) {
                                        ExtractionMode.DEEPLAB_V3_MOBILENET_V2 -> {
                                            SectionTitle("DeepLab Parameters")
                                            SliderItem("Target Class", uiState.config.deepLabTargetClassIndex.toFloat(), 0f..20f, step = 1f) {
                                                viewModel.updateConfig { c -> c.copy(deepLabTargetClassIndex = it.toInt()) }
                                            }
                                            SliderItem("Min Confidence", uiState.config.deepLabMinConfidence, 0f..1f) {
                                                viewModel.updateConfig { c -> c.copy(deepLabMinConfidence = it) }
                                            }
                                        }
                                        ExtractionMode.SALIENCY_MATTE -> {
                                            SectionTitle("Saliency Parameters")
                                            SliderItem("Threshold", uiState.config.saliencyThreshold, 0.05f..0.95f) {
                                                viewModel.updateConfig { c -> c.copy(saliencyThreshold = it) }
                                            }
                                            SliderItem("Feather", uiState.config.saliencyFeatherRadius.toFloat(), 1f..32f, step = 1f) {
                                                viewModel.updateConfig { c -> c.copy(saliencyFeatherRadius = it.toInt()) }
                                            }
                                            SliderItem("Cleanup", uiState.config.saliencyCleanupRadius.toFloat(), 0f..8f, step = 1f) {
                                                viewModel.updateConfig { c -> c.copy(saliencyCleanupRadius = it.toInt()) }
                                            }
                                            SliderItem("Edge Lock", uiState.config.saliencyEdgeLock, 0f..1f) {
                                                viewModel.updateConfig { c -> c.copy(saliencyEdgeLock = it) }
                                            }
                                        }
                                        ExtractionMode.MLKIT_SUBJECT -> {
                                            SectionTitle("ML Kit Parameters")
                                            SliderItem("Threshold (τ)", uiState.config.mlKitThreshold, 0.05f..0.95f) {
                                                viewModel.updateConfig { c -> c.copy(mlKitThreshold = it) }
                                            }
                                            SliderItem("Expansion (Px)", uiState.config.mlKitExpansionPx.toFloat(), -20f..20f, step = 1f) {
                                                viewModel.updateConfig { c -> c.copy(mlKitExpansionPx = it.toInt()) }
                                            }
                                            SliderItem("Feather", uiState.config.mlKitFeatherRadius.toFloat(), 1f..32f, step = 1f) {
                                                viewModel.updateConfig { c -> c.copy(mlKitFeatherRadius = it.toInt()) }
                                            }
                                            SliderItem("Clock Depth (Occlusion Clamp)", uiState.config.clockDepth, 0.0f..1.0f) {
                                                viewModel.updateConfig { c -> c.copy(clockDepth = it) }
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                                3 -> { // Clock
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedButton(
                                            onClick = { viewModel.updateConfig { it.copy(clockMode = ClockMode.HORIZONTAL) } },
                                            modifier = Modifier.weight(1f),
                                            shape = MaterialTheme.shapes.medium,
                                            colors = if (uiState.config.clockMode == ClockMode.HORIZONTAL) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) else ButtonDefaults.outlinedButtonColors()
                                        ) {
                                            Text("Horizontal")
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.updateConfig { it.copy(clockMode = ClockMode.VERTICAL) } },
                                            modifier = Modifier.weight(1f),
                                            shape = MaterialTheme.shapes.medium,
                                            colors = if (uiState.config.clockMode == ClockMode.VERTICAL) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) else ButtonDefaults.outlinedButtonColors()
                                        ) {
                                            Text("Vertical")
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(16.dp))
                                    ListItem(
                                        headlineContent = { Text(if (uiState.config.use24HourFormat) "24-Hour Format" else "AM/PM Format", color = MaterialTheme.colorScheme.onSurface) },
                                        trailingContent = {
                                            Switch(
                                                checked = uiState.config.use24HourFormat,
                                                onCheckedChange = { newValue -> viewModel.updateConfig { c -> c.copy(use24HourFormat = newValue) } }
                                            )
                                        },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                    )

                                    SliderItem("Size", uiState.config.fontSize, 10f..1000f) {
                                        viewModel.updateConfig { c -> c.copy(fontSize = it) }
                                    }
                                    SliderItem("Rotation", uiState.config.clockRotation, 0f..360f, step = 1f) {
                                        viewModel.setClockRotation(it)
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    SectionTitle("Clock Rotation Snap")
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(0f, 90f, 180f, 270f).forEach { angle ->
                                            OutlinedButton(
                                                onClick = { viewModel.setClockRotation(angle) },
                                                modifier = Modifier.weight(1f),
                                                shape = MaterialTheme.shapes.medium,
                                                colors = if (abs(uiState.config.clockRotation - angle) < 1f)
                                                    ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                                                else ButtonDefaults.outlinedButtonColors()
                                            ) {
                                                Text("${angle.toInt()}°", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                    SliderItem("Vertical Stretch", uiState.config.clockHeightScale, 0.5f..3.0f) {
                                        viewModel.updateConfig { c -> c.copy(clockHeightScale = it) }
                                    }
                                    SliderItem("Thickness", uiState.config.fontThickness, 0f..20f) {
                                        viewModel.updateConfig { c -> c.copy(fontThickness = it) }
                                    }
                                    SliderItem("Letter Space", uiState.config.letterSpacing, -0.1f..0.5f) {
                                        viewModel.updateConfig { c -> c.copy(letterSpacing = it) }
                                    }
                                    if (uiState.config.clockMode == ClockMode.VERTICAL) {
                                        SliderItem("Line Space", uiState.config.lineSpacing, -600f..400f) {
                                            viewModel.updateConfig { c -> c.copy(lineSpacing = it) }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                    SectionTitle("Font Family")
                                    
                                    // SYSTEM FONTS - CLEAN UI
                                    var expandedSys by remember { mutableStateOf(false) }
                                    Box(modifier = Modifier.padding(top = 4.dp)) {
                                        OutlinedButton(
                                            onClick = { expandedSys = true }, 
                                            modifier = Modifier.fillMaxWidth(), 
                                            shape = MaterialTheme.shapes.medium
                                        ) {
                                            Text(
                                                text = if(uiState.config.isCustomFont) "Select System Font" else uiState.config.fontFamily,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        DropdownMenu(expanded = expandedSys, onDismissRequest = { expandedSys = false }) {
                                            systemFonts.forEach { font ->
                                                DropdownMenuItem(text = { Text(font) }, onClick = {
                                                    viewModel.updateConfig { it.copy(fontFamily = font) }
                                                    expandedSys = false
                                                })
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    // CUSTOM FONTS - UNIFIED SINGLE BUTTON
                                    var expandedCust by remember { mutableStateOf(false) }
                                    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                                        uri?.let { viewModel.importFont(it) }
                                    }
                                    
                                    Box(modifier = Modifier.padding(top = 4.dp)) {
                                        OutlinedButton(
                                            onClick = { expandedCust = true }, 
                                            modifier = Modifier.fillMaxWidth(), 
                                            shape = MaterialTheme.shapes.medium
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if(uiState.config.isCustomFont) uiState.config.customFontName else "Custom Fonts",
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1
                                            )
                                        }
                                        DropdownMenu(expanded = expandedCust, onDismissRequest = { expandedCust = false }) {
                                            // Integrated Import Action
                                            DropdownMenuItem(
                                                text = { Text("Import New Font (.ttf, .zip)", fontWeight = FontWeight.Bold) },
                                                onClick = { 
                                                    fontLauncher.launch("*/*")
                                                    expandedCust = false 
                                                },
                                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
                                            )
                                            if (uiState.availableCustomFonts.isNotEmpty()) {
                                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                                uiState.availableCustomFonts.forEach { font ->
                                                    DropdownMenuItem(
                                                        text = { Text(font) }, 
                                                        onClick = {
                                                            viewModel.setCustomFont(font)
                                                            expandedCust = false
                                                        },
                                                        trailingIcon = { if(uiState.config.customFontName == font) Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp)) }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                    SectionTitle("Clock Color")
                                    
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                        FilledTonalButton(
                                            onClick = { viewModel.autoDetectColor() }, 
                                            modifier = Modifier.weight(1f),
                                            shape = MaterialTheme.shapes.medium
                                        ) {
                                            Text("Auto Color", fontSize = 12.sp)
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        val colors = listOf(Color.White, Color.Black, Color.Red, Color.Cyan, Color.Yellow, Color.Green, Color.Magenta)
                                        colors.forEach { color ->
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .padding(2.dp)
                                                    .clip(CircleShape)
                                                    .background(color)
                                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                                    .clickable { viewModel.updateConfig { it.copy(fontColor = color.toArgb()) } }
                                            )
                                        }
                                    }
                                }
                                4 -> { // Apply
                                    Button(
                                        onClick = { viewModel.applyConfig(onBack) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.large
                                    ) {
                                        Text("Apply & Auto-Save")
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.saveConfig(onBack) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.large
                                    ) {
                                        Text("Save Only")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }
                }
            }
        }
    }
}



@Composable
fun InteractionPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

@Composable
fun SliderItem(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float = 0f, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (step == 1f) value.toInt().toString() else "%.2f".format(value), 
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface // VISIBILITY FIX
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = if (step > 0) ((range.endInclusive - range.start) / step).toInt() - 1 else 0
        )
    }
}
