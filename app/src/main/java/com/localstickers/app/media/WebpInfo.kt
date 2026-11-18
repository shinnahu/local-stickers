package com.localstickers.app.media

/**
 * Minimal WebP container reader (pure Kotlin, no Android dependencies) used to validate sticker files:
 * canvas size, animation flag and frame count.
 */
data class WebpInfo(
    val width: Int,
    val height: Int,
    val animated: Boolean,
    val frameCount: Int,
    val frameDurationsMs: List<Int>,
) {
    val totalDurationMs: Int get() = frameDurationsMs.sum()

    companion object {
        fun parse(bytes: ByteArray): WebpInfo? {
            if (bytes.size < 30) return null
            if (fourCC(bytes, 0) != "RIFF" || fourCC(bytes, 8) != "WEBP") return null
            var pos = 12
            var width = -1
            var height = -1
            var animated = false
            var frames = 0
            val durations = ArrayList<Int>()
            while (pos + 8 <= bytes.size) {
                val id = fourCC(bytes, pos)
                val size = le32(bytes, pos + 4)
                if (size < 0) return null
                val data = pos + 8
                if (data + size > bytes.size) return null
                when (id) {
                    "VP8X" -> {
                        if (size < 10) return null
                        animated = (bytes[data].toInt() and 0x02) != 0
                        width = le24(bytes, data + 4) + 1
                        height = le24(bytes, data + 7) + 1
                    }
                    "VP8 " -> {
                        if (width < 0 && size >= 10) {
                            // Frame tag (3 bytes) + start code (3 bytes) + 14-bit width + 14-bit height.
                            width = le16(bytes, data + 6) and 0x3FFF
                            height = le16(bytes, data + 8) and 0x3FFF
                        }
                        if (!animated) frames = 1
                    }
                    "VP8L" -> {
                        if (width < 0 && size >= 5 && (bytes[data].toInt() and 0xFF) == 0x2F) {
                            val bits = le32(bytes, data + 1)
                            width = (bits and 0x3FFF) + 1
                            height = ((bits ushr 14) and 0x3FFF) + 1
                        }
                        if (!animated) frames = 1
                    }
                    "ANMF" -> {
                        if (size >= 16) {
                            frames++
                            durations += le24(bytes, data + 12)
                        }
                    }
                }
                pos = data + size + (size and 1)
            }
            if (width <= 0 || height <= 0) return null
            if (!animated) {
                return WebpInfo(width, height, false, 1, emptyList())
            }
            return WebpInfo(width, height, true, frames, durations)
        }

        fun fourCC(b: ByteArray, off: Int): String =
            String(charArrayOf(b[off].toInt().toChar(), b[off + 1].toInt().toChar(), b[off + 2].toInt().toChar(), b[off + 3].toInt().toChar()))

        fun le16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        fun le24(b: ByteArray, off: Int): Int = le16(b, off) or ((b[off + 2].toInt() and 0xFF) shl 16)

        fun le32(b: ByteArray, off: Int): Int = le24(b, off) or ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}
