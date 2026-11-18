package com.localstickers.app.ui.common

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.localstickers.app.data.Backup
import com.localstickers.app.data.ImportPlan
import com.localstickers.app.data.PackRepository
import com.localstickers.app.data.StickerPack
import kotlinx.coroutines.launch
import java.io.File

class BackupActions(
    val exportToFile: (List<StickerPack>) -> Unit,
    val share: (List<StickerPack>) -> Unit,
    val pickBackup: () -> Unit,
)

/**
 * Export (Android "Save as" picker, e.g. Downloads or Drive), share (share sheet) and import of backup files.
 * [onBusy] shows/hides a progress dialog, [onPlan] receives a checked backup ready to import.
 */
@Composable
fun rememberBackupActions(
    snackbar: SnackbarHostState,
    onBusy: (String?) -> Unit,
    onPlan: (ImportPlan) -> Unit = {},
): BackupActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by rememberUpdatedState(onBusy)
    val plan by rememberUpdatedState(onPlan)
    var pendingIds by rememberSaveable { mutableStateOf("") }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        val ids = pendingIds.split(',').filter { it.isNotEmpty() }.toSet()
        if (uri == null || ids.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val packs = PackRepository.get(context).snapshot().filter { it.id in ids }
            busy("Saving backup…")
            try {
                Backup.exportTo(context, uri, packs)
                snackbar.showSnackbar("Backup saved: ${packs.size} pack(s), ${packs.sumOf { it.stickers.size }} stickers")
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "The backup couldn't be saved")
            } finally {
                busy(null)
            }
        }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy("Reading backup…")
            try {
                plan(Backup.prepareImport(context, uri))
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "This file can't be imported")
            } finally {
                busy(null)
            }
        }
    }

    return remember {
        BackupActions(
            exportToFile = { packs ->
                pendingIds = packs.joinToString(",") { it.id }
                createDocument.launch(Backup.suggestedName(packs))
            },
            share = { packs ->
                scope.launch {
                    busy("Preparing backup…")
                    try {
                        shareFile(context, Backup.exportToCache(context, packs))
                    } catch (e: Exception) {
                        snackbar.showSnackbar(e.message ?: "The backup couldn't be shared")
                    } finally {
                        busy(null)
                    }
                }
            },
            pickBackup = {
                openDocument.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
            },
        )
    }
}

private fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(Backup.MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share sticker backup"))
}

/** Summary of a backup before importing, with the choice of what to do with packs that already exist. */
@Composable
fun ImportDialog(plan: ImportPlan, onImport: (asCopies: Boolean) -> Unit, onCancel: () -> Unit) {
    var asCopies by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Import stickers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${plan.packs.size} pack(s), ${plan.stickerCount} stickers found.")
                plan.packs.take(8).forEach { p ->
                    val existing = if (p.existsLocally && !p.alwaysNew) " (already on this phone)" else ""
                    Text(
                        "• ${p.meta.name}: ${p.files.size} ${if (p.meta.animated) "animated" else "still"}$existing",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (plan.packs.size > 8) Text("…and ${plan.packs.size - 8} more")
                if (plan.rejectedCount > 0) {
                    Text(
                        "${plan.rejectedCount} file(s) will be skipped because they don't meet WhatsApp's sticker rules.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (plan.conflicts > 0) {
                    Text("For packs that already exist:", style = MaterialTheme.typography.titleSmall)
                    ChoiceRow("Add the stickers they are missing (keeps them linked to WhatsApp)", !asCopies) { asCopies = false }
                    ChoiceRow("Import as separate new packs", asCopies) { asCopies = true }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(asCopies) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
