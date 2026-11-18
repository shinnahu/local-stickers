package com.localstickers.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

object BitmapUtils {

    /** Decodes the first frame of an image file (works for static and animated WebP). */
    fun decodeFirstFrame(file: File): Bitmap? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return decodeFirstFrame(bytes)
    }

    fun decodeFirstFrame(bytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val decoded = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
            if (decoded != null) return ensureArgb(decoded)
        }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { ensureArgb(it) }
    }

    fun ensureArgb(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        bitmap.recycle()
        return copy
    }

    fun toPng(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

    /** 96x96 tray icon for WhatsApp, made from a 512x512 sticker frame. */
    fun makeTrayIcon(stickerFrame: Bitmap): ByteArray {
        val size = com.localstickers.app.data.WhatsAppLimits.TRAY_SIZE
        // Two bilinear steps (512 -> 192 -> 96) look much smoother than one big jump.
        val half = Bitmap.createScaledBitmap(stickerFrame, size * 2, size * 2, true)
        val scaled = Bitmap.createScaledBitmap(half, size, size, true)
        if (half !== stickerFrame && half !== scaled) half.recycle()
        var png = toPng(scaled)
        if (png.size > com.localstickers.app.data.WhatsAppLimits.TRAY_MAX_BYTES) {
            // Practically impossible for 96x96, but stay within WhatsApp's limit no matter what.
            png = WebpEncoder.encodeStill(scaled, com.localstickers.app.data.WhatsAppLimits.TRAY_MAX_BYTES)
        }
        if (scaled !== stickerFrame) scaled.recycle()
        return png
    }
}
