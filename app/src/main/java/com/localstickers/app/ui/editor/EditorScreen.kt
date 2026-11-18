package com.localstickers.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localstickers.app.ui.common.BusyOverlay
import com.localstickers.app.ui.common.ConfirmDialog
import com.localstickers.app.ui.common.FullScreenMessage
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onClose: () -> Unit,
    onSaved: (packId: String) -> Unit,
    vm: EditorViewModel = viewModel(),
) {
    val ui = vm.ui
    val snackbar = remember { SnackbarHostState() }
    var confirmExit by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(ui.savedPackId) { ui.savedPackId?.let(onSaved) }

    val goBack: () -> Unit = {
        when {
            ui.loading || ui.error != null -> onClose()
            ui.step == Step.SAVE -> vm.backToEdit()
            ui.step == Step.EDIT -> vm.backToCrop()
            vm.hasChanges() -> confirmExit = true
            else -> onClose()
        }
    }
    BackHandler(enabled = ui.busy == null) { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (ui.step) {
                            Step.CROP -> "1. Crop"
                            Step.EDIT -> "2. Background"
                            Step.SAVE -> "3. Save"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (!ui.loading && ui.error == null) {
                        when (ui.step) {
                            Step.CROP -> TextButton(onClick = vm::toEdit) { Text("Next") }
                            Step.EDIT -> {
                                IconButton(onClick = vm::undo, enabled = ui.canUndo) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                                }
                                IconButton(onClick = vm::redo, enabled = ui.canRedo) {
                                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                                }
                                TextButton(onClick = vm::toSave) { Text("Next") }
                            }
                            Step.SAVE -> Unit
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading -> FullScreenMessage("Opening…")
                ui.error != null -> FullScreenMessage(ui.error) { Button(onClick = onClose) { Text("Close") } }
                ui.step == Step.CROP -> CropStep(ui, vm)
                ui.step == Step.EDIT -> EditStep(ui, vm)
                ui.step == Step.SAVE -> SaveStep(ui, vm)
            }
        }
    }

    ui.busy?.let { BusyOverlay(it, ui.progress) }

    if (confirmExit) {
        ConfirmDialog(
            title = "Discard this sticker?",
            text = "Your edits will be lost.",
            confirmLabel = "Discard",
            onConfirm = onClose,
            onDismiss = { confirmExit = false },
        )
    }
}

@Composable
private fun CropStep(ui: EditorUi, vm: EditorViewModel) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ui.cropImage?.let { image ->
                CropView(image = image, crop = ui.crop, square = ui.square, onCropChange = vm::setCrop, modifier = Modifier.fillMaxSize())
            }
        }
        ui.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !ui.square,
                onClick = { vm.setSquare(false) },
                label = { Text("Free") },
                leadingIcon = { Icon(Icons.Filled.Crop, contentDescription = null) },
            )
            FilterChip(
                selected = ui.square,
                onClick = { vm.setSquare(true) },
                label = { Text("Square") },
                leadingIcon = { Icon(Icons.Filled.CropSquare, contentDescription = null) },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = vm::rotate) { Icon(Icons.Filled.Rotate90DegreesCw, contentDescription = "Rotate") }
            IconButton(onClick = vm::flip) { Icon(Icons.Filled.Flip, contentDescription = "Mirror") }
            IconButton(onClick = vm::resetCrop) { Icon(Icons.Filled.RestartAlt, contentDescription = "Reset") }
        }
        Text(
            if (ui.animated) "GIF with ${ui.frameCount} frames. The crop applies to every frame." else "Drag the corners to crop.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun EditStep(ui: EditorUi, vm: EditorViewModel) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ui.editImage?.let { image ->
                MaskCanvas(
                    image = image,
                    tool = ui.tool,
                    brushRadius = ui.brushRadius,
                    onStroke = vm::addStroke,
                    onTap = vm::wandAt,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (ui.animated) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Frame ${ui.frame + 1}/${ui.frameCount}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = ui.frame.toFloat(),
                    onValueChange = { vm.setFrame(it.roundToInt()) },
                    valueRange = 0f..(ui.frameCount - 1).toFloat(),
                    steps = (ui.frameCount - 2).coerceAtLeast(0),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ToolOptions(ui, vm)
        NavigationBar {
            ToolItem(Tool.AUTO, "Auto", Icons.Outlined.AutoAwesome, ui.tool, vm::setTool)
            ToolItem(Tool.ERASE, "Erase", Icons.Filled.Brush, ui.tool, vm::setTool)
            ToolItem(Tool.RESTORE, "Restore", Icons.Filled.FormatPaint, ui.tool, vm::setTool)
            ToolItem(Tool.WAND, "Wand", Icons.Filled.Colorize, ui.tool, vm::setTool)
            ToolItem(Tool.OUTLINE, "Finish", Icons.Filled.PhotoSizeSelectLarge, ui.tool, vm::setTool)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ToolItem(
    tool: Tool,
    label: String,
    icon: ImageVector,
    current: Tool,
    onSelect: (Tool) -> Unit,
) {
    NavigationBarItem(
        selected = tool == current,
        onClick = { onSelect(tool) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
    )
}

@Composable
private fun ToolOptions(ui: EditorUi, vm: EditorViewModel) {
    val hint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        when (ui.tool) {
            Tool.AUTO -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ui.autoApplied) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Background removed. Touch up with Erase / Restore.", modifier = Modifier.weight(1f))
                    } else {
                        Button(onClick = vm::applyAuto, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.AutoFixHigh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Remove background")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = vm::resetEdits, enabled = ui.canUndo) { Text("Reset") }
                }
                Text(
                    "Runs on your phone (Google's on-device model). " +
                        if (ui.animated) "Every frame of the GIF is processed." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = hint,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Tool.ERASE, Tool.RESTORE -> {
                LabeledSlider(
                    label = "Brush size",
                    value = ui.brushRadius,
                    range = 0.01f..0.15f,
                    onChange = vm::setBrushRadius,
                )
                Text(
                    if (ui.tool == Tool.ERASE) "Paint over what you want to remove. Pinch with two fingers to zoom." else "Paint to bring back parts of the original image.",
                    style = MaterialTheme.typography.bodySmall,
                    color = hint,
                )
            }
            Tool.WAND -> {
                LabeledSlider(
                    label = "Color tolerance",
                    value = ui.tolerance.toFloat(),
                    range = 5f..120f,
                    onChange = { vm.setTolerance(it.roundToInt()) },
                )
                SwitchRow("Only the touching area", ui.contiguous, vm::setContiguous)
                Text(
                    "Tap a background color to remove it" + if (ui.animated) " from every frame." else ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = hint,
                )
            }
            Tool.OUTLINE -> {
                SwitchRow("Outline", ui.outline, vm::setOutline)
                if (ui.outline) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = ui.outlineWhite, onClick = { vm.setOutlineWhite(true) }, label = { Text("White") })
                        FilterChip(selected = !ui.outlineWhite, onClick = { vm.setOutlineWhite(false) }, label = { Text("Black") })
                    }
                    LabeledSlider(
                        label = "Outline width",
                        value = ui.outlineWidth.toFloat(),
                        range = 2f..16f,
                        onChange = { vm.setOutlineWidth(it.roundToInt()) },
                    )
                }
                SwitchRow("Fit to sticker (trim empty space)", ui.autoFit, vm::setAutoFit)
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(110.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
