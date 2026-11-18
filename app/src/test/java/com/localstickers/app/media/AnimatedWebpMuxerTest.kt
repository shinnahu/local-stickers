package com.localstickers.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Muxes still WebP frames (same chunk layouts Android's encoder produces: VP8X+ALPH+VP8, VP8L, VP8) into an
 * animated WebP and checks the container. The written files are also decoded by an external decoder (Pillow)
 * outside this test.
 */
class AnimatedWebpMuxerTest {

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResource("webp/$name")) { "missing fixture $name" }.readBytes()

    private val outDir = File("build/test-output").apply { mkdirs() }

    private fun muxSet(prefix: String, durations: List<Int>): ByteArray {
        val frames = durations.mapIndexed { i, d -> AnimatedWebpMuxer.frameFromStillWebp(fixture("${prefix}_$i.webp"), d) }
        val bytes = AnimatedWebpMuxer.mux(512, 512, frames)
        File(outDir, "$prefix.webp").writeBytes(bytes)
        return bytes
    }

    @Test
    fun stillFramesAreRecognised() {
        for (prefix in listOf("alpha_lossy", "alpha_lossless", "opaque_lossy")) {
            val info = WebpInfo.parse(fixture("${prefix}_0.webp"))
            assertNotNull(prefix, info)
            assertEquals(512, info!!.width)
            assertEquals(512, info.height)
            assertFalse(info.animated)
            assertEquals(1, info.frameCount)
        }
    }

    @Test
    fun alphaFlagFollowsFrames() {
        assertTrue(AnimatedWebpMuxer.frameFromStillWebp(fixture("alpha_lossy_0.webp"), 100).hasAlpha)
        assertTrue(AnimatedWebpMuxer.frameFromStillWebp(fixture("alpha_lossless_0.webp"), 100).hasAlpha)
        assertFalse(AnimatedWebpMuxer.frameFromStillWebp(fixture("opaque_lossy_0.webp"), 100).hasAlpha)
    }

    @Test
    fun muxedFilesAreValidAnimations() {
        val durations = listOf(100, 80, 120, 60)
        for (prefix in listOf("alpha_lossy", "alpha_lossless", "opaque_lossy")) {
            val bytes = muxSet(prefix, durations)
            val info = WebpInfo.parse(bytes)
            assertNotNull(prefix, info)
            assertEquals(512, info!!.width)
            assertEquals(512, info.height)
            assertTrue(info.animated)
            assertEquals(4, info.frameCount)
            assertEquals(durations, info.frameDurationsMs)
            // RIFF size field must equal file size - 8.
            assertEquals(bytes.size - 8, WebpInfo.le32(bytes, 4))
            // Animated stickers must stay under WhatsApp's 500 KB.
            assertTrue(bytes.size < 500 * 1024)
        }
    }

    @Test
    fun twoFrameStillIsAnimated() {
        val frame = AnimatedWebpMuxer.frameFromStillWebp(fixture("alpha_lossy_0.webp"), 1000)
        val bytes = AnimatedWebpMuxer.mux(512, 512, listOf(frame, frame))
        File(outDir, "still_as_animation.webp").writeBytes(bytes)
        val info = WebpInfo.parse(bytes)!!
        assertTrue(info.animated)
        assertEquals(2, info.frameCount)
        assertEquals(2000, info.totalDurationMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonWebp() {
        AnimatedWebpMuxer.frameFromStillWebp(ByteArray(64), 100)
    }
}
