package com.localstickers.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.localstickers.app.data.PackRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowActivity
import java.io.File

/**
 * Drives the real UI: open a pack, tap "Add sticker", pick a GIF, go through crop -> background -> save,
 * and check the sticker lands in that pack.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AddStickerFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun freshRepository() {
        PackRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun gif(): Uri = Uri.fromFile(File(requireNotNull(javaClass.classLoader?.getResource("media/ball.gif")).toURI()))

    /**
     * Returns the picked file the way Android does on a phone: the picker covers the app (onPause/onStop) and the
     * result arrives after onStart but before onResume.
     */
    private fun ActivityScenario<MainActivity>.returnPickedFile(uri: Uri) {
        lateinit var request: ShadowActivity.IntentForResult
        onActivity { request = shadowOf(it).nextStartedActivityForResult }
        moveToState(Lifecycle.State.STARTED)
        onActivity { shadowOf(it).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri)) }
        moveToState(Lifecycle.State.RESUMED)
    }

    @Test
    fun addStickerInsidePackOpensEditorAndSavesIntoThatPack() {
        val pack = runBlocking { PackRepository.get(context).createPack("Test pack", "Tester", animated = false) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForText("Test pack")
            compose.click("Test pack")
            compose.waitForText("0/30 · Still · Tester")
            compose.click("Add sticker")
            scenario.returnPickedFile(gif())

            compose.waitForText("1. Crop")
            compose.waitForText("Free")
            compose.click("Next")
            compose.waitForText("Remove background")
            compose.click("Next")
            compose.waitForText("Save sticker")
            compose.onNodeWithText("Save sticker", useUnmergedTree = true).performScrollTo().performClick()

            // While "Saving…" spins, the test clock would render frames non-stop and starve the background work
            // under Robolectric. Pause the clock and only run the main looper until the sticker is stored.
            compose.mainClock.autoAdvance = false
            val deadline = System.currentTimeMillis() + 60_000
            while (PackRepository.get(context).pack(pack.id)?.stickers?.size != 1) {
                check(System.currentTimeMillis() < deadline) { "The sticker was not saved within 60 s" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(50)
            }
            compose.mainClock.autoAdvance = true
            compose.waitForText("1/30 · Still · Tester")
            assertEquals(1, PackRepository.get(context).snapshot().size)
        }
    }

    @Test
    fun addFromFilesMenuInsidePackOpensEditor() {
        runBlocking { PackRepository.get(context).createPack("Test pack", "Tester", animated = false) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForText("Test pack")
            compose.click("Test pack")
            compose.waitForText("0/30 · Still · Tester")
            compose.onNodeWithContentDescription("More").performClick()
            compose.click("Add image or GIF from files")
            scenario.returnPickedFile(gif())
            compose.waitForText("1. Crop")
            compose.waitForText("Free")
        }
    }

    @Test
    fun deletingPackGoesBackToHomeOnce() {
        runBlocking { PackRepository.get(context).createPack("Test pack", "Tester", animated = false) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForText("Test pack")
            compose.click("Test pack")
            compose.waitForText("0/30 · Still · Tester")
            compose.onNodeWithContentDescription("More").performClick()
            compose.click("Delete pack")
            compose.click("Delete")
            compose.waitForText("No sticker packs yet")
            compose.waitForIdle()
            // Still on the home screen (a double "back" would leave an empty screen).
            compose.onNodeWithText("New sticker", useUnmergedTree = true).assertExists()
            assertEquals(0, PackRepository.get(context).snapshot().size)
        }
    }

    @Test
    fun newStickerFromHomeOpensEditor() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForText("New sticker")
            compose.click("New sticker")
            scenario.returnPickedFile(gif())
            compose.waitForText("1. Crop")
            compose.waitForText("Free")
        }
    }
}

// Button labels are merged into their button's node, so look them up in the unmerged tree.
private fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 30_000) =
    waitUntil(timeoutMs) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

private fun ComposeTestRule.click(text: String) {
    onNodeWithText(text, useUnmergedTree = true).performClick()
}
