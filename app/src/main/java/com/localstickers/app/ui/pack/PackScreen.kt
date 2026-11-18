package com.localstickers.app.ui.pack

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.localstickers.app.data.PackRepository
import com.localstickers.app.data.Sticker
import com.localstickers.app.data.StickerPack
import com.localstickers.app.data.WhatsAppLimits
import com.localstickers.app.ui.common.BusyOverlay
import com.localstickers.app.ui.common.ConfirmDialog
import com.localstickers.app.ui.common.FullScreenMessage
import com.localstickers.app.ui.common.MessageDialog
import com.localstickers.app.ui.common.PackInfoDialog
import com.localstickers.app.ui.common.PackState
import com.localstickers.app.ui.common.checkerboard
import com.localstickers.app.ui.common.packState
import com.localstickers.app.ui.common.rememberBackupActions
import com.localstickers.app.ui.common.rememberWhatsAppStatuses
import com.localstickers.app.whatsapp.WhatsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackScreen(packId: String, onBack: () -> Unit, onEditImage: (Uri) -> Unit) {
    val context = LocalContext.current
    val repository = remember { PackRepository.get(context) }
    val packs by repository.packs.collectAsStateWithLifecycle()
    val pack = packs.firstOrNull { it.id == packId }
    if (pack == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val statuses by rememberWhatsAppStatuses(listOf(packId))
    val status = statuses[packId]
    val state = packState(pack, status)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var menuOpen by remember { mutableStateOf(false) }
    var editInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selectedSticker by remember { mutableStateOf<String?>(null) }
    var problems by remember { mutableStateOf<List<String>?>(null) }
    var whatsAppError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onEditImage) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onEditImage) }
    val backup = rememberBackupActions(snackbar, onBusy = { busy = it })

    val addToWhatsApp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.launch {
            val error = result.data?.getStringExtra(WhatsApp.EXTRA_VALIDATION_ERROR)
            val now = withContext(Dispatchers.IO) { WhatsApp.status(context, packId) }
            when {
                error != null -> whatsAppError = error
                result.resultCode == Activity.RESULT_OK || now.addedSomewhere -> {
                    repository.markSynced(packId)
                    snackbar.showSnackbar("The pack is in WhatsApp. Open the sticker tray to use it.")
                }
                else -> snackbar.showSnackbar("The pack wasn't added to WhatsApp")
            }
        }
    }

    fun sendToWhatsApp() {
        scope.launch {
            val current = repository.pack(packId) ?: return@launch
            val issues = withContext(Dispatchers.IO) { WhatsApp.validate(repository, current) }
            if (issues.isNotEmpty()) {
                problems = issues
                return@launch
            }
            val now = withContext(Dispatchers.IO) { WhatsApp.status(context, packId) }
            val intent = WhatsApp.addPackIntent(current, now)
            if (intent == null) {
                snackbar.showSnackbar("WhatsApp isn't installed on this phone")
                return@launch
            }
            try {
                addToWhatsApp.launch(intent)
            } catch (e: ActivityNotFoundException) {
                snackbar.showSnackbar("This WhatsApp version can't add sticker packs. Update WhatsApp and try again.")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(pack.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${pack.stickers.size}/30 · ${if (pack.animated) "Animated" else "Still"} · ${pack.publisher}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { editInfo = true }) { Icon(Icons.Filled.Edit, contentDescription = "Rename") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Add image or GIF from files") },
                                enabled = !pack.isFull,
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    pickFile.launch(arrayOf("image/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export this pack…") },
                                enabled = pack.stickers.isNotEmpty(),
                                leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    backup.exportToFile(listOf(pack))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share this pack") },
                                enabled = pack.stickers.isNotEmpty(),
                                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    backup.share(listOf(pack))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete pack") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    confirmDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { WhatsAppBar(pack, state, onSend = ::sendToWhatsApp) },
        floatingActionButton = {
            if (!pack.isFull) {
                ExtendedFloatingActionButton(
                    text = { Text("Add sticker") },
                    icon = { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null) },
                    onClick = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (pack.stickers.isEmpty()) {
            FullScreenMessage(
                "This pack is empty.\nTap “Add sticker” to pick a ${if (pack.animated) "GIF" else "photo"}.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(pack.stickers, key = { it.id }) { sticker ->
                    StickerCell(
                        file = repository.stickerFile(pack.id, sticker),
                        sticker = sticker,
                        isTray = pack.trayFile == "tray_${sticker.id}.png",
                        onClick = { selectedSticker = sticker.id },
                    )
                }
            }
        }
    }

    busy?.let { BusyOverlay(it, null) }

    selectedSticker?.let { id ->
        val sticker = pack.stickers.firstOrNull { it.id == id }
        if (sticker == null) {
            selectedSticker = null
        } else {
            StickerSheet(
                pack = pack,
                sticker = sticker,
                file = repository.stickerFile(pack.id, sticker),
                otherPacks = packs.filter { it.id != pack.id && it.animated == pack.animated && !it.isFull },
                addedToWhatsApp = state == PackState.ADDED || state == PackState.UPDATE,
                onDismiss = { selectedSticker = null },
                onSave = { name, emojis -> scope.launch { repository.updateSticker(pack.id, sticker.id, name, emojis) } },
                onSetTray = { scope.launch { repository.setTraySticker(pack.id, sticker.id) } },
                onMove = { target ->
                    selectedSticker = null
                    scope.launch {
                        try {
                            repository.moveSticker(pack.id, sticker.id, target.id)
                            snackbar.showSnackbar("Moved to “${target.name}”")
                        } catch (e: Exception) {
                            snackbar.showSnackbar(e.message ?: "The sticker couldn't be moved")
                        }
                    }
                },
                onDelete = {
                    selectedSticker = null
                    scope.launch { repository.deleteSticker(pack.id, sticker.id) }
                },
            )
        }
    }

    if (editInfo) {
        PackInfoDialog(
            title = "Edit pack",
            initialName = pack.name,
            initialPublisher = pack.publisher,
            initialAnimated = pack.animated,
            showType = false,
            confirmLabel = "Save",
            onConfirm = { name, publisher, _ -> scope.launch { repository.updatePackInfo(pack.id, name, publisher) } },
            onDismiss = { editInfo = false },
        )
    }

    if (confirmDelete) {
        val inWhatsApp = state == PackState.ADDED || state == PackState.UPDATE
        ConfirmDialog(
            title = "Delete “${pack.name}”?",
            text = "Its ${pack.stickers.size} sticker(s) will be deleted from this phone." +
                (if (inWhatsApp) " WhatsApp will stop showing this pack." else "") +
                " Export a backup first if you may want it back.",
            confirmLabel = "Delete",
            // Leaving the screen happens on its own once the pack disappears from the list.
            onConfirm = { scope.launch { repository.deletePack(pack.id) } },
            onDismiss = { confirmDelete = false },
        )
    }

    problems?.let { MessageDialog("Fix these first", it) { problems = null } }
    whatsAppError?.let { MessageDialog("WhatsApp rejected the pack", listOf(it)) { whatsAppError = null } }
}

@Composable
private fun WhatsAppBar(pack: StickerPack, state: PackState, onSend: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            when (state) {
                PackState.NEEDS_STICKERS -> {
                    val missing = WhatsAppLimits.MIN_STICKERS - pack.stickers.size
                    Text(
                        "WhatsApp needs at least 3 stickers per pack. Add $missing more.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PackState.NO_WHATSAPP -> Text(
                    "WhatsApp isn't installed on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PackState.NOT_ADDED -> Button(onClick = onSend, modifier = Modifier.fillMaxWidth()) { Text("Add to WhatsApp") }
                PackState.UPDATE -> {
                    Text(
                        "You changed this pack after adding it to WhatsApp.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onSend, modifier = Modifier.fillMaxWidth()) { Text("Update in WhatsApp") }
                }
                PackState.ADDED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Added to WhatsApp", modifier = Modifier.weight(1f))
                    TextButton(onClick = onSend) { Text("Send again") }
                }
            }
        }
    }
}

@Composable
private fun StickerCell(file: File, sticker: Sticker, isTray: Boolean, onClick: () -> Unit) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .checkerboard(8.dp)
                .clickable(onClick = onClick),
        ) {
            AsyncImage(model = file, contentDescription = sticker.name.ifBlank { null }, modifier = Modifier.fillMaxSize().padding(4.dp))
            if (isTray) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Pack icon",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(3.dp),
                    )
                }
            }
            Text(
                sticker.emojis.joinToString(""),
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (sticker.name.isNotBlank()) {
            Text(
                sticker.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}
