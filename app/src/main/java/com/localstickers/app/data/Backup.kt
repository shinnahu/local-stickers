package com.localstickers.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import com.localstickers.app.media.BitmapUtils
import com.localstickers.app.media.WebpEncoder
import com.localstickers.app.media.WebpInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max

@Serializable
data class BackupManifest(
    val format: String = Backup.FORMAT,
    val version: Int = Backup.VERSION,
    val exportedAt: Long = 0L,
    val packs: List<StickerPack> = emptyList(),
)

/** A pack found in a backup file, with the sticker files that passed WhatsApp's checks. */
class PlannedPack(
    val meta: StickerPack,
    val files: Map<String, File>,
    val rejected: Int,
    val existsLocally: Boolean,
    /** Loose sticker files (not from our own backup) always become a new pack. */
    val alwaysNew: Boolean,
)

class ImportPlan(val workDir: File, val packs: List<PlannedPack>) {
    val stickerCount: Int get() = packs.sumOf { it.files.size }
    val rejectedCount: Int get() = packs.sumOf { it.rejected }
    val conflicts: Int get() = packs.count { it.existsLocally && !it.alwaysNew }
}

/**
 * Backup file = ZIP with
 *   backup.json                       pack + sticker metadata (names, emojis, order, pack type, ids)
 *   packs/<packId>/<sticker>.webp     the sticker files, exactly as WhatsApp receives them
 * Importing also accepts ZIPs of loose .webp stickers (for example ".wastickers" files: title.txt / author.txt).
 */
object Backup {
    const val FORMAT = "local-stickers-backup"
    const val VERSION = 1
    const val MIME = "application/zip"
    private const val MANIFEST = "backup.json"
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    private const val MAX_ENTRIES = 10_000

