package com.localstickers.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Handle { NONE, MOVE, TL, TR, BL, BR, L, T, R, B }

/** Where an image of [w]x[h] is drawn when fitted (centered) inside [box]. */
fun fitRect(w: Int, h: Int, box: IntSize, padding: Float = 0f): Rect {
    if (box.width <= 0 || box.height <= 0 || w <= 0 || h <= 0) return Rect.Zero
    val bw = box.width - 2 * padding
    val bh = box.height - 2 * padding
    val scale = min(bw / w, bh / h)
    val dw = w * scale
    val dh = h * scale
    val left = (box.width - dw) / 2f
    val top = (box.height - dh) / 2f
    return Rect(left, top, left + dw, top + dh)
}

private fun Rect.normToScreen(img: Rect) = Rect(
    img.left + left * img.width,
    img.top + top * img.height,
    img.left + right * img.width,
    img.top + bottom * img.height,
)

/**
 * Crop rectangle editor. [crop] is normalized (0..1) relative to [image]. In [square] mode the rectangle stays
 * square in real pixels.
 */
@Composable
fun CropView(
    image: ImageBitmap,
    crop: Rect,
    square: Boolean,
    onCropChange: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val touchRadius = with(density) { 32.dp.toPx() }
    val minSizePx = with(density) { 48.dp.toPx() }
    val padding = with(density) { 20.dp.toPx() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val imgRect = remember(box, image) { fitRect(image.width, image.height, box, padding) }
    val latestCrop by rememberUpdatedState(crop)
    val latestSquare by rememberUpdatedState(square)
    val latestImg by rememberUpdatedState(imgRect)
    val latestOnChange by rememberUpdatedState(onCropChange)
    val accent = MaterialTheme.colorScheme.primary

    Canvas(
        modifier
            .onSizeChanged { box = it }
            .pointerInput(image) {
                var handle = Handle.NONE
                var start = Rect.Zero
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = { pos ->
                        handle = hitTest(pos, latestCrop.normToScreen(latestImg), touchRadius, latestSquare)
                        start = latestCrop
                        total = Offset.Zero
                    },
                    onDragEnd = { handle = Handle.NONE },
                    onDragCancel = { handle = Handle.NONE },
                    onDrag = { change, delta ->
                        if (handle != Handle.NONE && latestImg.width > 0f) {
                            change.consume()
                            total += delta
                            latestOnChange(
                                dragCrop(handle, start, total, latestImg, image.width, image.height, latestSquare, minSizePx),
                            )
                        }
                    },
                )
            },
    ) {
        val r = imgRect
        if (r.width <= 0f) return@Canvas
        drawImage(
            image,
            dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
            dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
        )
        val c = crop.normToScreen(r)
        val dim = Color.Black.copy(alpha = 0.6f)
        drawRect(dim, Offset(r.left, r.top), Size(r.width, c.top - r.top))
        drawRect(dim, Offset(r.left, c.bottom), Size(r.width, r.bottom - c.bottom))
        drawRect(dim, Offset(r.left, c.top), Size(c.left - r.left, c.height))
        drawRect(dim, Offset(c.right, c.top), Size(r.right - c.right, c.height))

        val thin = 1.dp.toPx()
        for (i in 1..2) {
            val x = c.left + c.width * i / 3f
            val y = c.top + c.height * i / 3f
            drawLine(Color.White.copy(alpha = 0.5f), Offset(x, c.top), Offset(x, c.bottom), thin)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(c.left, y), Offset(c.right, y), thin)
        }
        drawRect(Color.White, c.topLeft, c.size, style = Stroke(2.dp.toPx()))

        val len = 22.dp.toPx()
        val w = 4.dp.toPx()
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(accent, Offset(x, y), Offset(x + dx * len, y), w, StrokeCap.Round)
            drawLine(accent, Offset(x, y), Offset(x, y + dy * len), w, StrokeCap.Round)
        }
        corner(c.left, c.top, 1f, 1f)
        corner(c.right, c.top, -1f, 1f)
        corner(c.left, c.bottom, 1f, -1f)
        corner(c.right, c.bottom, -1f, -1f)
        if (!square) {
            val half = 10.dp.toPx()
            drawLine(accent, Offset(c.center.x - half, c.top), Offset(c.center.x + half, c.top), w, StrokeCap.Round)
            drawLine(accent, Offset(c.center.x - half, c.bottom), Offset(c.center.x + half, c.bottom), w, StrokeCap.Round)
            drawLine(accent, Offset(c.left, c.center.y - half), Offset(c.left, c.center.y + half), w, StrokeCap.Round)
            drawLine(accent, Offset(c.right, c.center.y - half), Offset(c.right, c.center.y + half), w, StrokeCap.Round)
        }
    }
}

