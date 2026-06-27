package com.thusvill.advancewallpapermanager

import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

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
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            viewModel.handleClockDragDelta(
                                dragAmount.x,
                                dragAmount.y,
                                size.width.toFloat(),
                                size.height.toFloat()
                            )
                        }
                    } else {
                        detectTransformGestures { _, pan, zoom, _ ->
                            viewModel.handleWallpaperTransform(
                                pan.x,
                                pan.y,
                                zoom,
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

        // Top Middle Toggle (Wallpaper / Clock)
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .clip(CircleShape),
            color = Color.Black.copy(alpha = 0.5f)
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
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }

        // Delete Button (Floating Right)
        if (uiState.config.id != "default") {
            IconButton(
                onClick = { viewModel.deleteConfig(onBack) },
                modifier = Modifier
                    .statusBarsPadding()
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Red.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
            }
        }

        // Toggle Settings Button
        IconButton(
            onClick = { isSettingsVisible = !isSettingsVisible },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
        ) {
            Icon(if (isSettingsVisible) Icons.Default.Close else Icons.Default.Settings, contentDescription = "Toggle Settings", tint = Color.White)
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
                    .height(320.dp),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
                elevation = CardDefaults.cardElevation(24.dp)
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
                                        .height(34.dp)
                                        .offset(y = (-4).dp)
                                        .padding(horizontal = 4.dp)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
                                )
                            }
                        }
                    ) {
                        val tabs = listOf("Source", "Model", "Clock", "Apply")
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = { Text(title, fontSize = 13.sp, fontWeight = if(selectedTab == index) FontWeight.Bold else FontWeight.Normal) }
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                    // Tab Content
                    Box(modifier = Modifier.weight(1f).padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
                        Column(modifier = Modifier.padding(vertical = 12.dp)) {
                            when (selectedTab) {
                                0 -> { // Source
                                    Button(onClick = { launcher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Pick New Image")
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(uiState.statusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                                }
                                1 -> { // Model
                                    SectionTitle("Pipeline")
                                    FlowRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                        ExtractionMode.entries.forEach { mode ->
                                            FilterChip(
                                                selected = uiState.extractionMode == mode,
                                                onClick = { viewModel.setExtractionMode(mode) },
                                                label = { Text(mode.displayName, fontSize = 10.sp) },
                                                modifier = Modifier.padding(end = 6.dp)
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
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
                                            SliderItem("Feather", uiState.config.mlKitFeatherRadius.toFloat(), 1f..24f, step = 1f) {
                                                viewModel.updateConfig { c -> c.copy(mlKitFeatherRadius = it.toInt()) }
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                                2 -> { // Clock
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { viewModel.updateConfig { it.copy(clockMode = ClockMode.HORIZONTAL) } },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            border = if (uiState.config.clockMode == ClockMode.HORIZONTAL) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else ButtonDefaults.outlinedButtonBorder
                                        ) {
                                            Text("Horizontal", fontSize = 11.sp)
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.updateConfig { it.copy(clockMode = ClockMode.VERTICAL) } },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            border = if (uiState.config.clockMode == ClockMode.VERTICAL) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else ButtonDefaults.outlinedButtonBorder
                                        ) {
                                            Text("Vertical", fontSize = 11.sp)
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        val is24Hr = uiState.config.use24HourFormat
                                        Text(if (is24Hr) "24-Hour Format" else "AM/PM Format", modifier = Modifier.weight(1f), fontSize = 12.sp)
                                        Switch(
                                            checked = is24Hr,
                                            onCheckedChange = { newValue -> viewModel.updateConfig { c -> c.copy(use24HourFormat = newValue) } }
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    SliderItem("Size", uiState.config.fontSize, 50f..600f) {
                                        viewModel.updateConfig { c -> c.copy(fontSize = it) }
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

                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("System Fonts", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    
                                    var expandedSys by remember { mutableStateOf(false) }
                                    Box(modifier = Modifier.padding(top = 4.dp)) {
                                        OutlinedButton(onClick = { expandedSys = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                            Text(if(uiState.config.isCustomFont) "Select System Font" else uiState.config.fontFamily, fontSize = 12.sp)
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Custom Fonts", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                        val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                                            uri?.let { viewModel.importFont(it) }
                                        }
                                        IconButton(onClick = { fontLauncher.launch("*/*") }) { // ZIP or TTF
                                            Icon(Icons.Default.Add, contentDescription = "Import Font")
                                        }
                                    }
                                    
                                    if (uiState.availableCustomFonts.isNotEmpty()) {
                                        var expandedCust by remember { mutableStateOf(false) }
                                        Box(modifier = Modifier.padding(top = 4.dp)) {
                                            OutlinedButton(onClick = { expandedCust = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                                Text(if(uiState.config.isCustomFont) uiState.config.customFontName else "Select Custom Font", fontSize = 12.sp)
                                            }
                                            DropdownMenu(expanded = expandedCust, onDismissRequest = { expandedCust = false }) {
                                                uiState.availableCustomFonts.forEach { font ->
                                                    DropdownMenuItem(text = { Text(font) }, onClick = {
                                                        viewModel.setCustomFont(font)
                                                        expandedCust = false
                                                    })
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("Color", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                        Button(onClick = { viewModel.autoDetectColor() }, modifier = Modifier.weight(1f)) {
                                            Text("Auto Color", fontSize = 11.sp)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        val colors = listOf(Color.White, Color.Black, Color.Red, Color.Cyan, Color.Yellow, Color.Green, Color.Magenta)
                                        colors.forEach { color ->
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .padding(2.dp)
                                                    .clip(CircleShape)
                                                    .background(color)
                                                    .clickable { viewModel.updateConfig { it.copy(fontColor = color.toArgb()) } }
                                            )
                                        }
                                    }
                                }
                                3 -> { // Apply
                                    Button(
                                        onClick = { viewModel.applyConfig(onBack) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text("Apply & Auto-Save")
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.saveConfig(onBack) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text("Save Only")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
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
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun SliderItem(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float = 0f, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (step == 1f) value.toInt().toString() else "%.2f".format(value), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = if (step > 0) ((range.endInclusive - range.start) / step).toInt() - 1 else 0,
            modifier = Modifier.height(24.dp)
        )
    }
}