    fun suggestedName(packs: List<StickerPack>): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val base = if (packs.size == 1) {
            packs[0].name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '-').ifBlank { "stickers" }
        } else {
            "local-stickers"
        }
        return "$base-backup-$date.zip"
    }

    // ------------------------------------------------------------------ export

    suspend fun exportTo(context: Context, target: Uri, packs: List<StickerPack>) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(target, "w") ?: throw IOException("Can't write the backup file")
        out.use { writeZip(PackRepository.get(context), it, packs) }
    }

    /** Writes the backup into the app cache (for the share sheet) and returns the file. */
    suspend fun exportToCache(context: Context, packs: List<StickerPack>): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, suggestedName(packs))
        file.outputStream().use { writeZip(PackRepository.get(context), it, packs) }
        file
    }

    private fun writeZip(repo: PackRepository, os: OutputStream, packs: List<StickerPack>) {
        ZipOutputStream(BufferedOutputStream(os)).use { zip ->
            val manifest = BackupManifest(
                exportedAt = System.currentTimeMillis(),
                packs = packs.map { it.copy(syncedVersion = 0) },
            )
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(PackRepository.json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            for (pack in packs) {
                for (sticker in pack.stickers) {
                    val file = repo.stickerFile(pack.id, sticker)
                    if (!file.isFile) continue
                    zip.putNextEntry(ZipEntry("packs/${pack.id}/${sticker.fileName}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    // ------------------------------------------------------------------ import

    /** Unpacks and checks a backup. Nothing is added until [runImport]. */
    suspend fun prepareImport(context: Context, source: Uri): ImportPlan = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "import/${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val input = context.contentResolver.openInputStream(source) ?: throw IOException("Can't open the file")
            input.use { unzip(it, work) }
            val repo = PackRepository.get(context)
            val manifestFile = File(work, MANIFEST)
            val packs = if (manifestFile.isFile) planFromManifest(repo, work, manifestFile) else planFromLooseFiles(work)
            if (packs.none { it.files.isNotEmpty() }) {
                throw IOException("No usable stickers found in this file. Pick a backup made with this app or a ZIP of WhatsApp stickers.")
            }
            ImportPlan(work, packs)
        } catch (e: Exception) {
            work.deleteRecursively()
            throw e
        }
    }

    suspend fun runImport(context: Context, plan: ImportPlan, asCopies: Boolean): List<ImportOutcome> {
        val repo = PackRepository.get(context)
        try {
            return plan.packs.filter { it.files.isNotEmpty() }.map { planned ->
                repo.importPack(planned.meta, planned.files, asCopy = asCopies || planned.alwaysNew)
            }
        } finally {
            discard(plan)
        }
    }

    suspend fun discard(plan: ImportPlan) = withContext(Dispatchers.IO) { plan.workDir.deleteRecursively() }

    fun clearLeftovers(context: Context) {
        File(context.cacheDir, "import").deleteRecursively()
    }

    private fun unzip(input: InputStream, dest: File) {
        val root = dest.canonicalPath + File.separator
        var total = 0L
        var count = 0
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw IOException("The archive has too many files")
                if (entry.isDirectory) continue
                val out = File(dest, entry.name.replace('\\', '/'))
                if (!out.canonicalPath.startsWith(root)) throw IOException("The archive contains an invalid path")
                out.parentFile?.mkdirs()
                var written = 0L
                out.outputStream().use { os ->
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        written += n
                        total += n
                        if (written > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) throw IOException("The archive is too large")
                        os.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    private fun safeChild(root: File, relative: String): File? {
        val file = File(root, relative)
        return file.takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) && it.isFile }
    }

    private fun planFromManifest(repo: PackRepository, work: File, manifestFile: File): List<PlannedPack> {
        val manifest = try {
            PackRepository.json.decodeFromString(BackupManifest.serializer(), manifestFile.readText())
        } catch (e: Exception) {
            throw IOException("The backup file is damaged (backup.json can't be read)", e)
        }
        if (manifest.format != FORMAT) throw IOException("This is not a Local Stickers backup")
        if (manifest.version > VERSION) throw IOException("This backup was made by a newer version of the app")
        return manifest.packs.map { pack ->
            val files = LinkedHashMap<String, File>()
            var rejected = 0
            for (sticker in pack.stickers) {
                val file = safeChild(work, "packs/${pack.id}/${sticker.fileName}")
                if (file != null && isValidSticker(file, pack.animated)) files[sticker.fileName] = file else rejected++
            }
            PlannedPack(pack, files, rejected, existsLocally = repo.pack(pack.id) != null, alwaysNew = false)
        }
    }

    /** ZIP without backup.json: treat every .webp inside as a sticker (".wastickers" style). */
    private fun planFromLooseFiles(work: File): List<PlannedPack> {
        val all = work.walkTopDown().filter { it.isFile }.toList()
        val title = all.firstOrNull { it.name.equals("title.txt", true) }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        val author = all.firstOrNull { it.name.equals("author.txt", true) }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        val candidates = all.filter { it.extension.equals("webp", true) }.sortedBy { it.path }
        if (candidates.isEmpty()) return emptyList()

        val infos = candidates.map { it to runCatching { WebpInfo.parse(it.readBytes()) }.getOrNull() }
        val animatedCount = infos.count { it.second?.animated == true }
        val animated = animatedCount * 2 > infos.size
        var rejected = 0
        val usable = ArrayList<File>()
        for ((file, info) in infos) {
            when {
                info == null -> rejected++
                isValidSticker(file, animated) -> usable += file
                !animated && !info.animated -> convertToSticker(file)?.let { usable += it } ?: rejected++
                else -> rejected++
            }
        }
        val now = System.currentTimeMillis()
        return usable.chunked(WhatsAppLimits.MAX_STICKERS).mapIndexed { index, chunk ->
            val files = LinkedHashMap<String, File>()
            val stickers = chunk.mapIndexed { i, f ->
                val key = "loose_${index}_$i.webp"
                files[key] = f
                Sticker(id = "loose_${index}_$i", fileName = key, createdAt = now)
            }
            val baseName = title ?: "Imported stickers"
            val name = if (index == 0) baseName else "$baseName (${index + 1})"
            PlannedPack(
                meta = StickerPack(
                    id = "import_$index",
                    name = name,
                    publisher = author ?: "Me",
                    animated = animated,
                    stickers = stickers,
                    createdAt = now,
                    updatedAt = now,
                ),
                files = files,
                rejected = if (index == 0) rejected else 0,
                existsLocally = false,
                alwaysNew = true,
            )
        }
    }

    private fun isValidSticker(file: File, animated: Boolean): Boolean {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        val limit = if (animated) WhatsAppLimits.ANIMATED_MAX_BYTES else WhatsAppLimits.STATIC_MAX_BYTES
        if (bytes.size > limit) return false
        val info = WebpInfo.parse(bytes) ?: return false
        if (info.width != WhatsAppLimits.STICKER_SIZE || info.height != WhatsAppLimits.STICKER_SIZE) return false
        return if (animated) {
            info.frameCount > 1 &&
                info.totalDurationMs <= WhatsAppLimits.MAX_ANIMATION_MS &&
                info.frameDurationsMs.all { it >= WhatsAppLimits.MIN_FRAME_MS }
        } else {
            !info.animated
        }
    }

    /** Fits a still image that isn't sticker-sized into 512x512 and re-encodes it within 100 KB. */
    private fun convertToSticker(file: File): File? = runCatching {
        val src = BitmapUtils.decodeFirstFrame(file) ?: return null
        val size = WhatsAppLimits.STICKER_SIZE
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scale = size.toFloat() / max(src.width, src.height)
        val w = src.width * scale
        val h = src.height * scale
        Canvas(out).drawBitmap(
            src,
            null,
            RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        src.recycle()
        val bytes = WebpEncoder.encodeStill(out)
        out.recycle()
        File(file.parentFile, file.nameWithoutExtension + "_512.webp").apply { writeBytes(bytes) }
    }.getOrNull()
}
