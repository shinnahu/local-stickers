package com.localstickers.app.data

import android.content.Context
import com.localstickers.app.media.BitmapUtils
import com.localstickers.app.provider.StickerContentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

data class ImportOutcome(
    val packId: String,
    val packName: String,
    val added: Int,
    val skipped: Int,
    val merged: Boolean,
)

/**
 * Stores everything on the device, in the app's private folder:
 *   files/packs/<packId>/pack.json      metadata
 *   files/packs/<packId>/<stickerId>.webp
 *   files/packs/<packId>/tray_<stickerId>.png
 *
 * The same instance serves the UI and the ContentProvider WhatsApp reads from.
 */
class PackRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "packs").apply { mkdirs() }
    private val lock = Any()
    private val _packs = MutableStateFlow(loadAll())

    val packs: StateFlow<List<StickerPack>> = _packs.asStateFlow()

    fun snapshot(): List<StickerPack> = _packs.value

    fun pack(id: String): StickerPack? = _packs.value.firstOrNull { it.id == id }

    fun packDir(packId: String) = File(root, packId)

    fun stickerFile(packId: String, sticker: Sticker) = File(packDir(packId), sticker.fileName)

    fun trayFile(pack: StickerPack): File? = pack.trayFile?.let { File(packDir(pack.id), it) }

    // ------------------------------------------------------------------ packs

    suspend fun createPack(name: String, publisher: String, animated: Boolean): StickerPack = io {
        val now = System.currentTimeMillis()
        val pack = StickerPack(
            id = newPackId(),
            name = cleanText(name, "My stickers"),
            publisher = cleanText(publisher, "Me"),
            animated = animated,
            createdAt = now,
            updatedAt = now,
        )
        synchronized(lock) { commit(pack) }
        pack
    }

    suspend fun updatePackInfo(packId: String, name: String, publisher: String) = edit(packId) { p ->
        p.copy(name = cleanText(name, p.name), publisher = cleanText(publisher, p.publisher)).bumped()
    }

    suspend fun deletePack(packId: String) {
        io {
            synchronized(lock) {
                packDir(packId).deleteRecursively()
                _packs.value = _packs.value.filterNot { it.id == packId }
            }
        }
        notifyChanged(packId)
    }

    /** Remember that WhatsApp has received the current version of the pack. */
    suspend fun markSynced(packId: String) = edit(packId, notify = false) { it.copy(syncedVersion = it.imageDataVersion) }

    // --------------------------------------------------------------- stickers

    suspend fun addSticker(packId: String, webp: ByteArray, name: String, emojis: List<String>): Sticker {
        val sticker = io {
            synchronized(lock) {
                val pack = pack(packId) ?: throw IllegalStateException("Pack not found")
                check(!pack.isFull) { "This pack already has ${WhatsAppLimits.MAX_STICKERS} stickers" }
                val id = newStickerId()
                val sticker = Sticker(
                    id = id,
                    fileName = "$id.webp",
                    name = name.trim().take(pack.a11yLimit),
                    emojis = cleanEmojis(emojis),
                    createdAt = System.currentTimeMillis(),
                )
                writeFile(File(packDir(packId).apply { mkdirs() }, sticker.fileName), webp)
                commit(withTray(pack.copy(stickers = pack.stickers + sticker)).bumped())
                sticker
            }
        }
        notifyChanged(packId)
        return sticker
    }

    suspend fun updateSticker(packId: String, stickerId: String, name: String, emojis: List<String>) = edit(packId) { p ->
        p.copy(
            stickers = p.stickers.map {
                if (it.id == stickerId) it.copy(name = name.trim().take(p.a11yLimit), emojis = cleanEmojis(emojis)) else it
            },
        ).bumped()
    }

    suspend fun deleteSticker(packId: String, stickerId: String) = edit(packId) { p ->
        val s = p.stickers.firstOrNull { it.id == stickerId } ?: return@edit p
        File(packDir(packId), s.fileName).delete()
        withTray(p.copy(stickers = p.stickers - s, traySourceId = p.traySourceId.takeUnless { it == stickerId })).bumped()
    }

    suspend fun setTraySticker(packId: String, stickerId: String) = edit(packId) { p ->
        withTray(p.copy(traySourceId = stickerId)).bumped()
    }

    suspend fun moveSticker(fromId: String, stickerId: String, toId: String) {
        io {
            synchronized(lock) {
                val from = pack(fromId) ?: throw IllegalStateException("Pack not found")
                val to = pack(toId) ?: throw IllegalStateException("Pack not found")
                require(from.animated == to.animated) { "Static and animated stickers can't share a pack" }
                check(!to.isFull) { "\"${to.name}\" already has ${WhatsAppLimits.MAX_STICKERS} stickers" }
                val s = from.stickers.firstOrNull { it.id == stickerId } ?: return@synchronized
                val moved = if (to.stickers.any { it.fileName == s.fileName || it.id == s.id }) {
                    val nid = newStickerId()
                    s.copy(id = nid, fileName = "$nid.webp")
                } else {
                    s
                }
                val src = File(packDir(fromId), s.fileName)
                src.copyTo(File(packDir(toId).apply { mkdirs() }, moved.fileName), overwrite = true)
                commit(withTray(to.copy(stickers = to.stickers + moved)).bumped())
                src.delete()
                commit(
                    withTray(
                        from.copy(stickers = from.stickers - s, traySourceId = from.traySourceId.takeUnless { it == stickerId }),
                    ).bumped(),
                )
            }
        }
        notifyChanged(fromId)
        notifyChanged(toId)
    }

    // ----------------------------------------------------------------- import

    /**
     * Adds a pack coming from a backup file.
     * @param files extracted, already validated sticker files keyed by the file name used in the backup.
     * @param asCopy true: always create a new pack. false: if a pack with the same id exists, add the stickers it
     *               doesn't have yet (that is how a newer backup "updates" a pack).
     */
    suspend fun importPack(incoming: StickerPack, files: Map<String, File>, asCopy: Boolean): ImportOutcome {
        val outcome = io {
            synchronized(lock) {
                val existing = if (asCopy) null else pack(incoming.id)?.takeIf { it.animated == incoming.animated }
                if (existing != null) mergeLocked(existing, incoming, files) else createFromImportLocked(incoming, files, asCopy)
            }
        }
        notifyChanged(outcome.packId)
        return outcome
    }

    private fun mergeLocked(existing: StickerPack, incoming: StickerPack, files: Map<String, File>): ImportOutcome {
        val knownIds = existing.stickers.map { it.id }.toSet()
        val candidates = incoming.stickers.filter { it.id !in knownIds && files.containsKey(it.fileName) }
        val room = (WhatsAppLimits.MAX_STICKERS - existing.stickers.size).coerceAtLeast(0)
        val dir = packDir(existing.id).apply { mkdirs() }
        val added = candidates.take(room).map { s ->
            val id = if (SAFE_ID.matches(s.id) && existing.stickers.none { it.id == s.id }) s.id else newStickerId()
            val target = Sticker(id, "$id.webp", s.name.trim().take(existing.a11yLimit), cleanEmojis(s.emojis), s.createdAt)
            files.getValue(s.fileName).copyTo(File(dir, target.fileName), overwrite = true)
            target
        }
        if (added.isNotEmpty()) commit(withTray(existing.copy(stickers = existing.stickers + added)).bumped())
        return ImportOutcome(existing.id, existing.name, added.size, incoming.stickers.size - added.size, merged = true)
    }

    private fun createFromImportLocked(incoming: StickerPack, files: Map<String, File>, asCopy: Boolean): ImportOutcome {
        val packId = if (!asCopy && SAFE_ID.matches(incoming.id) && !packDir(incoming.id).exists()) incoming.id else newPackId()
        val dir = packDir(packId).apply { mkdirs() }
        val idMap = HashMap<String, String>()
        val limit = if (incoming.animated) WhatsAppLimits.MAX_ANIMATED_A11Y else WhatsAppLimits.MAX_STATIC_A11Y
        val stickers = incoming.stickers.filter { files.containsKey(it.fileName) }.take(WhatsAppLimits.MAX_STICKERS).map { s ->
            val id = if (!asCopy && SAFE_ID.matches(s.id) && s.id !in idMap.values) s.id else newStickerId()
            idMap[s.id] = id
            files.getValue(s.fileName).copyTo(File(dir, "$id.webp"), overwrite = true)
            Sticker(id, "$id.webp", s.name.trim().take(limit), cleanEmojis(s.emojis), s.createdAt)
        }
        val now = System.currentTimeMillis()
        val pack = StickerPack(
            id = packId,
            name = cleanText(incoming.name, "Imported stickers"),
            publisher = cleanText(incoming.publisher, "Me"),
            animated = incoming.animated,
            stickers = stickers,
            traySourceId = incoming.traySourceId?.let { idMap[it] },
            imageDataVersion = 1,
            syncedVersion = 0,
            createdAt = incoming.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = now,
        )
        commit(withTray(pack))
        return ImportOutcome(packId, pack.name, stickers.size, incoming.stickers.size - stickers.size, merged = false)
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun edit(packId: String, notify: Boolean = true, transform: (StickerPack) -> StickerPack) {
        io {
            synchronized(lock) {
                val p = pack(packId) ?: return@synchronized
                val n = transform(p)
                if (n != p) commit(n)
            }
        }
        if (notify) notifyChanged(packId)
    }

    private fun commit(pack: StickerPack) {
        writePackJson(pack)
        val list = _packs.value
        _packs.value = if (list.any { it.id == pack.id }) list.map { if (it.id == pack.id) pack else it } else list + pack
    }

    private fun StickerPack.bumped() = copy(imageDataVersion = imageDataVersion + 1, updatedAt = System.currentTimeMillis())

    /** Makes sure the tray icon matches the chosen (or first) sticker. */
    private fun withTray(pack: StickerPack): StickerPack {
        val dir = packDir(pack.id)
        val validChoice = pack.traySourceId?.takeIf { id -> pack.stickers.any { it.id == id } }
        val source = pack.stickers.firstOrNull { it.id == validChoice } ?: pack.stickers.firstOrNull()
        if (source == null) {
            pack.trayFile?.let { File(dir, it).delete() }
            return pack.copy(trayFile = null, traySourceId = null)
        }
        val wanted = "tray_${source.id}.png"
        val wantedFile = File(dir, wanted)
        if (pack.trayFile == wanted && wantedFile.isFile) return pack.copy(traySourceId = validChoice)
        val frame = BitmapUtils.decodeFirstFrame(File(dir, source.fileName)) ?: return pack.copy(traySourceId = validChoice)
        writeFile(wantedFile, BitmapUtils.makeTrayIcon(frame))
        frame.recycle()
        pack.trayFile?.takeIf { it != wanted }?.let { File(dir, it).delete() }
        return pack.copy(trayFile = wanted, traySourceId = validChoice)
    }

    private fun notifyChanged(packId: String) {
        val resolver = appContext.contentResolver
        runCatching {
            resolver.notifyChange(StickerContentProvider.metadataUri(), null)
            resolver.notifyChange(StickerContentProvider.metadataUri(packId), null)
            resolver.notifyChange(StickerContentProvider.stickersUri(packId), null)
        }
    }

    private fun loadAll(): List<StickerPack> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
            val file = File(dir, PACK_JSON)
            if (!file.isFile) return@mapNotNull null
            val pack = runCatching { json.decodeFromString(StickerPack.serializer(), file.readText()) }.getOrNull()
                ?: return@mapNotNull null
            if (pack.id != dir.name) return@mapNotNull null
            pack.copy(stickers = pack.stickers.filter { File(dir, it.fileName).isFile })
        }
    }

    private fun writePackJson(pack: StickerPack) {
        val dir = packDir(pack.id).apply { mkdirs() }
        writeFile(File(dir, PACK_JSON), json.encodeToString(StickerPack.serializer(), pack).toByteArray())
    }

    private fun writeFile(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not save ${target.name}" }
        }
    }

    private fun newPackId(): String {
        while (true) {
            val id = "pack_" + randomToken()
            if (!packDir(id).exists()) return id
        }
    }

    private fun newStickerId() = "st_" + randomToken()

    private fun randomToken() = UUID.randomUUID().toString().replace("-", "").take(16)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val PACK_JSON = "pack.json"

        /** Ids we create are [A-Za-z0-9_]; anything else coming from a backup gets a fresh id. */
        private val SAFE_ID = Regex("[A-Za-z0-9_]{1,64}")

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun cleanText(value: String, fallback: String): String =
            value.trim().replace(Regex("\\s+"), " ").take(WhatsAppLimits.MAX_TEXT).ifBlank { fallback }

        fun cleanEmojis(emojis: List<String>): List<String> =
            emojis.map { it.trim() }.filter { it.isNotEmpty() && !it.contains(',') }.distinct()
                .take(WhatsAppLimits.MAX_EMOJIS).ifEmpty { listOf(DEFAULT_EMOJI) }

        @Volatile
        private var instance: PackRepository? = null

        fun get(context: Context): PackRepository =
            instance ?: synchronized(this) { instance ?: PackRepository(context).also { instance = it } }
    }
}
