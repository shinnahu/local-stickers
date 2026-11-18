package com.localstickers.app.ui.pack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.localstickers.app.data.Sticker
import com.localstickers.app.data.StickerPack
import com.localstickers.app.data.WhatsAppLimits
import com.localstickers.app.ui.common.ConfirmDialog
import com.localstickers.app.ui.common.EmojiPicker
import com.localstickers.app.ui.common.checkerboard
import com.localstickers.app.ui.common.formatKb
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerSheet(
    pack: StickerPack,
    sticker: Sticker,
    file: File,
    otherPacks: List<StickerPack>,
    addedToWhatsApp: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, emojis: List<String>) -> Unit,
    onSetTray: () -> Unit,
    onMove: (StickerPack) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(sticker.id) { mutableStateOf(sticker.name) }
    var emojis by remember(sticker.id) { mutableStateOf(sticker.emojis) }
    var moveMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isTray = pack.trayFile == "tray_${sticker.id}.png"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .checkerboard(),
            ) {
                AsyncImage(model = file, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Text(
                "512×512 · ${formatKb(file.length())} · ${if (pack.animated) "animated" else "still"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(pack.a11yLimit) },
                label = { Text("Sticker name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            EmojiPicker(selected = emojis, onChange = { emojis = it })
            Button(
                onClick = {
                    onSave(name, emojis)
                    onDismiss()
                },
                enabled = name != sticker.name || emojis != sticker.emojis,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save changes") }

            HorizontalDivider()

            ListItem(
                headlineContent = { Text(if (isTray) "This is the pack icon" else "Use as pack icon") },
                leadingContent = { Icon(Icons.Filled.Star, contentDescription = null) },
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).let { m ->
                    if (isTray) m else m.clickableItem {
                        onSetTray()
                        onDismiss()
                    }
                },
                colors = ListItemDefaults.colors(),
            )
            Box {
                ListItem(
                    headlineContent = { Text("Move to another pack") },
                    supportingContent = {
                        Text(
                            if (otherPacks.isEmpty()) "No other ${if (pack.animated) "animated" else "still"} pack with free space" else "Same type packs only",
                        )
                    },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).let { m ->
                        if (otherPacks.isEmpty()) m else m.clickableItem { moveMenu = true }
                    },
                )
                DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                    otherPacks.forEach { target ->
                        DropdownMenuItem(
                            text = { Text("${target.name} (${target.stickers.size}/30)") },
                            onClick = {
                                moveMenu = false
                                onMove(target)
                            },
                        )
                    }
                }
            }
            ListItem(
                headlineContent = { Text("Delete sticker", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickableItem { confirmDelete = true },
            )
        }
    }

    if (confirmDelete) {
        val dropsBelowMinimum = addedToWhatsApp && pack.stickers.size - 1 < WhatsAppLimits.MIN_STICKERS
        ConfirmDialog(
            title = "Delete this sticker?",
            text = if (dropsBelowMinimum) {
                "The pack will have fewer than 3 stickers, so WhatsApp can't update it until you add more."
            } else {
                "It will be removed from this phone" + if (addedToWhatsApp) " and from the pack in WhatsApp." else "."
            },
            confirmLabel = "Delete",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun Modifier.clickableItem(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
