package com.localstickers.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.localstickers.app.data.Backup
import com.localstickers.app.data.PackRepository
import com.localstickers.app.media.EditSession
import com.localstickers.app.media.FinishOptions
import com.localstickers.app.media.MaskOp
import com.localstickers.app.media.MediaLoader
import com.localstickers.app.media.WebpEncoder
import com.localstickers.app.media.WebpInfo
import com.localstickers.app.provider.StickerContentProvider
import com.localstickers.app.whatsapp.WhatsApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.max

/**
 * End-to-end checks of the sticker pipeline with real Android graphics (Robolectric native graphics):
 * GIF/PNG decoding -> crop/rotate -> wand/brush masks -> outline -> WebP encoding -> pack storage ->
 * the ContentProvider WhatsApp reads -> backup export/import.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StickerPipelineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val out = File("build/test-output").apply { mkdirs() }

    private fun media(name: String): Uri {
        val url = requireNotNull(javaClass.classLoader?.getResource("media/$name")) { "missing $name" }
        return Uri.fromFile(File(url.toURI()))
    }

    @Before
    fun resetRepository() {
        // The repository is a process-wide singleton; give every test a fresh one.
        PackRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @Test
    fun gifBecomesAnimatedSticker() = runBlocking {
        val media = MediaLoader.load(context, media("ball.gif"))
        assertTrue(media.animated)
        assertEquals(12, media.frames.size)
        assertEquals(List(12) { 80 }, media.durationsMs)

        val session = EditSession(media)
        session.ensureWorking()
        assertEquals(512, max(session.workWidth, session.workHeight))

        // Magic wand on the blue background, applied to every frame.
        val ops = listOf<MaskOp>(MaskOp.Wand(0.02f, 0.02f, 40, contiguous = false))
        val frames = session.renderFinalFrames(ops, FinishOptions(outline = true, outlineWidth = 8, outlineColor = Color.WHITE, autoFit = true))
        assertEquals(12, frames.size)
        for (f in frames) {
            assertEquals(512, f.width)
            assertEquals(512, f.height)
            assertEquals("corner must be transparent", 0, Color.alpha(f.getPixel(2, 2)))
        }
        val px = IntArray(512 * 512)
        frames[0].getPixels(px, 0, 512, 0, 0, 512, 512)
        fun isRed(c: Int) = Color.alpha(c) == 255 && Color.red(c) > 200 && Color.green(c) < 80 && Color.blue(c) < 80
        val red = px.count(::isRed)
        val blue = px.count { Color.alpha(it) > 0 && Color.blue(it) > 150 && Color.red(it) < 60 }
        assertTrue("ball should be visible ($red px)", red > 5_000)
        assertTrue("background should be gone ($blue px)", blue < 200)
        // White outline: walking left from the ball's edge (through its middle row) we must hit opaque white.
        val redRows = (0 until 512).filter { y -> (0 until 512).any { x -> isRed(px[y * 512 + x]) } }
        val row = redRows[redRows.size / 2]
        val edge = (0 until 512).first { x -> isRed(px[row * 512 + x]) }
        assertTrue("ball must leave room for the outline (edge at $edge)", edge >= 8)
        assertEquals("outline pixel", Color.WHITE, px[row * 512 + edge - 4])
        assertEquals("outside the outline", 0, Color.alpha(px[row * 512 + edge - 14]))

        val bytes = WebpEncoder.encodeAnimated(frames, media.durationsMs)
        File(out, "pipeline_gif.webp").writeBytes(bytes)
        assertTrue("animated sticker is ${bytes.size} bytes", bytes.size <= 500 * 1024)
        val info = WebpInfo.parse(bytes)!!
        assertTrue(info.animated)
        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertTrue(info.frameCount >= 2)
        assertEquals(960, info.totalDurationMs)
    }

    @Test
    fun photoBecomesStillSticker() = runBlocking {
        val media = MediaLoader.load(context, media("photo.png"))
        assertFalse(media.animated)
        assertEquals(800, media.width)
        assertEquals(600, media.height)

        val session = EditSession(media)
        session.rotateClockwise() // 600 x 800 (portrait)
        session.setCropRect(RectF(0.1f, 0.1f, 0.9f, 0.9f))
        session.ensureWorking()
        assertEquals(384, session.workWidth)
        assertEquals(512, session.workHeight)

        val erase = MaskOp.Brush(floatArrayOf(0f, 0f, 0.3f, 0.3f), 0.1f, erase = true)
        val preview = session.renderPreview(0, listOf(erase))
        assertEquals("erased corner", 0, Color.alpha(preview.getPixel(5, 5)))
        assertEquals("untouched center", 255, Color.alpha(preview.getPixel(192, 256)))

        // Restoring over the erased area brings the pixels back.
        val restore = MaskOp.Brush(floatArrayOf(0f, 0f, 0.3f, 0.3f), 0.12f, erase = false)
        val restored = session.renderPreview(0, listOf(erase, restore))
        assertEquals(255, Color.alpha(restored.getPixel(5, 5)))

        // Contiguous wand on the yellow subject removes it and nothing else.
        val wand = MaskOp.Wand(0.5f, 0.5f, 30, contiguous = true)
        val noSubject = session.renderPreview(0, listOf(wand))
        assertEquals(0, Color.alpha(noSubject.getPixel(192, 256)))
        assertEquals(255, Color.alpha(noSubject.getPixel(10, 500)))

        val frames = session.renderFinalFrames(listOf(erase), FinishOptions(false, 8, Color.WHITE, autoFit = false))
        val still = WebpEncoder.encodeStill(frames[0])
        File(out, "pipeline_photo.webp").writeBytes(still)
        assertTrue("still sticker is ${still.size} bytes", still.size <= 100 * 1024)
        WebpInfo.parse(still)!!.let {
            assertFalse(it.animated)
            assertEquals(512, it.width)
            assertEquals(512, it.height)
        }
        val asAnimation = WebpEncoder.encodeStillAsAnimated(frames[0])
        File(out, "pipeline_photo_animated.webp").writeBytes(asAnimation)
        WebpInfo.parse(asAnimation)!!.let {
            assertTrue(it.animated)
            assertEquals(2, it.frameCount)
        }
    }

    private fun simpleSticker(color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawCircle(256f, 256f, 200f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return WebpEncoder.encodeStill(bmp)
    }

    @Test
    fun packStorageProviderAndBackup() = runBlocking {
        val repo = PackRepository.get(context)
        val pack = repo.createPack("Test pack", "Tester", animated = false)
        val stickers = listOf(simpleSticker(Color.RED), simpleSticker(Color.GREEN), simpleSticker(Color.BLUE))
        stickers.forEachIndexed { i, bytes -> repo.addSticker(pack.id, bytes, "Sticker $i", listOf("😀", "🎉")) }

        var p = repo.pack(pack.id)!!
        assertEquals(3, p.stickers.size)
        assertEquals(4, p.imageDataVersion)
        val tray = repo.trayFile(p)!!
        assertTrue(tray.isFile)
        assertTrue(tray.length() <= 50 * 1024)
        BitmapFactory.decodeFile(tray.path).let {
            assertEquals(96, it.width)
            assertEquals(96, it.height)
        }
        assertEquals(emptyList<String>(), WhatsApp.validate(repo, p))

        // What WhatsApp sees through the ContentProvider.
        val provider = Robolectric.setupContentProvider(StickerContentProvider::class.java, StickerContentProvider.AUTHORITY)
        provider.query(StickerContentProvider.metadataUri(), null, null, null, null).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(pack.id, c.getString(c.getColumnIndexOrThrow("sticker_pack_identifier")))
            assertEquals("Test pack", c.getString(c.getColumnIndexOrThrow("sticker_pack_name")))
            assertEquals("Tester", c.getString(c.getColumnIndexOrThrow("sticker_pack_publisher")))
            assertEquals(p.trayFile, c.getString(c.getColumnIndexOrThrow("sticker_pack_icon")))
            assertEquals("4", c.getString(c.getColumnIndexOrThrow("image_data_version")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("animated_sticker_pack")))
        }
        provider.query(StickerContentProvider.stickersUri(pack.id), null, null, null, null).use { c ->
            assertEquals(3, c.count)
            c.moveToFirst()
            assertEquals(p.stickers[0].fileName, c.getString(c.getColumnIndexOrThrow("sticker_file_name")))
            assertEquals("😀,🎉", c.getString(c.getColumnIndexOrThrow("sticker_emoji")))
            assertEquals("Sticker 0", c.getString(c.getColumnIndexOrThrow("sticker_accessibility_text")))
        }
        val base = "content://${StickerContentProvider.AUTHORITY}/stickers_asset/${pack.id}"
        provider.openAssetFile(Uri.parse("$base/${p.stickers[0].fileName}"), "r")!!.createInputStream().use {
            assertArrayEquals(stickers[0], it.readBytes())
        }
        provider.openAssetFile(Uri.parse("$base/${p.trayFile}"), "r")!!.createInputStream().use {
            assertTrue(it.readBytes().isNotEmpty())
        }
        assertNull("only pack files are served", provider.openAssetFile(Uri.parse("$base/pack.json"), "r"))

        // Changes bump the version WhatsApp uses to refresh, and the tray follows the first sticker.
        repo.markSynced(pack.id)
        assertFalse(repo.pack(pack.id)!!.changedSinceSync)
        repo.deleteSticker(pack.id, p.stickers[0].id)
        p = repo.pack(pack.id)!!
        assertEquals(2, p.stickers.size)
        assertEquals(5, p.imageDataVersion)
        assertTrue(p.changedSinceSync)
        assertEquals("tray_${p.stickers[0].id}.png", p.trayFile)
        repo.setTraySticker(pack.id, p.stickers[1].id)
        assertEquals("tray_${p.stickers[1].id}.png", repo.pack(pack.id)!!.trayFile)
        repo.addSticker(pack.id, stickers[0], "Back again", listOf("🔥"))

        // Backup round trip.
        val backupFile = File(context.cacheDir, "backup.zip")
        Backup.exportTo(context, Uri.fromFile(backupFile), repo.snapshot())
        assertTrue(backupFile.length() > 0)
        File(out, "backup_sample.zip").writeBytes(backupFile.readBytes())

        val plan = Backup.prepareImport(context, Uri.fromFile(backupFile))
        assertEquals(1, plan.packs.size)
        assertEquals(3, plan.stickerCount)
        assertEquals(1, plan.conflicts)
        assertEquals(0, Backup.runImport(context, plan, asCopies = false).single().added)

        // A sticker deleted on the phone comes back when merging the backup.
        val victim = repo.pack(pack.id)!!.stickers.last()
        repo.deleteSticker(pack.id, victim.id)
        val merged = Backup.runImport(context, Backup.prepareImport(context, Uri.fromFile(backupFile)), asCopies = false).single()
        assertEquals(1, merged.added)
        assertEquals(3, repo.pack(pack.id)!!.stickers.size)
        assertTrue(repo.pack(pack.id)!!.stickers.any { it.name == victim.name && it.emojis == victim.emojis })

        // Import as a separate copy.
        val copy = Backup.runImport(context, Backup.prepareImport(context, Uri.fromFile(backupFile)), asCopies = true).single()
        assertEquals(3, copy.added)
        assertNotEquals(pack.id, copy.packId)
        assertEquals(2, repo.snapshot().size)

        // Move between packs of the same type.
        val moving = repo.pack(copy.packId)!!.stickers.first()
        repo.moveSticker(copy.packId, moving.id, pack.id)
        assertEquals(4, repo.pack(pack.id)!!.stickers.size)
        assertEquals(2, repo.pack(copy.packId)!!.stickers.size)

        // Animated packs refuse still stickers in validation.
        val animatedPack = repo.createPack("Animated", "Tester", animated = true)
        repeat(3) { repo.addSticker(animatedPack.id, stickers[0], "", emptyList()) }
        val problems = WhatsApp.validate(repo, repo.pack(animatedPack.id)!!)
        assertEquals(3, problems.size)

        repo.deletePack(copy.packId)
        assertNull(repo.pack(copy.packId))
        assertFalse(repo.packDir(copy.packId).exists())

        // A fresh repository reads everything back from disk.
        resetRepository()
        val reloaded = PackRepository.get(context)
        assertEquals(4, reloaded.pack(pack.id)!!.stickers.size)
        assertEquals(repo.pack(pack.id)!!.imageDataVersion, reloaded.pack(pack.id)!!.imageDataVersion)
    }
}
