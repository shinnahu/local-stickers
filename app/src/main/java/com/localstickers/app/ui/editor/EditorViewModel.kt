package com.localstickers.app.ui.editor

import android.app.Application
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.localstickers.app.data.DEFAULT_EMOJI
import com.localstickers.app.data.PackRepository
import com.localstickers.app.data.StickerPack
import com.localstickers.app.media.BackgroundRemover
import com.localstickers.app.media.EditSession
import com.localstickers.app.media.FinishOptions
import com.localstickers.app.media.MaskOp
import com.localstickers.app.media.MediaLoader
import com.localstickers.app.media.NothingToSaveException
import com.localstickers.app.media.Outline
import com.localstickers.app.media.WebpEncoder
import com.localstickers.app.ui.common.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

enum class Step { CROP, EDIT, SAVE }

enum class Tool { AUTO, ERASE, RESTORE, WAND, OUTLINE }

/** Where the sticker goes: an existing pack or a pack created on save. */
sealed class SaveTarget {
    data class Existing(val packId: String) : SaveTarget()
    data object NewPack : SaveTarget()
}

data class EditorUi(
    val loading: Boolean = true,
    val error: String? = null,
    val step: Step = Step.CROP,
    val animated: Boolean = false,
    val frameCount: Int = 1,
    val note: String? = null,
    // crop
    val cropImage: ImageBitmap? = null,
    val crop: Rect = Rect(0f, 0f, 1f, 1f),
    val square: Boolean = false,
    // edit
    val editImage: ImageBitmap? = null,
    val frame: Int = 0,
    val tool: Tool = Tool.AUTO,
    val brushRadius: Float = 0.04f,
    val tolerance: Int = 40,
    val contiguous: Boolean = true,
    val outline: Boolean = false,
    val outlineWidth: Int = 8,
    val outlineWhite: Boolean = true,
    val autoFit: Boolean = true,
    val autoApplied: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    // background work
    val busy: String? = null,
    val progress: Float? = null,
    // save
    val previewFile: File? = null,
    val previewBytes: Long = 0,
    val stickerName: String = "",
    val emojis: List<String> = listOf(DEFAULT_EMOJI),
    val target: SaveTarget = SaveTarget.NewPack,
    val newPackName: String = "",
    val newPackPublisher: String = "",
    val newPackAnimated: Boolean = false,
    val savedPackId: String? = null,
)

class EditorViewModel(app: Application, savedState: SavedStateHandle) : AndroidViewModel(app) {

    private val repository = PackRepository.get(app)
    private val remover = BackgroundRemover(app)
    private val sourceUri: Uri? = savedState.get<String>("uri")?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    private val presetPackId: String? = savedState.get<String>("packId")?.takeIf { it.isNotBlank() }

    val packs: StateFlow<List<StickerPack>> = repository.packs

    var ui by mutableStateOf(EditorUi())
        private set

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private var session: EditSession? = null
    private val ops = ArrayList<MaskOp>()
    private val redoStack = ArrayList<MaskOp>()
    private var renderJob: Job? = null
    private var targetChosen = false
    private val previewDir = File(app.cacheDir, "editor").apply { mkdirs() }

    init {
        load()
    }

    private fun message(text: String) {
        messageChannel.trySend(text)
    }

    private fun load() = viewModelScope.launch {
        val uri = sourceUri
        if (uri == null) {
            ui = ui.copy(loading = false, error = "No image was selected")
            return@launch
        }
        try {
            val media = MediaLoader.load(getApplication(), uri)
            val s = EditSession(media)
            session = s
            val preview = withContext(Dispatchers.Default) { s.orientedPreview(PREVIEW_SIDE).asImageBitmap() }
            ui = ui.copy(
                loading = false,
                animated = media.animated,
                frameCount = media.frames.size,
                note = media.note,
                cropImage = preview,
                newPackAnimated = media.animated,
                newPackPublisher = Prefs.publisher(getApplication()),
            )
        } catch (e: Throwable) {
            ui = ui.copy(loading = false, error = e.message ?: "This file can't be opened")
        }
    }

    fun hasChanges(): Boolean {
        val s = session ?: return false
        return ops.isNotEmpty() || s.rotation != 0 || s.flipH || ui.crop != Rect(0f, 0f, 1f, 1f)
    }

