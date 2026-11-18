package com.localstickers.app.ui.common

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.localstickers.app.data.WhatsAppLimits
import kotlin.math.ceil
import kotlin.math.min

/** Grey/white squares that show where a sticker is transparent. */
@Composable
fun Modifier.checkerboard(cell: Dp = 10.dp): Modifier {
    val dark = isSystemInDarkTheme()
    val light = if (dark) Color(0xFF2B2B2B) else Color(0xFFFFFFFF)
    val shade = if (dark) Color(0xFF3A3A3A) else Color(0xFFE6E6E6)
    return this.drawWithCache {
        val c = cell.toPx()
        val path = Path()
        val cols = ceil(size.width / c).toInt()
        val rows = ceil(size.height / c).toInt()
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if ((x + y) % 2 == 1) {
                    path.addRect(Rect(x * c, y * c, min((x + 1) * c, size.width), min((y + 1) * c, size.height)))
                }
            }
        }
        onDrawBehind {
            drawRect(light)
            drawPath(path, shade)
        }
    }
}

@Composable
fun BusyOverlay(message: String, progress: Float?) {
    Dialog(onDismissRequest = {}) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(
                Modifier.padding(24.dp).width(260.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (progress == null) {
                    CircularProgressIndicator()
                } else {
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(16.dp))
                Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun MessageDialog(title: String, lines: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.take(12).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                if (lines.size > 12) Text("…and ${lines.size - 12} more")
            }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { Text(text) },
    )
}

/** Create / rename a pack. [showType] = allow choosing static vs animated (only for new packs). */
@Composable
fun PackInfoDialog(
    title: String,
    initialName: String,
    initialPublisher: String,
    initialAnimated: Boolean,
    showType: Boolean,
    confirmLabel: String,
    onConfirm: (name: String, publisher: String, animated: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var publisher by remember { mutableStateOf(initialPublisher) }
    var animated by remember { mutableStateOf(initialAnimated) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(WhatsAppLimits.MAX_TEXT) },
                    label = { Text("Pack name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = publisher,
                    onValueChange = { publisher = it.take(WhatsAppLimits.MAX_TEXT) },
                    label = { Text("Author") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showType) {
                    PackTypeSelector(animated = animated, onChange = { animated = it })
                    Text(
                        "WhatsApp doesn't allow mixing still and animated stickers in one pack.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && publisher.isNotBlank(),
                onClick = { onConfirm(name.trim(), publisher.trim(), animated); onDismiss() },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PackTypeSelector(animated: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !animated,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("Still") }
        SegmentedButton(
            selected = animated,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("Animated") }
    }
}

private val COMMON_EMOJIS = listOf(
    "😀", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎", "🤔", "😮", "😢", "😭",
    "😡", "🥳", "😴", "🤯", "😱", "🙄", "😅", "😇", "🤗", "🤪", "🥺", "😬",
    "👍", "👎", "👏", "🙏", "💪", "👋", "✌️", "🤝", "❤️", "💔", "🔥", "✨",
    "🎉", "💯", "👀", "🤡", "💩", "👻", "🐶", "🐱", "🍕", "☕", "⚽", "🌹",
)

/** Pick 1–3 emojis (WhatsApp uses them to find stickers when searching). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiPicker(selected: List<String>, onChange: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    var custom by remember { mutableStateOf("") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Emojis", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Text(
                if (selected.isEmpty()) "pick 1 to 3" else selected.joinToString(" ") + "  (${selected.size}/3)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "WhatsApp uses them to suggest this sticker when you search.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (emoji in COMMON_EMOJIS) {
                val isSelected = emoji in selected
                val enabled = isSelected || selected.size < WhatsAppLimits.MAX_EMOJIS
                Surface(
                    onClick = {
                        onChange(if (isSelected) selected - emoji else selected + emoji)
                    },
                    enabled = enabled,
                    shape = CircleShape,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(emoji, fontSize = 22.sp, color = if (enabled) Color.Unspecified else Color.Gray)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it.take(40) },
                label = { Text("Other emoji (type with your keyboard)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = custom.isNotBlank() && selected.size < WhatsAppLimits.MAX_EMOJIS,
                onClick = {
                    val found = extractEmojis(custom)
                    onChange((selected + found).distinct().take(WhatsAppLimits.MAX_EMOJIS))
                    custom = ""
                },
            ) { Text("Add") }
        }
    }
}

/** Splits text into user-perceived characters and keeps the ones that look like emoji. */
fun extractEmojis(text: String): List<String> {
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(text)
    val result = ArrayList<String>()
    var start = it.first()
    var end = it.next()
    while (end != java.text.BreakIterator.DONE) {
        val g = text.substring(start, end)
        if (g.isNotBlank() && looksLikeEmoji(g.codePointAt(0))) result += g
        start = end
        end = it.next()
    }
    return result
}

private fun looksLikeEmoji(cp: Int): Boolean =
    cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2190..0x21FF ||
        cp in 0x2B00..0x2BFF || cp in 0x3030..0x303D || cp == 0x00A9 || cp == 0x00AE || cp in 0x2100..0x214F ||
        Character.getType(cp) == Character.OTHER_SYMBOL.toInt()

fun formatKb(bytes: Long): String = if (bytes < 1024) "$bytes B" else "${(bytes + 512) / 1024} KB"

@Composable
fun FullScreenMessage(text: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

object Prefs {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun publisher(context: Context): String = prefs(context).getString("publisher", null) ?: "Me"

    fun setPublisher(context: Context, value: String) {
        prefs(context).edit().putString("publisher", value).apply()
    }
}
