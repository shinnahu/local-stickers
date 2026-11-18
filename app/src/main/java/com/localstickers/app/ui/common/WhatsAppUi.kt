package com.localstickers.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.localstickers.app.data.StickerPack
import com.localstickers.app.data.WhatsAppLimits
import com.localstickers.app.whatsapp.WhatsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PackState { NEEDS_STICKERS, NO_WHATSAPP, NOT_ADDED, UPDATE, ADDED }

fun packState(pack: StickerPack, status: WhatsApp.Status?): PackState = when {
    !pack.hasEnoughStickers -> PackState.NEEDS_STICKERS
    status == null -> PackState.NOT_ADDED
    !status.anyInstalled -> PackState.NO_WHATSAPP
    !status.addedSomewhere -> PackState.NOT_ADDED
    pack.changedSinceSync -> PackState.UPDATE
    else -> PackState.ADDED
}

/** Asks WhatsApp which packs it has. Refreshed every time the screen comes back to the foreground. */
@Composable
fun rememberWhatsAppStatuses(packIds: List<String>): State<Map<String, WhatsApp.Status>> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf<Map<String, WhatsApp.Status>>(emptyMap()) }
    val key = packIds.joinToString(",")
    LaunchedEffect(key, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.value = withContext(Dispatchers.IO) { packIds.associateWith { WhatsApp.status(context, it) } }
        }
    }
    return state
}

@Composable
fun PackStateChip(pack: StickerPack, state: PackState) {
    val (text, color) = when (state) {
        PackState.NEEDS_STICKERS -> {
            val missing = WhatsAppLimits.MIN_STICKERS - pack.stickers.size
            "Add $missing more to use in WhatsApp" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        PackState.NO_WHATSAPP -> "WhatsApp not installed" to MaterialTheme.colorScheme.onSurfaceVariant
        PackState.NOT_ADDED -> "Not in WhatsApp yet" to MaterialTheme.colorScheme.onSurfaceVariant
        PackState.UPDATE -> "Changes not sent to WhatsApp" to MaterialTheme.colorScheme.tertiary
        PackState.ADDED -> "In WhatsApp" to MaterialTheme.colorScheme.primary
    }
    val icon = when (state) {
        PackState.ADDED -> Icons.Filled.CheckCircle
        PackState.UPDATE -> Icons.Filled.Sync
        else -> Icons.Outlined.Info
    }
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}
