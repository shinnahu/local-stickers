package com.localstickers.app.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.localstickers.app.data.WhatsAppLimits
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One step of background editing. Coordinates are normalized to the cropped image (0..1). */
sealed class MaskOp {
    /** Keep only what ML Kit considers the subject. */
    data object Auto : MaskOp()

    /** Free-hand stroke. [points] = x0,y0,x1,y1... ; [radius] is relative to the longest image side. */
    class Brush(val points: FloatArray, val radius: Float, val erase: Boolean) : MaskOp()

    /** "Magic wand": removes pixels with a color similar to the tapped one. */
    class Wand(val x: Float, val y: Float, val tolerance: Int, val contiguous: Boolean) : MaskOp()
}

data class FinishOptions(
    val outline: Boolean,
    val outlineWidth: Int,
    val outlineColor: Int,
    val autoFit: Boolean,
)

class NothingToSaveException : Exception("Everything was erased. Restore part of the image first.")

/**
 * Holds the source frames and turns them into sticker frames:
 * orientation (rotate / flip) -> crop -> working frames (<= 512 px) -> mask ops -> final 512x512 sticker.
 * Every op is replayed on every GIF frame, so one edit applies to the whole animation.
 */
class EditSession(val media: LoadedMedia) {

    var rotation = 0
        private set
    var flipH = false
        private set
    /** Normalized crop rectangle in the rotated/flipped image. */
    var crop = RectF(0f, 0f, 1f, 1f)
        private set

    private var builtKey: String? = null
    var working: List<Bitmap> = emptyList()
        private set
    val workWidth: Int get() = working.firstOrNull()?.width ?: 0
    val workHeight: Int get() = working.firstOrNull()?.height ?: 0
    val frameCount: Int get() = media.frames.size

    /** ML Kit masks per frame (working size), filled by [setAutoMask]. */
    private var autoMasks: Array<ByteArray?> = arrayOfNulls(media.frames.size)

    private var cacheFrame = -1
    private var cacheOps: List<MaskOp> = emptyList()
    private var cacheMask: ByteArray? = null

    val orientedWidth: Int get() = if (rotation % 180 == 0) media.width else media.height
    val orientedHeight: Int get() = if (rotation % 180 == 0) media.height else media.width

    // ------------------------------------------------------------ orientation

    /** Rotates the visible image 90° clockwise (the crop rectangle follows). */
    fun rotateClockwise() {
        rotation = if (flipH) (rotation + 270) % 360 else (rotation + 90) % 360
        crop = RectF(1f - crop.bottom, crop.left, 1f - crop.top, crop.right)
    }

    fun flipHorizontal() {
        flipH = !flipH
        crop = RectF(1f - crop.right, crop.top, 1f - crop.left, crop.bottom)
    }

    fun setCropRect(rect: RectF) {
        val l = rect.left.coerceIn(0f, 1f)
        val t = rect.top.coerceIn(0f, 1f)
        val r = rect.right.coerceIn(l, 1f)
        val b = rect.bottom.coerceIn(t, 1f)
        crop = RectF(l, t, r, b)
    }

    fun resetOrientationAndCrop() {
        rotation = 0
        flipH = false
        crop = RectF(0f, 0f, 1f, 1f)
    }

    /** src pixel -> oriented pixel. */
    private fun orientationMatrix(srcW: Int, srcH: Int): Matrix {
        val m = Matrix()
        when (rotation) {
            90 -> { m.postRotate(90f); m.postTranslate(srcH.toFloat(), 0f) }
            180 -> { m.postRotate(180f); m.postTranslate(srcW.toFloat(), srcH.toFloat()) }
            270 -> { m.postRotate(270f); m.postTranslate(0f, srcW.toFloat()) }
        }
        if (flipH) {
            val ow = if (rotation % 180 == 0) srcW else srcH
            m.postScale(-1f, 1f)
            m.postTranslate(ow.toFloat(), 0f)
        }
        return m
    }

