package com.localstickers.app.media

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlin.math.floor
import kotlin.math.min

class BackgroundRemovalUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Automatic background removal with ML Kit Subject Segmentation. It runs on the device; the model itself is
 * provided by Google Play services (downloaded once, the first time it is needed).
 */
class BackgroundRemover(context: Context) {
    private val appContext = context.applicationContext
    private val segmenter: SubjectSegmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
        )
    }

    /** Makes sure the model is on the device. [onProgress] gets 0..1 while downloading. */
    suspend fun ensureModel(onProgress: (Float?) -> Unit) {
        val client = ModuleInstall.getClient(appContext)
        val available = try {
            client.areModulesAvailable(segmenter).await().areModulesAvailable()
        } catch (e: Exception) {
            throw BackgroundRemovalUnavailable("Automatic removal needs Google Play services, which isn't available on this device.", e)
        }
        if (available) return

        val done = CompletableDeferred<Boolean>()
        val listener = object : InstallStatusListener {
            override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                update.progressInfo?.let { info ->
                    if (info.totalBytesToDownload > 0) onProgress(info.bytesDownloaded.toFloat() / info.totalBytesToDownload)
                }
                when (update.installState) {
                    InstallState.STATE_COMPLETED -> done.complete(true)
                    InstallState.STATE_FAILED, InstallState.STATE_CANCELED -> done.complete(false)
                }
            }
        }
        try {
            onProgress(null)
            val request = ModuleInstallRequest.newBuilder().addApi(segmenter).setListener(listener).build()
            val response = client.installModules(request).await()
            if (response.areModulesAlreadyInstalled()) return
            val ok = withTimeout(5 * 60_000L) { done.await() }
            if (!ok) throw BackgroundRemovalUnavailable("The background-removal model couldn't be downloaded. Check your connection and try again.")
        } catch (e: BackgroundRemovalUnavailable) {
            throw e
        } catch (e: Exception) {
            throw BackgroundRemovalUnavailable("The background-removal model couldn't be downloaded. Check your connection and try again.", e)
        } finally {
            runCatching { client.unregisterListener(listener) }
        }
    }

    /**
     * Returns a foreground mask (0..255 per pixel, row-major) with the size [outW] x [outH].
     * [input] should be an ARGB_8888 image showing the same area at any resolution.
     */
    suspend fun foregroundMask(input: Bitmap, outW: Int, outH: Int): ByteArray {
        val result = try {
            segmenter.process(InputImage.fromBitmap(input, 0)).await()
        } catch (e: Exception) {
            throw BackgroundRemovalUnavailable("Automatic removal failed: ${e.message ?: "unknown error"}", e)
        }
        val buffer = result.foregroundConfidenceMask
            ?: throw BackgroundRemovalUnavailable("Automatic removal didn't return a mask")
        val iw = input.width
        val ih = input.height
        buffer.rewind()
        val confidence = FloatArray(iw * ih)
        buffer.get(confidence, 0, min(confidence.size, buffer.remaining()))
        return resample(confidence, iw, ih, outW, outH)
    }

    fun close() {
        runCatching { segmenter.close() }
    }

    /** Bilinear resize of the confidence map plus a contrast curve that keeps edges soft but removes haze. */
    private fun resample(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        val out = ByteArray(dw * dh)
        val sx = sw.toFloat() / dw
        val sy = sh.toFloat() / dh
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = floor(fy).toInt()
            val y1 = min(y0 + 1, sh - 1)
            val ty = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = floor(fx).toInt()
                val x1 = min(x0 + 1, sw - 1)
                val tx = fx - x0
                val top = src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx
                val bottom = src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx
                val c = top * (1 - ty) + bottom * ty
                val a = ((c - 0.3f) / 0.4f).coerceIn(0f, 1f)
                out[y * dw + x] = (a * 255f + 0.5f).toInt().toByte()
            }
        }
        return out
    }
}
