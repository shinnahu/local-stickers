package com.localstickers.app.media

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws a solid outline (WhatsApp recommends an 8px white stroke) around the visible part of a sticker,
 * using an exact Euclidean distance transform so the stroke has round, anti-aliased edges.
 */
object Outline {
    private const val INF = 1e20f

    fun apply(bitmap: Bitmap, width: Float, color: Int) {
        if (width <= 0f) return
        val w = bitmap.width
        val h = bitmap.height
        val n = w * h
        val px = IntArray(n)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val dist = FloatArray(n) { if ((px[it] ushr 24) >= 128) 0f else INF }
        distanceTransform(dist, w, h)

        val orR = Color.red(color)
        val orG = Color.green(color)
        val orB = Color.blue(color)
        for (i in 0 until n) {
            val d = sqrt(dist[i])
            val ring = (width + 0.5f - d).coerceIn(0f, 1f)
            if (ring <= 0f) continue
            val c = px[i]
            val a = c ushr 24
            if (a == 255) continue
            val oa = (ring * 255f).roundToInt() * (255 - a) / 255
            val outA = a + oa
            if (outA == 0) continue
            val r = (((c shr 16) and 0xFF) * a + orR * oa) / outA
            val g = (((c shr 8) and 0xFF) * a + orG * oa) / outA
            val b = ((c and 0xFF) * a + orB * oa) / outA
            px[i] = (outA shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** Squared distance to the nearest 0 cell (Felzenszwalb & Huttenlocher), in place. */
    private fun distanceTransform(f: FloatArray, w: Int, h: Int) {
        val size = max(w, h)
        val line = FloatArray(size)
        val out = FloatArray(size)
        val v = IntArray(size)
        val z = FloatArray(size + 1)
        for (x in 0 until w) {
            for (y in 0 until h) line[y] = f[y * w + x]
            transform1d(line, h, out, v, z)
            for (y in 0 until h) f[y * w + x] = out[y]
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) line[x] = f[row + x]
            transform1d(line, w, out, v, z)
            for (x in 0 until w) f[row + x] = out[x]
        }
    }

    private fun transform1d(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: FloatArray) {
        var k = 0
        v[0] = 0
        z[0] = -INF
        z[1] = INF
        for (q in 1 until n) {
            val fq = f[q] + q.toFloat() * q
            var s = (fq - (f[v[k]] + v[k].toFloat() * v[k])) / (2f * q - 2f * v[k])
            while (s <= z[k] && k > 0) {
                k--
                s = (fq - (f[v[k]] + v[k].toFloat() * v[k])) / (2f * q - 2f * v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = INF
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = (q - v[k]).toFloat()
            d[q] = dq * dq + f[v[k]]
        }
    }
}