    // ------------------------------------------------------------------- crop

    fun rotate() {
        val s = session ?: return
        s.rotateClockwise()
        refreshCropPreview()
    }

    fun flip() {
        val s = session ?: return
        s.flipHorizontal()
        refreshCropPreview()
    }

    fun setCrop(rect: Rect) {
        session?.setCropRect(RectF(rect.left, rect.top, rect.right, rect.bottom))
        ui = ui.copy(crop = rect)
    }

    fun setSquare(on: Boolean) {
        val s = session ?: return
        if (on) {
            val iw = s.orientedWidth.toFloat()
            val ih = s.orientedHeight.toFloat()
            val c = ui.crop
            val side = min(c.width * iw, c.height * ih)
            val cx = (c.left + c.right) / 2f * iw
            val cy = (c.top + c.bottom) / 2f * ih
            setCrop(Rect((cx - side / 2) / iw, (cy - side / 2) / ih, (cx + side / 2) / iw, (cy + side / 2) / ih))
        }
        ui = ui.copy(square = on)
    }

    fun resetCrop() {
        val s = session ?: return
        s.resetOrientationAndCrop()
        ui = ui.copy(square = false)
        refreshCropPreview()
    }

    private fun refreshCropPreview() {
        val s = session ?: return
        viewModelScope.launch {
            val img = withContext(Dispatchers.Default) { s.orientedPreview(PREVIEW_SIDE).asImageBitmap() }
            val c = s.crop
            ui = ui.copy(cropImage = img, crop = Rect(c.left, c.top, c.right, c.bottom))
        }
    }

    fun toEdit() {
        val s = session ?: return
        viewModelScope.launch {
            ui = ui.copy(busy = "Preparing…", progress = null)
            try {
                val reset = withContext(Dispatchers.Default) { s.ensureWorking() }
                if (reset) {
                    if (ops.isNotEmpty()) message("The crop changed, so background edits were reset")
                    ops.clear()
                    redoStack.clear()
                }
                ui = ui.copy(busy = null, step = Step.EDIT, frame = min(ui.frame, s.frameCount - 1))
                renderEdit()
            } catch (e: Throwable) {
                ui = ui.copy(busy = null)
                message(e.message ?: "Something went wrong")
            }
        }
    }

    fun backToCrop() {
        ui = ui.copy(step = Step.CROP)
    }

    // ------------------------------------------------------------------- edit

    fun setTool(tool: Tool) {
        ui = ui.copy(tool = tool)
    }

    fun setBrushRadius(value: Float) {
        ui = ui.copy(brushRadius = value)
    }

    fun setTolerance(value: Int) {
        ui = ui.copy(tolerance = value)
    }

    fun setContiguous(value: Boolean) {
        ui = ui.copy(contiguous = value)
    }

    fun setOutline(on: Boolean) {
        ui = ui.copy(outline = on)
        renderEdit()
    }

    fun setOutlineWidth(width: Int) {
        ui = ui.copy(outlineWidth = width)
        if (ui.outline) renderEdit()
    }

    fun setOutlineWhite(white: Boolean) {
        ui = ui.copy(outlineWhite = white)
        if (ui.outline) renderEdit()
    }

    fun setAutoFit(on: Boolean) {
        ui = ui.copy(autoFit = on)
    }

    fun setFrame(frame: Int) {
        val s = session ?: return
        val f = frame.coerceIn(0, s.frameCount - 1)
        if (f == ui.frame) return
        ui = ui.copy(frame = f)
        renderEdit()
    }

    fun addStroke(points: FloatArray, erase: Boolean) {
        if (points.size < 2) return
        push(MaskOp.Brush(points, ui.brushRadius, erase))
    }

    fun wandAt(x: Float, y: Float) {
        push(MaskOp.Wand(x, y, ui.tolerance, ui.contiguous))
    }

    fun undo() {
        if (ops.isEmpty()) return
        redoStack += ops.removeAt(ops.lastIndex)
        renderEdit()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        ops += redoStack.removeAt(redoStack.lastIndex)
        renderEdit()
    }

    fun resetEdits() {
        if (ops.isEmpty()) return
        ops.clear()
        redoStack.clear()
        renderEdit()
    }

    private fun push(op: MaskOp) {
        ops += op
        redoStack.clear()
        renderEdit()
    }