    /** First frame, rotated/flipped, fitting in [maxSide]. Used by the crop screen. */
    fun orientedPreview(maxSide: Int): Bitmap {
        val src = media.frames[0]
        val ow = orientedWidth.toFloat()
        val oh = orientedHeight.toFloat()
        val scale = min(1f, maxSide / max(ow, oh))
        val out = Bitmap.createBitmap(max(1, (ow * scale).roundToInt()), max(1, (oh * scale).roundToInt()), Bitmap.Config.ARGB_8888)
        val m = orientationMatrix(src.width, src.height).apply { postScale(scale, scale) }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    // --------------------------------------------------------- working frames

    private fun currentKey() = "$rotation|$flipH|${crop.left}|${crop.top}|${crop.right}|${crop.bottom}"

    fun workingIsCurrent(): Boolean = builtKey == currentKey() && working.isNotEmpty()

    /** Rebuilds the cropped frames if orientation/crop changed. Returns true when edits had to be reset. */
    @Synchronized
    fun ensureWorking(): Boolean {
        if (workingIsCurrent()) return false
        // Old frames are not recycled: a preview render may still be reading them.
        working = media.frames.map { cropFrame(it, WhatsAppLimits.STICKER_SIZE) }
        builtKey = currentKey()
        autoMasks = arrayOfNulls(media.frames.size)
        cacheFrame = -1
        cacheMask = null
        cacheOps = emptyList()
        return true
    }

    /** Oriented + cropped frame whose longest side is [maxSide]. */
    fun cropFrame(source: Bitmap, maxSide: Int): Bitmap {
        var src = source
        val temps = ArrayList<Bitmap>()
        fun cropPx(b: Bitmap): RectF {
            val ow = if (rotation % 180 == 0) b.width else b.height
            val oh = if (rotation % 180 == 0) b.height else b.width
            return RectF(crop.left * ow, crop.top * oh, crop.right * ow, crop.bottom * oh)
        }
        var c = cropPx(src)
        var scale = maxSide / max(1f, max(c.width(), c.height()))
        // Downscale in halves first: a single big bilinear step would alias.
        while (scale < 0.5f && src.width > 4 && src.height > 4) {
            src = Bitmap.createScaledBitmap(src, src.width / 2, src.height / 2, true).also { temps += it }
            c = cropPx(src)
            scale = maxSide / max(1f, max(c.width(), c.height()))
        }
        val outW = max(1, (c.width() * scale).roundToInt())
        val outH = max(1, (c.height() * scale).roundToInt())
        val m = orientationMatrix(src.width, src.height).apply {
            postTranslate(-c.left, -c.top)
            postScale(scale, scale)
        }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        temps.forEach { it.recycle() }
        return out
    }

    /** Larger version of a frame for ML Kit, which works best with images of at least 512 px. */
    fun segmentationInput(frame: Int): Bitmap =
        cropFrame(media.frames[frame], if (media.animated) 768 else 1024)

    fun hasAutoMask(frame: Int) = autoMasks.getOrNull(frame) != null

    @Synchronized
    fun setAutoMask(frame: Int, mask: ByteArray) {
        require(mask.size == workWidth * workHeight)
        autoMasks[frame] = mask
    }

    // ------------------------------------------------------------------ masks

    /** Mask for [frame] after [ops]; reuses the previous result when ops were only appended. */
    @Synchronized
    fun maskFor(frame: Int, ops: List<MaskOp>): ByteArray {
        val cached = cacheMask
        val reusable = cached != null && cacheFrame == frame && cacheOps.size <= ops.size &&
            cacheOps.indices.all { cacheOps[it] === ops[it] }
        val mask: ByteArray
        val from: Int
        if (reusable) {
            if (cacheOps.size == ops.size) return cached!!
            mask = cached!!.copyOf()
            from = cacheOps.size
        } else {
            mask = ByteArray(workWidth * workHeight) { -1 }
            from = 0
        }
        applyOps(frame, mask, ops, from)
        cacheFrame = frame
        cacheOps = ops.toList()
        cacheMask = mask
        return mask
    }

    private fun freshMask(frame: Int, ops: List<MaskOp>): ByteArray {
        val mask = ByteArray(workWidth * workHeight) { -1 }
        applyOps(frame, mask, ops, 0)
        return mask
    }

    private fun applyOps(frame: Int, mask: ByteArray, ops: List<MaskOp>, from: Int) {
        val w = workWidth
        val h = workHeight
        var pixels: IntArray? = null
        var strokeBitmap: Bitmap? = null
        var strokeBytes: ByteArray? = null
        for (k in from until ops.size) {
            when (val op = ops[k]) {
                MaskOp.Auto -> autoMasks[frame]?.let { auto ->
                    for (i in mask.indices) {
                        val a = auto[i].toInt() and 0xFF
                        if (a < (mask[i].toInt() and 0xFF)) mask[i] = a.toByte()
                    }
                }
                is MaskOp.Brush -> {
                    val bmp = strokeBitmap ?: Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { strokeBitmap = it }
                    val bytes = strokeBytes ?: ByteArray(bmp.rowBytes * h).also { strokeBytes = it }
                    applyBrush(op, bmp, bytes, mask, w, h)
                }
                is MaskOp.Wand -> {
                    val px = pixels ?: IntArray(w * h).also {
                        working[frame].getPixels(it, 0, w, 0, 0, w, h)
                        pixels = it
                    }
                    applyWand(op, px, mask, w, h)
                }
            }
        }
        strokeBitmap?.recycle()
    }

    private fun applyBrush(op: MaskOp.Brush, bmp: Bitmap, bytes: ByteArray, mask: ByteArray, w: Int, h: Int) {
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)
        val radiusPx = max(0.5f, op.radius * max(w, h))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val pts = op.points
        if (pts.size < 2) return
        if (pts.size == 2) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(pts[0] * w, pts[1] * h, radiusPx, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radiusPx * 2f
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val path = Path()
            path.moveTo(pts[0] * w, pts[1] * h)
            var i = 2
            while (i + 1 < pts.size) {
                path.lineTo(pts[i] * w, pts[i + 1] * h)
                i += 2
            }
            canvas.drawPath(path, paint)
        }
        bmp.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        val rowBytes = bmp.rowBytes
        for (y in 0 until h) {
            val src = y * rowBytes
            val dst = y * w
            for (x in 0 until w) {
                val s = bytes[src + x].toInt() and 0xFF
                if (s == 0) continue
                val m = mask[dst + x].toInt() and 0xFF
                mask[dst + x] = (if (op.erase) m * (255 - s) / 255 else max(m, s)).toByte()
            }
        }
    }

