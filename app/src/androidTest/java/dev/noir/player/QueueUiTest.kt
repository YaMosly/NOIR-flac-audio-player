package dev.noir.player

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QueueUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val library get()=(context.applicationContext as NoirApp).library
    @Test fun libraryActionsUndoAndQueueSearchWorkOnASmallScreen() {
        val tracks=runBlocking {withTimeout(10000){library.tracks.first{it.size==3}}}
        val track=tracks[1]
        val original=library.preferences.value.lessTrack(track.id)
        compose.runOnIdle {library.setLessTrack(track.id,false);library.setSmart(true)}
        try {
            compose.onNodeWithContentDescription("Библиотека").performClick()
            compose.onAllNodesWithContentDescription("Действия: ${track.title}").onLast().performClick()
            compose.onNodeWithText("Реже этот трек").performClick()
            compose.waitUntil(5000){library.preferences.value.lessTrack(track.id)}
            compose.onNodeWithText("Отменить",useUnmergedTree=false).performClick()
            compose.waitUntil(5000){!library.preferences.value.lessTrack(track.id)}
            compose.onAllNodesWithContentDescription("Действия: ${track.title}").onLast().performClick()
            compose.onNodeWithText("Слушать следующим").performClick()
            compose.onNodeWithContentDescription("Очередь").performClick()
            compose.onNodeWithText("Очередь").assertExists()
            compose.onNodeWithText("Поиск в очереди").performTextInput("not-in-queue-123")
            compose.onNodeWithText("В очереди ничего не найдено").assertExists()
            compose.onNodeWithText("Поиск в очереди").performTextClearance()
            compose.waitForIdle()
            val bitmap=instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null),"noir-queue-v080.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        } finally {compose.runOnIdle {library.setLessTrack(track.id,original)}}
    }
}
