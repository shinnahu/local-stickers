package com.localstickers.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.localstickers.app.ui.common.checkerboard
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shows the sticker being edited over a checkerboard.
 * One finger: paint (erase / restore) or tap (magic wand). Two fingers: zoom and move.
 */
@Composable
fun MaskCanvas(
    image: ImageBitmap,
    tool: Tool,
    brushRadius: Float,
    onStroke: (FloatArray, Boolean) -> Unit,
    onTap: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val padding = with(density) { 16.dp.toPx() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val stroke = remember { mutableStateListOf<Offset>() }
    val fit = remember(box, image.width, image.height) { fitRect(image.width, image.height, box, padding) }

    val latestFit by rememberUpdatedState(fit)
    val latestOnStroke by rememberUpdatedState(onStroke)
    val latestOnTap by rememberUpdatedState(onTap)

    LaunchedEffect(image.width, image.height) {
        zoom = 1f
        pan = Offset.Zero
    }

    fun center() = Offset(box.width / 2f, box.height / 2f)

    fun toImage(p: Offset): Offset {
        val f = latestFit
        if (f.width <= 0f) return Offset(-1f, -1f)
        val c = center()
        val base = c + (p - c - pan) / zoom
        return Offset((base.x - f.left) / f.width, (base.y - f.top) / f.height)
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(tool) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val painting = tool == Tool.ERASE || tool == Tool.RESTORE
                    val tapping = tool == Tool.WAND
                    var transforming = false
                    var moved = false
                    stroke.clear()
                    if (painting) stroke.add(toImage(down.position))
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            if (!transforming) {
                                transforming = true
                                stroke.clear()
                            }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newZoom = (zoom * zoomChange).coerceIn(1f, 8f)
                            val factor = newZoom / zoom
                            val c = center()
                            pan = (centroid - c) - (centroid - c - pan) * factor + panChange
                            zoom = newZoom
                            event.changes.forEach { it.consume() }
                        } else if (!transforming) {
                            val change = event.changes.firstOrNull { it.pressed }
                            if (change != null) {
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                if (painting) {
                                    stroke.add(toImage(change.position))
                                } else if (!tapping && moved && zoom > 1f) {
                                    pan += change.position - change.previousPosition
                                }
                                change.consume()
                            }
                        }
                        // Keep at least part of the image on screen.
                        val limitX = box.width * zoom / 2f
                        val limitY = box.height * zoom / 2f
                        pan = Offset(pan.x.coerceIn(-limitX, limitX), pan.y.coerceIn(-limitY, limitY))
                    } while (event.changes.any { it.pressed })

                    if (!transforming) {
                        if (painting && stroke.isNotEmpty()) {
                            val pts = FloatArray(stroke.size * 2)
                            stroke.forEachIndexed { i, o ->
                                pts[i * 2] = o.x
                                pts[i * 2 + 1] = o.y
                            }
                            latestOnStroke(pts, tool == Tool.ERASE)
                        } else if (tapping && !moved) {
                            val p = toImage(down.position)
                            if (p.x in 0f..1f && p.y in 0f..1f) latestOnTap(p.x, p.y)
                        }
                    }
                    stroke.clear()
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                },
        ) {
            if (fit.width > 0f) {
                with(density) {
                    Spacer(
                        Modifier
                            .offset { IntOffset(fit.left.roundToInt(), fit.top.roundToInt()) }
                            .size(fit.width.toDp(), fit.height.toDp())
                            .checkerboard(),
                    )
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val r = fit
                if (r.width <= 0f) return@Canvas
                drawImage(
                    image,
                    dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                    dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                    filterQuality = if (zoom > 2.5f) FilterQuality.None else FilterQuality.Low,
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                val r = fit
                if (stroke.isEmpty() || r.width <= 0f) return@Canvas
                val color = if (tool == Tool.ERASE) Color(0x99FF3B30) else Color(0x9934C759)
                val width = brushRadius * 2f * max(r.width, r.height)
                fun at(o: Offset) = Offset(r.left + o.x * r.width, r.top + o.y * r.height)
                if (stroke.size == 1) {
                    drawCircle(color, radius = width / 2f, center = at(stroke[0]))
                } else {
                    val path = Path()
                    val first = at(stroke[0])
                    path.moveTo(first.x, first.y)
                    for (i in 1 until stroke.size) {
                        val p = at(stroke[i])
                        path.lineTo(p.x, p.y)
                    }
                    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}
