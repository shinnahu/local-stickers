package com.localstickers.app.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.localstickers.app.data.Backup
import com.localstickers.app.data.ImportPlan
import com.localstickers.app.data.PackRepository
import com.localstickers.app.data.StickerPack
import com.localstickers.app.ui.common.BusyOverlay
import com.localstickers.app.ui.common.ImportDialog
import com.localstickers.app.ui.common.MessageDialog
import com.localstickers.app.ui.common.PackInfoDialog
import com.localstickers.app.ui.common.PackStateChip
import com.localstickers.app.ui.common.Prefs
import com.localstickers.app.ui.common.checkerboard
import com.localstickers.app.ui.common.packState
import com.localstickers.app.ui.common.rememberBackupActions
import com.localstickers.app.ui.common.rememberWhatsAppStatuses
import com.localstickers.app.whatsapp.WhatsApp
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenPack: (String) -> Unit, onEditImage: (Uri) -> Unit) {
    val context = LocalContext.current
    val repository = remember { PackRepository.get(context) }
    val packs by repository.packs.collectAsStateWithLifecycle()
    val sorted = remember(packs) { packs.sortedByDescending { it.updatedAt } }
    val statuses by rememberWhatsAppStatuses(packs.map { it.id })
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var menuOpen by remember { mutableStateOf(false) }
    var newPack by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var importPlan by remember { mutableStateOf<ImportPlan?>(null) }
    var report by remember { mutableStateOf<List<String>?>(null) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onEditImage) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onEditImage) }
    val backup = rememberBackupActions(snackbar, onBusy = { busy = it }, onPlan = { importPlan = it })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local Stickers") },
                actions = {
                    IconButton(onClick = { newPack = true }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "New pack")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Pick image or GIF from files") },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    pickFile.launch(arrayOf("image/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Import backup…") },
                                leadingIcon = { Icon(Icons.Filled.FileUpload, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    backup.pickBackup()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export all packs…") },
                                enabled = packs.isNotEmpty(),
                                leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    backup.exportToFile(sorted)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share backup of all packs") },
                                enabled = packs.isNotEmpty(),
                                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    backup.share(sorted)
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("New sticker") },
                icon = { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null) },
                onClick = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (sorted.isEmpty()) {
            EmptyHome(
                modifier = Modifier.padding(padding),
                onCreate = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onImport = backup.pickBackup,
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
            ) {
                items(sorted, key = { it.id }) { pack ->
                    PackRow(
                        pack = pack,
                        tray = repository.trayFile(pack),
                        status = statuses[pack.id],
                        onClick = { onOpenPack(pack.id) },
                    )
                }
            }
        }
    }

    busy?.let { BusyOverlay(it, null) }

    if (newPack) {
        PackInfoDialog(
            title = "New pack",
            initialName = "My stickers ${packs.size + 1}",
            initialPublisher = Prefs.publisher(context),
            initialAnimated = false,
            showType = true,
            confirmLabel = "Create",
            onConfirm = { name, publisher, animated ->
                Prefs.setPublisher(context, publisher)
                scope.launch {
                    val pack = repository.createPack(name, publisher, animated)
                    onOpenPack(pack.id)
                }
            },
            onDismiss = { newPack = false },
        )
    }

    importPlan?.let { plan ->
        ImportDialog(
            plan = plan,
            onImport = { asCopies ->
                importPlan = null
                scope.launch {
                    busy = "Importing…"
                    try {
                        val outcomes = Backup.runImport(context, plan, asCopies)
                        report = outcomes.map { o ->
                            buildString {
                                append(o.packName).append(": ")
                                append(if (o.merged) "${o.added} new sticker(s) added" else "${o.added} sticker(s) imported")
                                if (o.skipped > 0) append(", ${o.skipped} skipped")
                            }
                        }
                    } catch (e: Exception) {
                        snackbar.showSnackbar(e.message ?: "Import failed")
                    } finally {
                        busy = null
                    }
                }
            },
            onCancel = {
                importPlan = null
                scope.launch { Backup.discard(plan) }
            },
        )
    }

    report?.let { lines -> MessageDialog("Import finished", lines) { report = null } }
}

@Composable
private fun EmptyHome(modifier: Modifier, onCreate: () -> Unit, onImport: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No sticker packs yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a photo or a GIF, crop it, remove the background and save it to a pack. " +
                "Everything stays on this phone. Packs with 3 or more stickers can be added to WhatsApp.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreate) {
            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Create a sticker")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onImport) {
            Icon(Icons.Filled.FileUpload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Import a backup")
        }
    }
}

@Composable
private fun PackRow(pack: StickerPack, tray: File?, status: WhatsApp.Status?, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .checkerboard(6.dp),
            ) {
                if (tray != null) {
                    AsyncImage(model = tray, contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pack.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${pack.stickers.size}/30 · ${if (pack.animated) "Animated" else "Still"} · ${pack.publisher}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                PackStateChip(pack, packState(pack, status))
            }
        }
    }
}