private fun hitTest(p: Offset, c: Rect, touch: Float, square: Boolean): Handle {
    fun near(x: Float, y: Float) = (p - Offset(x, y)).getDistance() <= touch
    when {
        near(c.left, c.top) -> return Handle.TL
        near(c.right, c.top) -> return Handle.TR
        near(c.left, c.bottom) -> return Handle.BL
        near(c.right, c.bottom) -> return Handle.BR
    }
    if (!square) {
        val insideY = p.y in c.top..c.bottom
        val insideX = p.x in c.left..c.right
        when {
            abs(p.x - c.left) <= touch && insideY -> return Handle.L
            abs(p.x - c.right) <= touch && insideY -> return Handle.R
            abs(p.y - c.top) <= touch && insideX -> return Handle.T
            abs(p.y - c.bottom) <= touch && insideX -> return Handle.B
        }
    }
    return if (c.contains(p)) Handle.MOVE else Handle.NONE
}

private fun dragCrop(
    handle: Handle,
    start: Rect,
    totalPx: Offset,
    img: Rect,
    imageW: Int,
    imageH: Int,
    square: Boolean,
    minSizePx: Float,
): Rect {
    val dx = totalPx.x / img.width
    val dy = totalPx.y / img.height
    val minW = min(1f, minSizePx / img.width)
    val minH = min(1f, minSizePx / img.height)
    var l = start.left
    var t = start.top
    var r = start.right
    var b = start.bottom
    when (handle) {
        Handle.MOVE -> {
            val w = r - l
            val h = b - t
            l = (l + dx).coerceIn(0f, 1f - w)
            t = (t + dy).coerceIn(0f, 1f - h)
            return Rect(l, t, l + w, t + h)
        }
        Handle.L -> l = (l + dx).coerceIn(0f, r - minW)
        Handle.R -> r = (r + dx).coerceIn(l + minW, 1f)
        Handle.T -> t = (t + dy).coerceIn(0f, b - minH)
        Handle.B -> b = (b + dy).coerceIn(t + minH, 1f)
        Handle.TL, Handle.TR, Handle.BL, Handle.BR -> {
            val leftSide = handle == Handle.TL || handle == Handle.BL
            val topSide = handle == Handle.TL || handle == Handle.TR
            if (!square) {
                if (leftSide) l = (l + dx).coerceIn(0f, r - minW) else r = (r + dx).coerceIn(l + minW, 1f)
                if (topSide) t = (t + dy).coerceIn(0f, b - minH) else b = (b + dy).coerceIn(t + minH, 1f)
            } else {
                // Work in image pixels so the result is a real square.
                val iw = imageW.toFloat()
                val ih = imageH.toFloat()
                val ax = (if (leftSide) r else l) * iw
                val ay = (if (topSide) b else t) * ih
                val cx = ((if (leftSide) l else r) + dx) * iw
                val cy = ((if (topSide) t else b) + dy) * ih
                val dirX = if (leftSide) -1f else 1f
                val dirY = if (topSide) -1f else 1f
                val maxX = if (leftSide) ax else iw - ax
                val maxY = if (topSide) ay else ih - ay
                val upper = min(maxX, maxY)
                val lower = min(max(minW * iw, minH * ih), upper)
                val side = max(abs(cx - ax), abs(cy - ay)).coerceIn(lower, upper)
                val nx = ax + dirX * side
                val ny = ay + dirY * side
                l = min(ax, nx) / iw
                r = max(ax, nx) / iw
                t = min(ay, ny) / ih
                b = max(ay, ny) / ih
            }
        }
        Handle.NONE -> Unit
    }
    return Rect(l, t, r, b)
}
