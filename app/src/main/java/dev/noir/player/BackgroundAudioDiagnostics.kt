package dev.noir.player

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/** Small local event history, outside the native USB hot loop. */
object BackgroundAudioDiagnostics {
    private val events = ArrayDeque<String>()
    private fun state(context: Context): String {
        val power = context.getSystemService(PowerManager::class.java)
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        return "screen=${power.isInteractive}, idle=${power.isDeviceIdleMode}, powerSave=${power.isPowerSaveMode}, batteryExempt=${power.isIgnoringBatteryOptimizations(context.packageName)}, importance=${process.importance}"
    }
    @Synchronized fun record(context: Context, event: String) {
        val snapshot = runCatching { state(context) }.getOrElse { "unavailable: ${it.javaClass.simpleName}" }
        events.addLast("${SystemClock.elapsedRealtime()} ms · $event · $snapshot")
        while (events.size > 20) events.removeFirst()
    }
    @Synchronized fun report(context: Context): String = buildString {
        appendLine("Фон и энергопитание: ${runCatching { state(context) }.getOrDefault("неизвестно")}")
        appendLine("USB: очередь 128 мс; PCM-буфер 8 с; стартовый запас 500 мс")
        events.forEach(::appendLine)
    }
}
