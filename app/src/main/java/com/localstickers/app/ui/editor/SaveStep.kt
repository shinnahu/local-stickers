package com.localstickers.app.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.localstickers.app.data.PackRepository
import com.localstickers.app.data.StickerPack
import com.localstickers.app.data.WhatsAppLimits
import com.localstickers.app.ui.common.EmojiPicker
import com.localstickers.app.ui.common.PackTypeSelector
import com.localstickers.app.ui.common.checkerboard
import com.localstickers.app.ui.common.formatKb

@Composable
fun SaveStep(ui: EditorUi, vm: EditorViewModel) {
    val packs by vm.packs.collectAsStateWithLifecycle()
    val sorted = packs.sortedByDescending { it.updatedAt }
    val repository = PackRepository.get(LocalContext.current)
    val limit = if (ui.animated) WhatsAppLimits.ANIMATED_MAX_BYTES else WhatsAppLimits.STATIC_MAX_BYTES

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .checkerboard(),
            ) {
                AsyncImage(model = ui.previewFile, contentDescription = "Sticker preview", modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${if (ui.animated) "Animated" else "Still"} · 512×512 · ${formatKb(ui.previewBytes)} of ${limit / 1024} KB",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = ui.stickerName,
            onValueChange = vm::setStickerName,
            label = { Text("Sticker name") },
            supportingText = { Text("Optional. WhatsApp uses it as the accessibility description.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        EmojiPicker(selected = ui.emojis, onChange = vm::setEmojis)

        HorizontalDivider()
        Text("Save to pack", style = MaterialTheme.typography.titleMedium)

        for (pack in sorted) {
            PackChoice(
                pack = pack,
                trayFile = repository.trayFile(pack),
                selected = (ui.target as? SaveTarget.Existing)?.packId == pack.id,
                sourceAnimated = ui.animated,
                onSelect = { vm.setTarget(SaveTarget.Existing(pack.id)) },
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { vm.setTarget(SaveTarget.NewPack) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = ui.target is SaveTarget.NewPack, onClick = { vm.setTarget(SaveTarget.NewPack) })
            Text("New pack", fontWeight = FontWeight.Medium)
        }
        if (ui.target is SaveTarget.NewPack) {
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ui.newPackName,
                    onValueChange = vm::setNewPackName,
                    label = { Text("Pack name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ui.newPackPublisher,
                    onValueChange = vm::setNewPackPublisher,
                    label = { Text("Author") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                PackTypeSelector(animated = ui.newPackAnimated, onChange = vm::setNewPackAnimated)
                conversionNote(sourceAnimated = ui.animated, packAnimated = ui.newPackAnimated)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }

        val canSave = when (val t = ui.target) {
            is SaveTarget.Existing -> packs.any { it.id == t.packId && !it.isFull }
            SaveTarget.NewPack -> ui.newPackName.isNotBlank()
        }
        Button(onClick = vm::save, enabled = canSave && ui.busy == null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Save sticker")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PackChoice(
    pack: StickerPack,
    trayFile: java.io.File?,
    selected: Boolean,
    sourceAnimated: Boolean,
    onSelect: () -> Unit,
) {
    val enabled = !pack.isFull
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .checkerboard(6.dp),
        ) {
            if (trayFile != null) AsyncImage(model = trayFile, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(pack.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val type = if (pack.animated) "Animated" else "Still"
            Text(
                if (pack.isFull) "$type · full (30/30)" else "$type · ${pack.stickers.size}/30",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selected) {
                conversionNote(sourceAnimated, pack.animated)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

private fun conversionNote(sourceAnimated: Boolean, packAnimated: Boolean): String? = when {
    sourceAnimated && !packAnimated -> "Still pack: only the first frame of the GIF will be saved."
    !sourceAnimated && packAnimated -> "Animated pack: the image will be saved as an animated sticker that doesn't move."
    else -> null
}