    private fun applyWand(op: MaskOp.Wand, px: IntArray, mask: ByteArray, w: Int, h: Int) {
        val sx = (op.x * w).toInt().coerceIn(0, w - 1)
        val sy = (op.y * h).toInt().coerceIn(0, h - 1)
        // The color is always picked from the first frame so all GIF frames remove the same background.
        val seed = working[0].getPixel(sx, sy)
        val tol = op.tolerance
        if (!op.contiguous) {
            for (i in px.indices) if (similar(px[i], seed, tol)) mask[i] = 0
            return
        }
        val start = sy * w + sx
        if (!similar(px[start], seed, tol)) return
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        while (head < tail) {
            val p = queue[head++]
            mask[p] = 0
            val x = p % w
            val y = p / w
            if (x > 0) { val q = p - 1; if (!visited[q] && similar(px[q], seed, tol)) { visited[q] = true; queue[tail++] = q } }
            if (x < w - 1) { val q = p + 1; if (!visited[q] && similar(px[q], seed, tol)) { visited[q] = true; queue[tail++] = q } }
            if (y > 0) { val q = p - w; if (!visited[q] && similar(px[q], seed, tol)) { visited[q] = true; queue[tail++] = q } }
            if (y < h - 1) { val q = p + w; if (!visited[q] && similar(px[q], seed, tol)) { visited[q] = true; queue[tail++] = q } }
        }
    }