    private fun outlineColor() = if (ui.outlineWhite) Color.WHITE else Color.BLACK

    private fun renderEdit() {
        val s = session ?: return
        if (!s.workingIsCurrent()) return
        val snapshot = ops.toList()
        val frame = ui.frame
        val outline = ui.outline
        val outlineWidth = ui.outlineWidth
        val color = outlineColor()
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            val image = withContext(Dispatchers.Default) {
                val bmp = s.renderPreview(frame, snapshot)
                if (outline) {
                    // Same visual thickness as the final 512 px sticker.
                    Outline.apply(bmp, outlineWidth * max(s.workWidth, s.workHeight) / 496f, color)
                }
                bmp.asImageBitmap()
            }
            ui = ui.copy(
                editImage = image,
                canUndo = ops.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                autoApplied = ops.contains(MaskOp.Auto),
            )
        }
    }

    /** Removes the background with ML Kit on every frame, then adds an "Auto" step that can be undone. */
    fun applyAuto() {
        val s = session ?: return
        if (ops.contains(MaskOp.Auto)) {
            message("Already applied. Use Undo to bring the background back.")
            return
        }
        viewModelScope.launch {
            try {
                ui = ui.copy(busy = "Preparing background removal…", progress = null)
                remover.ensureModel { p ->
                    viewModelScope.launch {
                        ui = ui.copy(busy = "Downloading the background remover (only the first time)…", progress = p)
                    }
                }
                for (i in 0 until s.frameCount) {
                    if (s.hasAutoMask(i)) continue
                    ui = ui.copy(
                        busy = if (s.frameCount > 1) "Removing background… frame ${i + 1} of ${s.frameCount}" else "Removing background…",
                        progress = if (s.frameCount > 1) i.toFloat() / s.frameCount else null,
                    )
                    val mask = withContext(Dispatchers.Default) {
                        val input = s.segmentationInput(i)
                        try {
                            remover.foregroundMask(input, s.workWidth, s.workHeight)
                        } finally {
                            input.recycle()
                        }
                    }
                    if (i == 0 && mask.count { (it.toInt() and 0xFF) > 127 } < mask.size / 400) {
                        ui = ui.copy(busy = null, progress = null)
                        message("No clear subject was found. Try the eraser or the magic wand.")
                        return@launch
                    }
                    s.setAutoMask(i, mask)
                }
                ui = ui.copy(busy = null, progress = null)
                push(MaskOp.Auto)
            } catch (e: Throwable) {
                ui = ui.copy(busy = null, progress = null)
                message(e.message ?: "Background removal failed")
            }
        }
    }

    // ------------------------------------------------------------------- save

    private fun finishOptions() = FinishOptions(
        outline = ui.outline,
        outlineWidth = ui.outlineWidth,
        outlineColor = outlineColor(),
        autoFit = ui.autoFit,
    )

    fun toSave() {
        val s = session ?: return
        val snapshot = ops.toList()
        val options = finishOptions()
        viewModelScope.launch {
            ui = ui.copy(busy = "Creating sticker…", progress = 0f)
            try {
                val bytes = withContext(Dispatchers.Default) {
                    val frames = s.renderFinalFrames(snapshot, options, onProgress = { p -> progress(0.35f * p) })
                    try {
                        if (frames.size > 1) {
                            WebpEncoder.encodeAnimated(frames, s.media.durationsMs, onProgress = { p -> progress(0.35f + 0.65f * p) })
                        } else {
                            WebpEncoder.encodeStill(frames[0])
                        }
                    } finally {
                        frames.forEach { it.recycle() }
                    }
                }
                val file = withContext(Dispatchers.IO) {
                    previewDir.listFiles()?.forEach { it.delete() }
                    File(previewDir, "preview_${System.currentTimeMillis()}.webp").apply { writeBytes(bytes) }
                }
                val chosen = ui.target
                val target = when {
                    !targetChosen -> defaultTarget()
                    chosen is SaveTarget.Existing && !canUse(chosen.packId) -> defaultTarget()
                    else -> chosen
                }
                ui = ui.copy(
                    busy = null,
                    progress = null,
                    step = Step.SAVE,
                    previewFile = file,
                    previewBytes = file.length(),
                    target = target,
                    newPackName = ui.newPackName.ifBlank { defaultNewPackName() },
                )
            } catch (e: NothingToSaveException) {
                ui = ui.copy(busy = null, progress = null)
                message(e.message ?: "Nothing to save")
            } catch (e: Throwable) {
                ui = ui.copy(busy = null, progress = null)
                message(e.message ?: "The sticker couldn't be created")
            }
        }
    }

    private fun progress(p: Float) {
        viewModelScope.launch { if (ui.busy != null) ui = ui.copy(progress = p) }
    }

    fun backToEdit() {
        ui = ui.copy(step = Step.EDIT)
    }

    private fun canUse(packId: String): Boolean = repository.pack(packId)?.let { !it.isFull } ?: false

    private fun defaultTarget(): SaveTarget {
        val sourceAnimated = (session?.frameCount ?: 1) > 1
        presetPackId?.let { id -> if (canUse(id)) return SaveTarget.Existing(id) }
        val best = repository.snapshot()
            .filter { it.animated == sourceAnimated && !it.isFull }
            .maxByOrNull { it.updatedAt }
        return best?.let { SaveTarget.Existing(it.id) } ?: SaveTarget.NewPack
    }

    private fun defaultNewPackName(): String {
        val n = repository.snapshot().size + 1
        return if (ui.animated) "My animated stickers $n" else "My stickers $n"
    }

    fun setStickerName(value: String) {
        ui = ui.copy(stickerName = value.take(com.localstickers.app.data.WhatsAppLimits.MAX_STATIC_A11Y))
    }

    fun setEmojis(value: List<String>) {
        ui = ui.copy(emojis = value)
    }

    fun setTarget(target: SaveTarget) {
        targetChosen = true
        ui = ui.copy(target = target)
    }

    fun setNewPackName(value: String) {
        ui = ui.copy(newPackName = value.take(com.localstickers.app.data.WhatsAppLimits.MAX_TEXT))
    }

    fun setNewPackPublisher(value: String) {
        ui = ui.copy(newPackPublisher = value.take(com.localstickers.app.data.WhatsAppLimits.MAX_TEXT))
    }

    fun setNewPackAnimated(value: Boolean) {
        ui = ui.copy(newPackAnimated = value)
    }

    fun save() {
        val s = session ?: return
        val preview = ui.previewFile ?: return
        val target = ui.target
        val snapshot = ops.toList()
        val options = finishOptions()
        val sourceAnimated = s.frameCount > 1
        viewModelScope.launch {
            ui = ui.copy(busy = "Saving…", progress = null)
            try {
                val existing = (target as? SaveTarget.Existing)?.let { repository.pack(it.packId) }
                if (target is SaveTarget.Existing && existing == null) throw IllegalStateException("That pack no longer exists")
                if (existing != null && existing.isFull) throw IllegalStateException("\"${existing.name}\" is full (30 stickers)")
                val packAnimated = existing?.animated ?: ui.newPackAnimated
                val bytes = withContext(Dispatchers.Default) {
                    when {
                        packAnimated == sourceAnimated -> preview.readBytes()
                        else -> {
                            // Mixed type: still image -> 2-frame animation, or GIF -> its first frame.
                            val first = s.renderFinalFrames(snapshot, options, frames = 0..0)[0]
                            try {
                                if (packAnimated) WebpEncoder.encodeStillAsAnimated(first) else WebpEncoder.encodeStill(first)
                            } finally {
                                first.recycle()
                            }
                        }
                    }
                }
                val packId = existing?.id ?: run {
                    val publisher = ui.newPackPublisher.ifBlank { "Me" }
                    Prefs.setPublisher(getApplication(), publisher)
                    repository.createPack(ui.newPackName.ifBlank { defaultNewPackName() }, publisher, packAnimated).id
                }
                repository.addSticker(packId, bytes, ui.stickerName, ui.emojis.ifEmpty { listOf(DEFAULT_EMOJI) })
                ui = ui.copy(busy = null, savedPackId = packId)
            } catch (e: Throwable) {
                ui = ui.copy(busy = null, progress = null)
                message(e.message ?: "The sticker couldn't be saved")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        renderJob?.cancel()
        // Bitmaps are left to the garbage collector: a background render may still be reading them.
        session = null
        remover.close()
        previewDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val PREVIEW_SIDE = 1280
    }
}
