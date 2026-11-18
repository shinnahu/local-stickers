package com.localstickers.app.whatsapp

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.localstickers.app.data.PackRepository
import com.localstickers.app.data.StickerPack
import com.localstickers.app.data.WhatsAppLimits
import com.localstickers.app.media.WebpInfo
import com.localstickers.app.provider.StickerContentProvider
import java.io.File

/** Talks to WhatsApp the way WhatsApp's official sample app does (intent + whitelist provider). */
object WhatsApp {
    const val CONSUMER = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"

    private const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    private const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
    private const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
    private const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"
    const val EXTRA_VALIDATION_ERROR = "validation_error"

    data class Status(
        val consumerInstalled: Boolean,
        val businessInstalled: Boolean,
        val inConsumer: Boolean,
        val inBusiness: Boolean,
    ) {
        val anyInstalled get() = consumerInstalled || businessInstalled
        val addedSomewhere get() = (consumerInstalled && inConsumer) || (businessInstalled && inBusiness)
        val addedEverywhere get() = anyInstalled && (!consumerInstalled || inConsumer) && (!businessInstalled || inBusiness)
    }

    fun isInstalled(pm: PackageManager, packageName: String): Boolean = try {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(packageName, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Asks WhatsApp's own provider whether it already has the pack. Runs a query: call off the main thread. */
    fun status(context: Context, packId: String): Status {
        val pm = context.packageManager
        val consumer = isInstalled(pm, CONSUMER)
        val business = isInstalled(pm, BUSINESS)
        return Status(
            consumerInstalled = consumer,
            businessInstalled = business,
            inConsumer = consumer && isAdded(context, CONSUMER, packId),
            inBusiness = business && isAdded(context, BUSINESS, packId),
        )
    }

    private fun isAdded(context: Context, whatsappPackage: String, packId: String): Boolean = try {
        val authority = "$whatsappPackage.provider.sticker_whitelist_check"
        @Suppress("DEPRECATION")
        val provider = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)
        if (provider == null) {
            false
        } else {
            val uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
                .appendPath("is_whitelisted")
                .appendQueryParameter("authority", StickerContentProvider.AUTHORITY)
                .appendQueryParameter("identifier", packId)
                .build()
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("result")) == 1
            } ?: false
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Intent that opens WhatsApp's "Add sticker pack" dialog. Targets the WhatsApp app(s) that don't have the pack
     * yet; when the pack is everywhere already (an update), it goes to the installed app(s) again.
     */
    fun addPackIntent(pack: StickerPack, status: Status): Intent? {
        if (!status.anyInstalled) return null
        val intent = Intent(ACTION_ENABLE_STICKER_PACK)
            .putExtra(EXTRA_STICKER_PACK_ID, pack.id)
            .putExtra(EXTRA_STICKER_PACK_AUTHORITY, StickerContentProvider.AUTHORITY)
            .putExtra(EXTRA_STICKER_PACK_NAME, pack.name)
        val missingConsumer = status.consumerInstalled && !status.inConsumer
        val missingBusiness = status.businessInstalled && !status.inBusiness
        return when {
            missingConsumer && missingBusiness -> Intent.createChooser(intent, "Add to WhatsApp")
            missingConsumer -> intent.setPackage(CONSUMER)
            missingBusiness -> intent.setPackage(BUSINESS)
            status.consumerInstalled && status.businessInstalled -> Intent.createChooser(intent, "Update in WhatsApp")
            status.consumerInstalled -> intent.setPackage(CONSUMER)
            else -> intent.setPackage(BUSINESS)
        }
    }

    /**
     * Local copy of the checks WhatsApp runs (from StickerPackValidator in the official sample), so problems are
     * shown here with a clear message instead of a generic WhatsApp error. Returns a list of problems.
     */
    fun validate(repository: PackRepository, pack: StickerPack): List<String> {
        val problems = ArrayList<String>()
        val count = pack.stickers.size
        if (count < WhatsAppLimits.MIN_STICKERS) {
            problems += "A pack needs at least ${WhatsAppLimits.MIN_STICKERS} stickers (it has $count)."
        }
        if (count > WhatsAppLimits.MAX_STICKERS) {
            problems += "A pack can have at most ${WhatsAppLimits.MAX_STICKERS} stickers (it has $count)."
        }
        if (pack.name.isBlank() || pack.name.length > WhatsAppLimits.MAX_TEXT) problems += "The pack name must be 1–128 characters."
        if (pack.publisher.isBlank() || pack.publisher.length > WhatsAppLimits.MAX_TEXT) problems += "The author must be 1–128 characters."
        val tray = repository.trayFile(pack)
        if (tray == null || !tray.isFile) {
            problems += "The pack icon is missing."
        } else if (tray.length() > WhatsAppLimits.TRAY_MAX_BYTES) {
            problems += "The pack icon is larger than 50 KB."
        }
        for ((index, sticker) in pack.stickers.withIndex()) {
            val label = "Sticker ${index + 1}"
            val file: File = repository.stickerFile(pack.id, sticker)
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null) {
                problems += "$label: file is missing."
                continue
            }
            val limit = if (pack.animated) WhatsAppLimits.ANIMATED_MAX_BYTES else WhatsAppLimits.STATIC_MAX_BYTES
            if (bytes.size > limit) problems += "$label is ${bytes.size / 1024} KB (max ${limit / 1024} KB)."
            val info = WebpInfo.parse(bytes)
            when {
                info == null -> problems += "$label is not a valid WebP file."
                info.width != WhatsAppLimits.STICKER_SIZE || info.height != WhatsAppLimits.STICKER_SIZE ->
                    problems += "$label is ${info.width}x${info.height} (must be 512x512)."
                pack.animated && info.frameCount <= 1 -> problems += "$label is not animated, but the pack is animated."
                !pack.animated && info.animated -> problems += "$label is animated, but the pack is static."
                pack.animated && info.totalDurationMs > WhatsAppLimits.MAX_ANIMATION_MS -> problems += "$label lasts more than 10 seconds."
                pack.animated && info.frameDurationsMs.any { it < WhatsAppLimits.MIN_FRAME_MS } -> problems += "$label has frames shorter than 8 ms."
            }
            if (sticker.emojis.isEmpty() || sticker.emojis.size > WhatsAppLimits.MAX_EMOJIS) problems += "$label needs 1–3 emojis."
            if (sticker.name.length > pack.a11yLimit) problems += "$label: name is longer than ${pack.a11yLimit} characters."
        }
        return problems
    }
}
