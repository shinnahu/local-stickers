package com.localstickers.app.media

import android.graphics.Bitmap
import android.os.Build
import com.localstickers.app.data.WhatsAppLimits
import java.io.ByteArrayOutputStream

class StickerTooLargeException(message: String) : Exception(message)

/** Encodes sticker frames to WebP files that respect WhatsApp's size limits. */
object WebpEncoder {

    @Suppress("DEPRECATION")
    private val legacyWebpFormat: Bitmap.CompressFormat
        get() = Bitmap.CompressFormat.WEBP

    private val lossyFormat: Bitmap.CompressFormat
        get() = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else legacyWebpFormat

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream(64 * 1024).use { out ->
            check(bitmap.compress(format, quality, out)) { "WebP encoding failed" }
            out.toByteArray()
        }

    private fun losslessOrNull(bitmap: Bitmap): ByteArray? = when {
        Build.VERSION.SDK_INT >= 30 -> compress(bitmap, Bitmap.CompressFormat.WEBP_LOSSLESS, 100)
        // On Android 10 the legacy WEBP format at quality 100 is lossless.
        Build.VERSION.SDK_INT == 29 -> compress(bitmap, legacyWebpFormat, 100)
        else -> null
    }

    /** Still WebP no larger than [maxBytes]: lossless when it fits, otherwise the highest lossy quality that fits. */
    fun encodeStill(bitmap: Bitmap, maxBytes: Int = WhatsAppLimits.STATIC_MAX_BYTES): ByteArray {
        losslessOrNull(bitmap)?.let { if (it.size <= maxBytes) return it }
        val top = compress(bitmap, lossyFormat, 95)
        if (top.size <= maxBytes) return top
        var lo = 0
        var hi = 94
        var best: ByteArray? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val data = compress(bitmap, lossyFormat, mid)
            if (data.size <= maxBytes) {
                best = data
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best ?: throw StickerTooLargeException("This image can't be compressed under ${maxBytes / 1024} KB")
    }

    /**
     * Animated WebP no larger than [maxBytes]. Lowers the quality first and, if that is not enough,
     * halves the frame rate (keeping the total duration) until the file fits.
     */
    fun encodeAnimated(
        frames: List<Bitmap>,
        durationsMs: List<Int>,
        maxBytes: Int = WhatsAppLimits.ANIMATED_MAX_BYTES,
        onProgress: (Float) -> Unit = {},
    ): ByteArray {
        require(frames.size >= 2 && frames.size == durationsMs.size)
        val width = frames[0].width
        val height = frames[0].height
        val qualities = intArrayOf(80, 66, 52, 40, 28)
        var curFrames = frames
        var curDurations = durationsMs.map { it.coerceAtLeast(WhatsAppLimits.MIN_FRAME_MS) }
        var pass = 0
        while (true) {
            for (q in qualities) {
                val encoded = ArrayList<AnimatedWebpMuxer.Frame>(curFrames.size)
                curFrames.forEachIndexed { i, bmp ->
                    encoded += AnimatedWebpMuxer.frameFromStillWebp(compress(bmp, lossyFormat, q), curDurations[i])
                    onProgress(passProgress(pass, (i + 1f) / curFrames.size))
                }
                val data = AnimatedWebpMuxer.mux(width, height, encoded)
                pass++
                if (data.size <= maxBytes) {
                    onProgress(1f)
                    return data
                }
                // Way over budget: lower qualities won't save it, drop frames first.
                if (data.size > maxBytes * 3 && curFrames.size > 6) break
            }
            if (curFrames.size <= 2) {
                throw StickerTooLargeException("This animation can't be compressed under ${maxBytes / 1024} KB")
            }
            val keptFrames = ArrayList<Bitmap>()
            val keptDurations = ArrayList<Int>()
            var i = 0
            while (i < curFrames.size) {
                keptFrames += curFrames[i]
                keptDurations += curDurations[i] + (if (i + 1 < curFrames.size) curDurations[i + 1] else 0)
                i += 2
            }
            if (keptFrames.size < 2) {
                throw StickerTooLargeException("This animation can't be compressed under ${maxBytes / 1024} KB")
            }
            curFrames = keptFrames
            curDurations = keptDurations
        }
    }

    /** A still image saved as a 2-frame "animation" so it can live in an animated pack. */
    fun encodeStillAsAnimated(bitmap: Bitmap, maxBytes: Int = WhatsAppLimits.ANIMATED_MAX_BYTES): ByteArray {
        val candidates = sequence {
            if (Build.VERSION.SDK_INT >= 30) yield(compress(bitmap, Bitmap.CompressFormat.WEBP_LOSSLESS, 100))
            for (q in intArrayOf(90, 75, 60, 45, 30, 15)) yield(compress(bitmap, lossyFormat, q))
        }
        for (still in candidates) {
            val frame = AnimatedWebpMuxer.frameFromStillWebp(still, 1000)
            val data = AnimatedWebpMuxer.mux(bitmap.width, bitmap.height, listOf(frame, frame))
            if (data.size <= maxBytes) return data
        }
        throw StickerTooLargeException("This image can't be compressed under ${maxBytes / 1024} KB")
    }

    private fun passProgress(pass: Int, fraction: Float): Float {
        // Each extra pass moves the bar further but never reaches 100% before success.
        val done = 1f - 1f / (pass + 1)
        val next = 1f - 1f / (pass + 2)
        return done + (next - done) * fraction
    }
}
