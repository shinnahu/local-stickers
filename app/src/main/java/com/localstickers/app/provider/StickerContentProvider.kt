package com.localstickers.app.provider

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.localstickers.app.BuildConfig
import com.localstickers.app.data.PackRepository
import com.localstickers.app.data.StickerPack
import com.localstickers.app.data.WhatsAppLimits
import java.io.File

/**
 * The interface WhatsApp uses to read sticker packs (same URIs and column names as WhatsApp's official sample
 * app). Packs are served straight from the app's private storage.
 */
class StickerContentProvider : ContentProvider() {

    private val repository by lazy { PackRepository.get(requireNotNull(context)) }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = when (MATCHER.match(uri)) {
        METADATA_CODE -> packCursor(uri, repository.snapshot().filter { it.isServable() })
        METADATA_CODE_FOR_SINGLE_PACK -> {
            val id = uri.lastPathSegment.orEmpty()
            packCursor(uri, listOfNotNull(repository.pack(id)?.takeIf { it.trayFile != null }))
        }
        STICKERS_CODE -> stickersCursor(uri, uri.lastPathSegment.orEmpty())
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (MATCHER.match(uri) != STICKERS_ASSET_CODE) return null
        val segments = uri.pathSegments
        if (segments.size != 3) throw IllegalArgumentException("path segments should be 3, uri is: $uri")
        val identifier = segments[1]
        val fileName = segments[2]
        val pack = repository.pack(identifier) ?: return null
        // Only files that belong to the pack can be read.
        val known = fileName == pack.trayFile || pack.stickers.any { it.fileName == fileName }
        if (!known) return null
        val file = File(repository.packDir(pack.id), fileName)
        if (!file.isFile) return null
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    override fun getType(uri: Uri): String = when (MATCHER.match(uri)) {
        METADATA_CODE -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$METADATA"
        METADATA_CODE_FOR_SINGLE_PACK -> "vnd.android.cursor.item/vnd.$AUTHORITY.$METADATA"
        STICKERS_CODE -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$STICKERS"
        STICKERS_ASSET_CODE -> if (uri.lastPathSegment.orEmpty().endsWith(".png")) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    private fun StickerPack.isServable() =
        trayFile != null && stickers.size in WhatsAppLimits.MIN_STICKERS..WhatsAppLimits.MAX_STICKERS

    private fun packCursor(uri: Uri, packs: List<StickerPack>): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                STICKER_PACK_IDENTIFIER_IN_QUERY,
                STICKER_PACK_NAME_IN_QUERY,
                STICKER_PACK_PUBLISHER_IN_QUERY,
                STICKER_PACK_ICON_IN_QUERY,
                ANDROID_APP_DOWNLOAD_LINK_IN_QUERY,
                IOS_APP_DOWNLOAD_LINK_IN_QUERY,
                PUBLISHER_EMAIL,
                PUBLISHER_WEBSITE,
                PRIVACY_POLICY_WEBSITE,
                LICENSE_AGREEMENT_WEBSITE,
                IMAGE_DATA_VERSION,
                AVOID_CACHE,
                ANIMATED_STICKER_PACK,
            ),
        )
        for (pack in packs) {
            cursor.newRow()
                .add(pack.id)
                .add(pack.name)
                .add(pack.publisher)
                .add(pack.trayFile)
                .add("")
                .add("")
                .add("")
                .add("")
                .add("")
                .add("")
                .add(pack.imageDataVersion.toString())
                .add(0)
                .add(if (pack.animated) 1 else 0)
        }
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    private fun stickersCursor(uri: Uri, identifier: String): Cursor {
        val cursor = MatrixCursor(
            arrayOf(STICKER_FILE_NAME_IN_QUERY, STICKER_FILE_EMOJI_IN_QUERY, STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY),
        )
        repository.pack(identifier)?.let { pack ->
            for (sticker in pack.stickers) {
                cursor.addRow(
                    arrayOf<Any?>(
                        sticker.fileName,
                        sticker.emojis.joinToString(","),
                        sticker.name.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Not supported")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Not supported")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Not supported")

    companion object {
        /** Must start with the package name (WhatsApp's sample enforces the same rule). */
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".stickercontentprovider"

        // Column names WhatsApp expects. Do not change.
        private const val STICKER_PACK_IDENTIFIER_IN_QUERY = "sticker_pack_identifier"
        private const val STICKER_PACK_NAME_IN_QUERY = "sticker_pack_name"
        private const val STICKER_PACK_PUBLISHER_IN_QUERY = "sticker_pack_publisher"
        private const val STICKER_PACK_ICON_IN_QUERY = "sticker_pack_icon"
        private const val ANDROID_APP_DOWNLOAD_LINK_IN_QUERY = "android_play_store_link"
        private const val IOS_APP_DOWNLOAD_LINK_IN_QUERY = "ios_app_download_link"
        private const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        private const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        private const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        private const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        private const val IMAGE_DATA_VERSION = "image_data_version"
        private const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        private const val ANIMATED_STICKER_PACK = "animated_sticker_pack"
        private const val STICKER_FILE_NAME_IN_QUERY = "sticker_file_name"
        private const val STICKER_FILE_EMOJI_IN_QUERY = "sticker_emoji"
        private const val STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY = "sticker_accessibility_text"

        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"

        private const val METADATA_CODE = 1
        private const val METADATA_CODE_FOR_SINGLE_PACK = 2
        private const val STICKERS_CODE = 3
        private const val STICKERS_ASSET_CODE = 4

        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, METADATA, METADATA_CODE)
            addURI(AUTHORITY, "$METADATA/*", METADATA_CODE_FOR_SINGLE_PACK)
            addURI(AUTHORITY, "$STICKERS/*", STICKERS_CODE)
            addURI(AUTHORITY, "$STICKERS_ASSET/*/*", STICKERS_ASSET_CODE)
        }

        private fun base(): Uri.Builder = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY)

        fun metadataUri(): Uri = base().appendPath(METADATA).build()

        fun metadataUri(packId: String): Uri = base().appendPath(METADATA).appendPath(packId).build()

        fun stickersUri(packId: String): Uri = base().appendPath(STICKERS).appendPath(packId).build()
    }
}