    private fun similar(c: Int, seed: Int, tol: Int): Boolean {
        val a1 = c ushr 24
        val a2 = seed ushr 24
        if (a1 < 24 && a2 < 24) return true
        if (a1 < 24 || a2 < 24) return false
        val dr = abs(((c shr 16) and 0xFF) - ((seed shr 16) and 0xFF))
        val dg = abs(((c shr 8) and 0xFF) - ((seed shr 8) and 0xFF))
        val db = abs((c and 0xFF) - (seed and 0xFF))
        return max(dr, max(dg, db)) <= tol
    }

    // --------------------------------------------------------------- previews

    /** Working-size image of [frame] with the mask applied (transparent where erased). */
    fun renderPreview(frame: Int, ops: List<MaskOp>): Bitmap {
        val mask = maskFor(frame, ops)
        val out = Bitmap.createBitmap(workWidth, workHeight, Bitmap.Config.ARGB_8888)
        out.setPixels(composite(frame, mask), 0, workWidth, 0, 0, workWidth, workHeight)
        return out
    }

    private fun composite(frame: Int, mask: ByteArray): IntArray {
        val w = workWidth
        val h = workHeight
        val px = IntArray(w * h)
        working[frame].getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val m = mask[i].toInt() and 0xFF
            if (m == 255) continue
            val a = (px[i] ushr 24) * m / 255
            px[i] = (a shl 24) or (px[i] and 0x00FFFFFF)
        }
        return px
    }

    // ------------------------------------------------------------ final output

    /**
     * Final 512x512 frames. With [FinishOptions.autoFit] the visible part (union over all frames) is scaled up to
     * fill the sticker, leaving a small margin and room for the outline.
     */
    fun renderFinalFrames(
        ops: List<MaskOp>,
        options: FinishOptions,
        frames: IntRange = 0 until frameCount,
        onProgress: (Float) -> Unit = {},
    ): List<Bitmap> {
        val w = workWidth
        val h = workHeight
        // Pass 1: masks (1 byte/pixel) and the visible area. Pass 2 composites one frame at a time,
        // so long GIFs don't need every full-color frame in memory at once.
        val masks = ArrayList<ByteArray>()
        var bounds: Rect? = null
        var anyVisible = false
        for ((n, f) in frames.withIndex()) {
            val mask = freshMask(f, ops)
            masks += mask
            val frameBounds = visibleBounds(composite(f, mask), w, h)
            if (frameBounds != null) {
                anyVisible = true
                if (bounds == null) bounds = Rect(frameBounds) else bounds.union(frameBounds)
            }
            onProgress(0.5f * (n + 1) / frames.count())
        }
        if (!anyVisible) throw NothingToSaveException()
        if (!options.autoFit || bounds == null) bounds = Rect(0, 0, w, h)

        val size = WhatsAppLimits.STICKER_SIZE
        val pad = if (options.outline) options.outlineWidth + 8 else 8
        val box = (size - 2 * pad).toFloat()
        val scale = box / max(bounds.width(), bounds.height())
        val dw = bounds.width() * scale
        val dh = bounds.height() * scale
        val dst = RectF((size - dw) / 2f, (size - dh) / 2f, (size + dw) / 2f, (size + dh) / 2f)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val result = ArrayList<Bitmap>(masks.size)
        for ((n, f) in frames.withIndex()) {
            val content = Bitmap.createBitmap(composite(f, masks[n]), w, h, Bitmap.Config.ARGB_8888)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(content, bounds, dst, paint)
            content.recycle()
            if (options.outline) Outline.apply(out, options.outlineWidth.toFloat(), options.outlineColor)
            result += out
            onProgress(0.5f + 0.5f * (n + 1) / masks.size)
        }
        return result
    }

    private fun visibleBounds(px: IntArray, w: Int, h: Int): Rect? {
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 10) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        return Rect(minX, minY, maxX + 1, maxY + 1)
    }
}
