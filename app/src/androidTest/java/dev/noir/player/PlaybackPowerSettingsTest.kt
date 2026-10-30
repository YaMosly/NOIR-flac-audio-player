package dev.noir.player

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Settings dialog and return to NOIR, only on the dedicated emulator. */
class PlaybackPowerSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
    }
    private fun eventually(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < until) { if (condition()) return; Thread.sleep(50) }
        fail("Expected system setting state was not reached")
    }
    private fun clickSystemButton(id: String) {
        eventually {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@eventually false
            if (root.packageName?.toString() != "com.android.settings") return@eventually false
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }
    @Test fun denyingThenGrantingSystemExemptionRefreshesTheVisibleStatus() {
        assertTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val original = PlaybackPowerSettings.isExempt(context)
        try {
            shell("cmd deviceidle whitelist -${context.packageName}")
            eventually { !PlaybackPowerSettings.isExempt(context) }
            compose.onNodeWithContentDescription("Настройки").performClick()
            compose.onNodeWithText("Работа при выключенном экране").performScrollTo().performClick()
            compose.onNodeWithText("Разрешить работу без оптимизации").performScrollTo().performClick()
            clickSystemButton("android:id/button2")
            compose.waitForIdle()
            assertFalse(PlaybackPowerSettings.isExempt(context))
            compose.onNodeWithText("Android: исключение из оптимизации батареи не разрешено").assertExists()
            compose.onNodeWithText("Разрешить работу без оптимизации").performScrollTo().performClick()
            clickSystemButton("android:id/button1")
            eventually { PlaybackPowerSettings.isExempt(context) }
            compose.waitForIdle()
            compose.onNodeWithText("Android: NOIR в списке исключений из оптимизации батареи").assertExists()
            compose.onNodeWithText("Разрешить работу без оптимизации").assertDoesNotExist()
            assertTrue(BackgroundAudioDiagnostics.report(context).contains("batteryExempt=true"))
        } finally {
            shell("cmd deviceidle whitelist ${if (original) "+" else "-"}${context.packageName}")
        }
    }
}
