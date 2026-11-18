package com.localstickers.app.media

import java.io.ByteArrayOutputStream

/**
 * Builds an animated WebP file out of single-frame WebP files (the output of Android's Bitmap.compress).
 *
 * Android can only *encode* still WebP images, so each frame is encoded on its own and its image chunks
 * (optional "ALPH" + "VP8 "/"VP8L") are wrapped in "ANMF" frame chunks behind a "VP8X" + "ANIM" header,
 * following the WebP container specification (developers.google.com/speed/webp/docs/riff_container).
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
object AnimatedWebpMuxer {

    class Frame(
        /** "ALPH" (optional) and "VP8 "/"VP8L" chunks, each with header and even padding. */
        val imageChunks: ByteArray,
        val durationMs: Int,
        val hasAlpha: Boolean,
    )

    /** Extracts the image chunks of a still WebP file so it can become one animation frame. */
    fun frameFromStillWebp(webp: ByteArray, durationMs: Int): Frame {
        require(webp.size >= 20 && WebpInfo.fourCC(webp, 0) == "RIFF" && WebpInfo.fourCC(webp, 8) == "WEBP") {
            "Not a WebP file"
        }
        val out = ByteArrayOutputStream(webp.size)
        var pos = 12
        var alpha = false
        var foundBitstream = false
        while (pos + 8 <= webp.size) {
            val id = WebpInfo.fourCC(webp, pos)
            val size = WebpInfo.le32(webp, pos + 4)
            require(size >= 0 && pos + 8 + size <= webp.size) { "Corrupt WebP chunk $id" }
            when (id) {
                "ALPH" -> {
                    writeChunk(out, webp, pos, size)
                    alpha = true
                }
                "VP8 " -> {
                    writeChunk(out, webp, pos, size)
                    foundBitstream = true
                }
                "VP8L" -> {
                    writeChunk(out, webp, pos, size)
                    foundBitstream = true
                    if (size >= 5) {
                        val bits = WebpInfo.le32(webp, pos + 9)
                        if ((bits ushr 28) and 1 == 1) alpha = true
                    }
                }
                "ANIM", "ANMF" -> throw IllegalArgumentException("Frame is already animated")
                // VP8X, ICCP, EXIF, XMP: not needed inside a frame.
            }
            pos += 8 + size + (size and 1)
        }
        require(foundBitstream) { "WebP has no image data" }
        return Frame(out.toByteArray(), durationMs, alpha)
    }

    /**
     * @param loopCount 0 = loop forever.
     * Every frame covers the whole canvas and is drawn without blending, so transparent pixels of a frame
     * stay transparent instead of showing the previous frame through.
     */
    fun mux(width: Int, height: Int, frames: List<Frame>, loopCount: Int = 0): ByteArray {
        require(frames.isNotEmpty()) { "No frames" }
        val body = ByteArrayOutputStream()

        // VP8X: flags + canvas size.
        var flags = 0x02 // animation
        if (frames.any { it.hasAlpha }) flags = flags or 0x10 // alpha
        writeFourCC(body, "VP8X")
        writeLE32(body, 10)
        body.write(flags)
        body.write(0); body.write(0); body.write(0)
        writeLE24(body, width - 1)
        writeLE24(body, height - 1)

        // ANIM: background color (BGRA, transparent) + loop count.
        writeFourCC(body, "ANIM")
        writeLE32(body, 6)
        writeLE32(body, 0)
        writeLE16(body, loopCount)

        for (frame in frames) {
            val payload = 16 + frame.imageChunks.size
            writeFourCC(body, "ANMF")
            writeLE32(body, payload)
            writeLE24(body, 0) // X offset / 2
            writeLE24(body, 0) // Y offset / 2
            writeLE24(body, width - 1)
            writeLE24(body, height - 1)
            writeLE24(body, frame.durationMs.coerceIn(1, 0xFFFFFF))
            body.write(0x02) // blending: do not blend; disposal: none
            body.write(frame.imageChunks)
            if (payload and 1 == 1) body.write(0)
        }

        val out = ByteArrayOutputStream(body.size() + 12)
        writeFourCC(out, "RIFF")
        writeLE32(out, 4 + body.size())
        writeFourCC(out, "WEBP")
        body.writeTo(out)
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, src: ByteArray, pos: Int, size: Int) {
        out.write(src, pos, 8 + size)
        if (size and 1 == 1) out.write(0)
    }

    private fun writeFourCC(out: ByteArrayOutputStream, s: String) {
        for (c in s) out.write(c.code)
    }

    private fun writeLE16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun writeLE24(out: ByteArrayOutputStream, v: Int) {
        writeLE16(out, v)
        out.write((v ushr 16) and 0xFF)
    }

    private fun writeLE32(out: ByteArrayOutputStream, v: Int) {
        writeLE24(out, v)
        out.write((v ushr 24) and 0xFF)
    }
}
