package dev.noir.player

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test

class AlbumUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun albumBrowserFindsAndStartsAnOrderedAlbum() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val library=(context.applicationContext as NoirApp).library
        val tracks=runBlocking {withTimeout(10000){library.tracks.first{it.size==3}}}
        compose.onNodeWithContentDescription("Библиотека").performClick()
        compose.onNodeWithText("Альбомы").performClick()
        compose.onAllNodesWithText(tracks[0].album).onFirst().assertExists()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null),"noir-albums-v090.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        compose.onAllNodesWithText(tracks[0].album).onFirst().performClick()
        compose.onNodeWithContentDescription("Пауза").assertExists()
    }
}
