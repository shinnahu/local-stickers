package com.localstickers.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import com.localstickers.app.data.WhatsAppLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Decoded source media: one frame for pictures, several for GIFs. */
class LoadedMedia(
    val frames: List<Bitmap>,
    val durationsMs: List<Int>,
    /** Human readable note when the GIF had to be shortened or thinned out. */
    val note: String? = null,
) {
    val animated: Boolean get() = frames.size > 1
    val width: Int get() = frames[0].width
    val height: Int get() = frames[0].height

    fun recycle() = frames.forEach { it.recycle() }
}

object MediaLoader {
    private const val MAX_FILE_BYTES = 60L * 1024 * 1024
    private const val MAX_STILL_SIDE = 2048
    private const val MAX_GIF_SIDE = 640
    /** Upper bound of frames kept in memory; WhatsApp's 500 KB limit rarely allows more anyway. */
    const val MAX_FRAMES = 40

    suspend fun load(context: Context, uri: Uri): LoadedMedia = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
            ?: throw IOException("Can't open the selected file")
        if (isGif(bytes)) {
            decodeGif(bytes)?.let { return@withContext it }
        }
        LoadedMedia(listOf(decodeStill(bytes)), listOf(0))
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_FILE_BYTES) throw IOException("The file is too big (max 60 MB)")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun isGif(b: ByteArray) =
        b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()

    // ------------------------------------------------------------------ stills

    private fun decodeStill(bytes: ByteArray): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val bitmap = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    val w = info.size.width
                    val h = info.size.height
                    val scale = min(1f, MAX_STILL_SIDE.toFloat() / max(w, h))
                    if (scale < 1f) {
                        decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
                    }
                }
            }.getOrNull()
            if (bitmap != null) return BitmapUtils.ensureArgb(bitmap)
        }
        return decodeStillLegacy(bytes)
    }

    private fun decodeStillLegacy(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("This image format isn't supported")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_STILL_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IOException("This image format isn't supported")
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        val scale = min(1f, MAX_STILL_SIDE.toFloat() / max(bitmap.width, bitmap.height))
        if (scale < 1f) matrix.postScale(scale, scale)
        if (!matrix.isIdentity) {
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed !== bitmap) bitmap.recycle()
            bitmap = transformed
        }
        return BitmapUtils.ensureArgb(bitmap)
    }

    // -------------------------------------------------------------------- GIFs

    private object FreshBitmaps : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap = Bitmap.createBitmap(width, height, config)
        override fun release(bitmap: Bitmap) = Unit
        override fun obtainByteArray(size: Int) = ByteArray(size)
        override fun release(bytes: ByteArray) = Unit
        override fun obtainIntArray(size: Int) = IntArray(size)
        override fun release(array: IntArray) = Unit
    }

    /**
     * Decodes a GIF frame by frame. Keeps at most 10 s (WhatsApp's limit) and at most [MAX_FRAMES] frames; when the
     * GIF has more, frames are skipped evenly and their time is added to the kept ones, so the speed stays the same.
     * Returns null for single-frame GIFs.
     */
    private fun decodeGif(bytes: ByteArray): LoadedMedia? {
        val header = GifHeaderParser().setData(bytes).parseHeader()
        if (header.status != GifDecoder.STATUS_OK || header.numFrames <= 1) return null
        var sample = 1
        while (max(header.width, header.height) / sample > MAX_GIF_SIDE * 2) sample *= 2
        val decoder = StandardGifDecoder(FreshBitmaps, header, ByteBuffer.wrap(bytes), sample)
        decoder.setDefaultBitmapConfig(Bitmap.Config.ARGB_8888)
        val total = decoder.frameCount

        // Frames that fit in 10 seconds.
        var usable = 0
        var elapsed = 0
        while (usable < total) {
            val d = decoder.getDelay(usable).coerceAtLeast(20)
            if (usable > 0 && elapsed + d > WhatsAppLimits.MAX_ANIMATION_MS) break
            elapsed += d
            usable++
        }
        val step = max(1, ceil(usable / MAX_FRAMES.toDouble()).toInt())

        val frames = ArrayList<Bitmap>()
        val durations = ArrayList<Int>()
        for (i in 0 until usable) {
            decoder.advance()
            val delay = decoder.getDelay(i).coerceAtLeast(20)
            val frame = decoder.nextFrame ?: break
            if (i % step == 0) {
                frames += fitGifFrame(frame)
                durations += delay
            } else {
                frame.recycle()
                durations[durations.lastIndex] = durations.last() + delay
            }
        }
        decoder.clear()
        if (frames.size <= 1) {
            frames.drop(1).forEach { it.recycle() }
            return frames.firstOrNull()?.let { LoadedMedia(listOf(it), listOf(0)) }
        }
        // A frame can't be longer than the whole 10 s budget.
        val capped = durations.map { it.coerceAtMost(WhatsAppLimits.MAX_ANIMATION_MS) }
        val notes = ArrayList<String>()
        if (usable < total) notes += "GIF shortened to the first ${elapsed / 1000.0}s (WhatsApp allows 10 s)"
        if (step > 1) notes += "frame rate reduced to keep the sticker small"
        return LoadedMedia(frames, capped, notes.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    private fun fitGifFrame(frame: Bitmap): Bitmap {
        val scale = min(1f, MAX_GIF_SIDE.toFloat() / max(frame.width, frame.height))
        val argb = if (frame.config == Bitmap.Config.ARGB_8888) frame else frame.copy(Bitmap.Config.ARGB_8888, false).also { frame.recycle() }
        if (scale >= 1f) return argb
        val scaled = Bitmap.createScaledBitmap(
            argb,
            (argb.width * scale).roundToInt().coerceAtLeast(1),
            (argb.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== argb) argb.recycle()
        return scaled
    }
}
