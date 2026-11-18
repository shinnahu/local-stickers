package com.localstickers.app.data

import kotlinx.serialization.Serializable

/**
 * Limits taken from WhatsApp's official sticker sample (StickerPackValidator.java and the Android README in
 * github.com/WhatsApp/stickers). WhatsApp rejects packs that break them.
 */
object WhatsAppLimits {
    const val STICKER_SIZE = 512
    const val TRAY_SIZE = 96
    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30
    const val STATIC_MAX_BYTES = 100 * 1024
    const val ANIMATED_MAX_BYTES = 500 * 1024
    const val TRAY_MAX_BYTES = 50 * 1024
    const val MIN_FRAME_MS = 8
    const val MAX_ANIMATION_MS = 10_000
    const val MAX_EMOJIS = 3
    const val MAX_TEXT = 128
    const val MAX_STATIC_A11Y = 125
    const val MAX_ANIMATED_A11Y = 255
}

const val DEFAULT_EMOJI = "😀"

@Serializable
data class Sticker(
    val id: String,
    val fileName: String,
    /** User-given name. Sent to WhatsApp as the sticker's accessibility text. */
    val name: String = "",
    /** 1..3 emojis, used by WhatsApp for search and categories. */
    val emojis: List<String> = listOf(DEFAULT_EMOJI),
    val createdAt: Long = 0L,
)

@Serializable
data class StickerPack(
    /** Identifier WhatsApp uses (together with the provider authority). Never changes. */
    val id: String,
    val name: String,
    val publisher: String,
    /** WhatsApp packs are either all static or all animated. */
    val animated: Boolean,
    val stickers: List<Sticker> = emptyList(),
    /** File name of the 96x96 tray icon inside the pack folder ("tray_<stickerId>.png"). */
    val trayFile: String? = null,
    /** Sticker explicitly chosen as the pack icon; null means "first sticker". */
    val traySourceId: String? = null,
    /** Bumped on every change so WhatsApp knows it has to refresh the pack. */
    val imageDataVersion: Int = 1,
    /** imageDataVersion at the moment the pack was last sent to WhatsApp (0 = never). */
    val syncedVersion: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val isFull: Boolean get() = stickers.size >= WhatsAppLimits.MAX_STICKERS
    val hasEnoughStickers: Boolean get() = stickers.size >= WhatsAppLimits.MIN_STICKERS
    val changedSinceSync: Boolean get() = syncedVersion != imageDataVersion
    val a11yLimit: Int get() = if (animated) WhatsAppLimits.MAX_ANIMATED_A11Y else WhatsAppLimits.MAX_STATIC_A11Y
}
